package com.github.catvod.spider;

import android.text.TextUtils;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Playback bridge for the YouTube spider: builds local DASH manifests and answers the segment
 * requests the player issues against them.
 *
 * <p>Two independent bridges live here:
 * <ul>
 *   <li><b>direct</b> ({@code mpd}/{@code media}/{@code single}/{@code yt_progressive}) — plain
 *       googlevideo URLs with real {@code initRange}/{@code indexRange}, proxied byte for byte.</li>
 *   <li><b>SABR</b> ({@code sabr_mpd}/{@code sabr_mpd2}/{@code sabr}/{@code sabr_time}) — formats
 *       with no per-format URL, negotiated through {@link YTSabrSession}. {@code sabr_mpd} uses
 *       local segment numbers; {@code sabr_mpd2} uses target-time URLs backed by real SABR
 *       MEDIA_HEADER/native-sequence mapping and the session cache.</li>
 * </ul>
 */
final class YTPlay {

    private static final long PLAY_CACHE_MS = 21600 * 1000L;
    private static final long SABR_CACHE_MS = 1800 * 1000L;
    private static final String[] RISKY_AUDIO = {"ec-3", "ec3", "eac3", "ac-3", "ac3", "dts", "truehd"};

    private static final java.util.concurrent.atomic.AtomicLong OWNER_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private final YouTubeLite yt;
    private final Map<String, String> header;
    private final JsonObject ext;
    private final String siteKey;
    /** Identifies this YTPlay instance inside {@link YTServer}'s owner registry. */
    private final String ownerId = "p" + OWNER_SEQ.incrementAndGet();

    private final Map<String, PlayData> playCache = new HashMap<>();
    private final Map<String, SabrData> sabrCache = new HashMap<>();
    // A and B can request the same video MPD concurrently. Serialize extraction per video so
    // both routes share one successful TVHTML5/poToken response instead of racing BotGuard.
    private final Map<String, Object> sabrExtractLocks = new HashMap<>();
    private final Map<String, Long> refreshMarks = new HashMap<>();
    // A local MPD can outlive the host's current episode during sequential playback.
    // Include a generation in the SABR state key so a new extraction never reuses old UMP state.
    // Static: a Spider rebuilt after the host's registry clear must not restart the counter at 1 and
    // collide with a state key the previous instance is still serving.
    private static final Map<String, Long> sabrGenerations = new HashMap<>();
    /** Candidate signature behind each generation, so re-activating the same one does not bump it. */
    private static final Map<String, String> sabrSignatures = new HashMap<>();
    /** State keys created by this instance; only these may be torn down by it. */
    private final Set<String> ownedStateKeys = Collections.synchronizedSet(new HashSet<>());
    private final Object sabrSwitchLock = new Object();
    private volatile boolean destroyed;
    private volatile boolean destroyRequested;
    /** Last time the player itself asked for media; drives deferred teardown. */
    private volatile long lastPlayerAt;
    private volatile boolean destroyScheduled;
    private final Object destroyMonitor = new Object();

    YTPlay(YouTubeLite yt, Map<String, String> header, JsonObject ext, String siteKey) {
        this.yt = yt;
        this.header = header;
        this.ext = ext;
        this.siteKey = siteKey;
        YTServer.register(ownerId, this);
    }

    /** Cached direct-play tracks for one {@code vid + quality} pair. */
    private static class PlayData {
        List<YTFormat> videoTracks = new ArrayList<>();
        YTFormat videoItem;
        YTFormat audioItem;
        List<YTFormat> audioCandidates = new ArrayList<>();
        List<String> failedAudioKeys = new ArrayList<>();
        long duration;
        long expires;
    }

    /** One compatible SABR client pairing. */
    private static class Candidate {
        String client;
        YTFormat video;
        YTFormat audio;
        String videoSig;
        String audioSig;
    }

    /** Cached SABR session state for one video. */
    private static class SabrData {
        List<Candidate> candidates = new ArrayList<>();
        int activeIndex;
        YTFormat videoItem;
        YTFormat audioItem;
        String stateKey;
        long duration;
        long expires;
    }

    /* ------------------------------------------------------------------ */
    /* proxy entry point                                                  */
    /* ------------------------------------------------------------------ */

    void destroy() {
        // The host may call destroy() for mobile-home-destroy while the player still owns the
        // media URL. Defer hard teardown until the *player* has been idle for a grace period.
        destroyRequested = true;
        lastPlayerAt = System.currentTimeMillis();
        synchronized (destroyMonitor) {
            if (destroyed || destroyScheduled) return;
            destroyScheduled = true;
            Thread deferred = new Thread(() -> {
                while (!destroyed) {
                    try {
                        Thread.sleep(5000L);
                    } catch (InterruptedException ignored) {
                    }
                    if (System.currentTimeMillis() - lastPlayerAt >= 30000L) {
                        hardDestroy();
                        return;
                    }
                }
            }, "youtube-deferred-destroy");
            deferred.setDaemon(true);
            deferred.start();
        }
    }

    private void hardDestroy() {
        synchronized (destroyMonitor) {
            if (destroyed) return;
            destroyed = true;
            YTServer.unregister(ownerId);
            // Cancel only the sessions this instance created. YouTubeLite.sabrState is now static so
            // a rebuilt Spider can adopt a running session; wiping it wholesale here would kill the
            // playback that survived the host's registry clear, which is the exact failure this is
            // meant to prevent. Sessions nobody adopts are reaped by the producer's own idle exit.
            synchronized (YouTubeLite.sabrState) {
                for (String key : new ArrayList<>(ownedStateKeys)) {
                    YTSabrSession value = YouTubeLite.sabrState.get(key);
                    if (value == null) continue;
                    // Another live YTPlay may have adopted this session after our grace period
                    // started; only tear down sessions still attributed to us.
                    if (!ownerId.equals(YouTubeLite.sabrOwners.get(key))) continue;
                    value.cancel();
                    YouTubeLite.sabrState.remove(key);
                    YouTubeLite.sabrOwners.remove(key);
                }
            }
            ownedStateKeys.clear();
            // Do not close the shared HTTP client here: another Spider instance rebuilt after the
            // host's clear may still be serving from a session that uses it.
            sabrCache.clear();
            playCache.clear();
        }
    }

    Object[] proxy(Map<String, String> params) {
        // Only a real player request counts as liveness. The SABR producer also calls in through
        // this JAR, so refreshing the deadline on every proxy() call let the session keep itself
        // alive forever: observed downloading for 31s after the player was destroyed, and two
        // concurrent sessions (old producer + new one) fetching the same 2160p video at once.
        lastPlayerAt = System.currentTimeMillis();
        if (destroyed) return text(499, "YouTube 播放会话已关闭");
        String type = params.get("type");
        if (type == null) return null;
        // Move the manifest onto the JAR-owned server via a redirect. The player's initial URL must
        // stay proxy:// so the host does not classify playback as EXTERNAL_LOOPBACK_PROXY and stall
        // 5s on a readiness probe it can never pass; but the manifest is re-fetched during playback
        // (observed at +15s and +18s), and by then the host may have cleared its jar-loader, which
        // answers those refetches with HTTP 500 null_or_empty and kills playback. Redirecting once
        // gets the player a durable URL for every later manifest refresh.
        if ("sabr_mpd".equals(type) || "sabr_mpd2".equals(type)) {
            // A manifest request is the first sign the player moved to another video. Stop the
            // previous one's producer here rather than waiting for a size-based reap that a busy
            // (and therefore non-idle) session never triggers.
            cancelOtherVideos(params.get("vid"));
            if (!"1".equals(params.get("ytr"))) {
                String query = manifestQuery(params, type);
                String owned = query == null ? null : YTServer.url(ownerId, siteKey, query);
                if (owned != null) return redirect(owned);
            }
        }
        switch (type) {
            case "mpd":
                return proxyMpd(params);
            case "media":
                return proxyMedia(params);
            case "single":
                return proxySingle(params);
            case "yt_progressive":
                return proxyProgressive(params);
            case "sabr_mpd":
                return proxySabrMpd(params);
            // SABR-B now uses a target-time SegmentTemplate backed by real MEDIA_HEADER mapping.
            // Keep the old route name so existing quality-menu URLs remain compatible.
            case "sabr_mpd2":
                return proxySabrMpd2(params);
            case "sabr_time":
                return proxySabrTime(params);
            case "sabr_range":
                return proxySabrRange(params);
            case "sabr":
                return proxySabr(params);
            default:
                return null;
        }
    }

    /**
     * Absolute media URL, usable inside a manifest.
     *
     * <p>Prefers this JAR's own loopback server. The host's {@code /proxy} endpoint stops routing
     * into this JAR the moment the host clears its jar-loader registry (logged as
     * {@code base-loader: clear reason=mobile-home-destroy}), after which every segment request is
     * answered with HTTP 500 {@code null_or_empty} and the player dies once its buffer drains.
     * {@link YTServer} keeps serving because this JAR owns the socket.
     */
    String localUrl(String params) {
        String owned = YTServer.url(ownerId, siteKey, params);
        if (owned != null) return owned;
        return Proxy.getUrl() + "?do=csp&siteKey=" + siteKey + params;
    }

    /**
     * Identity of a SABR session: video + quality + bridge, deliberately WITHOUT {@code sid}.
     *
     * <p>{@code sid} comes from {@code playerContent}, which increments a counter on every single
     * play request. Including it meant replaying the same video produced a brand new cache key, a
     * new generation and therefore a cold session, while the warm one (already holding a negotiated
     * cookie and an indexed timeline) was orphaned. The log shows exactly that: a manifest reporting
     * {@code videoReal=7} was followed 3s later by one reporting {@code videoReal=0} for the same
     * video, then {@code Canceled: SABR session closed} and HTTP 503 on the init segments.
     *
     * <p>That is why resuming a video with a watch-history position was the worst case: resuming
     * always issues a fresh play request, so it always started cold and had to renegotiate before
     * the player's 12s segment deadline. Entering a second time appeared to "fix" it only because
     * the extract cache had warmed by then.
     *
     * <p>Switching to a different video is handled explicitly by {@link #cancelOtherVideos(String)},
     * and a genuine client failover still bumps the generation inside {@link #activate}, so dropping
     * {@code sid} does not let stale state leak across episodes.
     */
    private static String sabrCacheKey(String vid, String quality, String sid, boolean b) {
        String key = (b ? "yt_sabr_b_" : "yt_sabr_") + vid;
        if (!"best".equals(quality)) key += "_" + quality;
        return key;
    }

    /* ------------------------------------------------------------------ */
    /* direct track selection                                             */
    /* ------------------------------------------------------------------ */

    private static String audioKey(YTFormat item) {
        if (item == null) return "";
        return low(item.client) + "|" + item.itag + "|" + low(item.codecs);
    }

    private static String audioSignature(YTFormat item) {
        if (item == null) return "";
        return item.itag + "|" + low(mimeBase(item.mimeType)) + "|" + low(item.codecs);
    }

    private static String low(String text) {
        return text == null ? "" : text.toLowerCase(Locale.US);
    }

    private static String mimeBase(String mime) {
        if (mime == null) return "";
        int index = mime.indexOf(';');
        return index < 0 ? mime : mime.substring(0, index);
    }

    private static int codecRank(YTFormat item) {
        String text = low(item.codecs) + " " + low(item.mimeType);
        if (text.contains("mp4a") || text.contains("aac")) return 5;
        if (text.contains("opus")) return 4;
        if (text.contains("vorbis")) return 3;
        if (text.contains("mp3")) return 2;
        return 1;
    }

    private static int clientOrder(String client) {
        String name = client == null ? "" : client.toUpperCase(Locale.US);
        switch (name) {
            case "ANDROID_VR":
                return 6;
            case "IOS":
                return 5;
            case "MWEB":
                return 4;
            case "ANDROID":
                return 3;
            case "WEB_INITIAL":
                return 2;
            case "WEB":
                return 1;
            default:
                return 0;
        }
    }

    /**
     * Ranks direct audio-only formats.
     *
     * <p>EC-3/AC-3 and friends are only used when nothing else exists, so a high-bitrate surround
     * track can never displace plain AAC.
     */
    private List<YTFormat> audioCandidates(List<YTFormat> formats, List<String> failedKeys,
                                           String requiredSignature, String sameClient) {
        Set<String> failed = failedKeys == null ? new HashSet<>() : new HashSet<>(failedKeys);
        String requiredClient = sameClient == null ? "" : sameClient.toUpperCase(Locale.US);
        List<YTFormat> audios = new ArrayList<>();
        for (YTFormat item : formats) {
            if (item.isSabr() || TextUtils.isEmpty(item.url)) continue;
            if (!item.hasAudio() || item.hasVideo()) continue;
            if (!requiredClient.isEmpty() && !requiredClient.equals(low(item.client).toUpperCase(Locale.US))) continue;
            if (failed.contains(audioKey(item))) continue;
            audios.add(item);
        }
        List<YTFormat> safe = new ArrayList<>();
        for (YTFormat item : audios) {
            String text = low(item.codecs) + " " + low(item.mimeType);
            boolean risky = false;
            for (String marker : RISKY_AUDIO) {
                if (text.contains(marker)) {
                    risky = true;
                    break;
                }
            }
            if (!risky) safe.add(item);
        }
        if (!safe.isEmpty()) audios = safe;
        if (requiredSignature != null) {
            List<YTFormat> matched = new ArrayList<>();
            for (YTFormat item : audios) if (requiredSignature.equals(audioSignature(item))) matched.add(item);
            audios = matched;
        }
        audios.sort((a, b) -> {
            int cmp = Integer.compare(codecRank(b), codecRank(a));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(clientOrder(b.client), clientOrder(a.client));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(b.itag == 140 ? 1 : 0, a.itag == 140 ? 1 : 0);
            if (cmp != 0) return cmp;
            return Long.compare(b.bitrate, a.bitrate);
        });
        List<YTFormat> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (YTFormat item : audios) {
            String key = audioKey(item);
            if (seen.contains(key)) continue;
            seen.add(key);
            result.add(item);
        }
        return result;
    }

    /**
     * Switches to the next cached audio candidate after a 403.
     *
     * <p>The MPD has already been published, so only a candidate with the same itag/container/codec
     * may be substituted; anything else would break SegmentBase compatibility.
     */
    private YTFormat nextCachedAudio(PlayData data, YTFormat failedItem) {
        List<String> failedKeys = new ArrayList<>(data.failedAudioKeys);
        String failedKey = audioKey(failedItem);
        if (!failedKeys.contains(failedKey)) failedKeys.add(failedKey);
        String signature = audioSignature(data.audioItem == null ? failedItem : data.audioItem);
        List<YTFormat> remaining = new ArrayList<>();
        for (YTFormat item : data.audioCandidates) {
            if (failedKeys.contains(audioKey(item))) continue;
            if (!signature.equals(audioSignature(item))) continue;
            remaining.add(item);
        }
        data.failedAudioKeys = failedKeys;
        if (remaining.isEmpty()) return null;
        data.audioItem = remaining.get(0);
        return data.audioItem;
    }

    /**
     * Re-resolves direct tracks when the cache is gone or a URL was rejected.
     *
     * <p>Forced refreshes are throttled: one per 10s per {@code vid+quality}. Without it every
     * rejected Range triggers its own extraction, and the refreshed URLs get rejected just as fast.
     */
    private PlayData rebuild(String vid, String quality, boolean forceRefresh,
                             List<String> failedAudioKeys, String requiredAudioSignature) {
        String cacheKey = "yt_" + vid + "_" + quality;
        if (forceRefresh) {
            synchronized (refreshMarks) {
                Long last = refreshMarks.get(cacheKey);
                if (last != null && System.currentTimeMillis() - last < 10000) {
                    PlayData cached = playCache.get(cacheKey);
                    if (cached != null) return cached;
                }
                refreshMarks.put(cacheKey, System.currentTimeMillis());
            }
        }
        try {
            YouTubeLite.Extracted data = yt.extract(vid, forceRefresh);
            String select = "8k".equals(quality) || "8k_hdr".equals(quality) || "4k".equals(quality)
                    || "2k".equals(quality) || "1080p".equals(quality) ? quality : "best";
            List<YTFormat> allTracks = yt.chooseVideoTracks(directOnly(data.formats), select);
            String wanted = "hdr".equals(quality) || "8k_hdr".equals(quality) ? "HDR" : "SDR";
            List<YTFormat> videoTracks = new ArrayList<>();
            for (YTFormat item : allTracks) if (wanted.equals(item.trackName)) videoTracks.add(item);
            if (videoTracks.isEmpty() && !allTracks.isEmpty()
                    && !"8k".equals(quality) && !"8k_hdr".equals(quality)) {
                videoTracks.add(allTracks.get(0));
            }
            List<YTFormat> candidates = audioCandidates(data.formats, failedAudioKeys, requiredAudioSignature, null);
            if (candidates.isEmpty() && failedAudioKeys != null && !failedAudioKeys.isEmpty()) {
                // Every same-format candidate was rejected; retry without the failure list so
                // playback can continue on a freshly signed URL.
                candidates = audioCandidates(data.formats, null, null, null);
            }
            if (videoTracks.isEmpty() || candidates.isEmpty()) return null;
            PlayData value = new PlayData();
            value.videoTracks = videoTracks;
            value.videoItem = videoTracks.get(0);
            value.audioItem = candidates.get(0);
            value.audioCandidates = candidates;
            value.failedAudioKeys = failedAudioKeys == null ? new ArrayList<>() : new ArrayList<>(failedAudioKeys);
            value.duration = data.duration;
            value.expires = System.currentTimeMillis() + PLAY_CACHE_MS;
            playCache.put(cacheKey, value);
            return value;
        } catch (Throwable e) {
            return null;
        }
    }

    private static List<YTFormat> directOnly(List<YTFormat> formats) {
        List<YTFormat> out = new ArrayList<>();
        for (YTFormat item : formats) {
            if (item.isSabr() || TextUtils.isEmpty(item.url)) continue;
            out.add(item);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* direct proxies                                                     */
    /* ------------------------------------------------------------------ */

    private Object[] proxyMpd(Map<String, String> params) {
        String vid = params.get("vid");
        String quality = params.get("quality") == null ? "1080p" : params.get("quality");
        PlayData data = vid == null ? null : playCache.get("yt_" + vid + "_" + quality);
        if (data == null && vid != null) data = rebuild(vid, quality, false, null, null);
        if (data == null) return text(404, "视频缓存已过期或不存在");
        List<YTFormat> videoTracks = data.videoTracks.isEmpty()
                ? Collections.singletonList(data.videoItem) : data.videoTracks;
        YTFormat audio = data.audioItem;
        boolean direct = "direct".equalsIgnoreCase(YouTubeLite.optString(ext, "seg", "proxy"));
        String mediaBase = localUrl("&type=media&vid=" + enc(vid) + "&quality=" + enc(quality));
        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT")
                .append(data.duration).append("S\" minBufferTime=\"PT1.5S\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
                .append("  <Period id=\"1\" start=\"PT0S\">\n");
        for (YTFormat item : videoTracks) {
            if (item == null) continue;
            String baseUrl = direct ? item.url : mediaBase + "&track=video&itag=" + item.itag;
            mpd.append("    <AdaptationSet mimeType=\"").append(esc(mimeBase(fallback(item.mimeType, "video/webm"))))
                    .append("\" startWithSAP=\"1\" segmentAlignment=\"true\" scanType=\"progressive\">\n")
                    .append("      <Representation id=\"v").append(item.itag == 0 ? 1 : item.itag)
                    .append("\" bandwidth=\"").append(item.bitrate == 0 ? 1000000 : item.bitrate)
                    .append("\" codecs=\"").append(esc(item.codecs))
                    .append("\" height=\"").append(item.height).append("\" width=\"").append(item.width).append("\">\n")
                    .append("        <BaseURL>").append(esc(baseUrl)).append("</BaseURL>\n")
                    .append("        <SegmentBase indexRange=\"").append(rangeText(item.indexRange))
                    .append("\"><Initialization range=\"").append(rangeText(item.initRange)).append("\"/></SegmentBase>\n")
                    .append("      </Representation>\n")
                    .append("    </AdaptationSet>\n");
        }
        if (audio != null && !TextUtils.isEmpty(audio.url)) {
            String baseUrl = direct ? audio.url : mediaBase + "&track=audio";
            mpd.append("    <AdaptationSet mimeType=\"").append(esc(mimeBase(fallback(audio.mimeType, "audio/mp4"))))
                    .append("\" startWithSAP=\"1\" segmentAlignment=\"true\" lang=\"und\">\n")
                    .append("      <Representation id=\"audio\" bandwidth=\"")
                    .append(audio.bitrate == 0 ? 128000 : audio.bitrate)
                    .append("\" codecs=\"").append(esc(audio.codecs)).append("\" audioSamplingRate=\"44100\">\n")
                    .append("        <BaseURL>").append(esc(baseUrl)).append("</BaseURL>\n")
                    .append("        <SegmentBase indexRange=\"").append(rangeText(audio.indexRange))
                    .append("\"><Initialization range=\"").append(rangeText(audio.initRange)).append("\"/></SegmentBase>\n")
                    .append("      </Representation>\n")
                    .append("    </AdaptationSet>\n");
        }
        mpd.append("  </Period>\n</MPD>");
        return bytes(200, "application/dash+xml", mpd.toString().getBytes(), null);
    }

    private Object[] proxyMedia(Map<String, String> params) {
        String vid = params.get("vid");
        String quality = params.get("quality") == null ? "1080p" : params.get("quality");
        String track = params.get("track");
        boolean known = "video".equals(track) || "audio".equals(track);
        String cacheKey = "yt_" + vid + "_" + quality;
        PlayData data = vid == null ? null : playCache.get(cacheKey);
        if (data == null && vid != null && known) data = rebuild(vid, quality, false, null, null);
        if (data == null || !known) return text(404, "媒体不存在");
        YTFormat item = selectTrack(data, track, params.get("itag"));
        if (item == null || TextUtils.isEmpty(item.url)) return text(404, track + " 流不存在");
        String range = range(params);
        YTHttp.Result response = fetch(item, item.url, range);
        if (response.code == 403) {
            boolean retried = false;
            if ("audio".equals(track)) {
                YTFormat next = nextCachedAudio(data, item);
                if (next != null) {
                    item = next;
                    response = fetch(item, item.url, range);
                    retried = true;
                }
            }
            if (!retried || response.code == 403) {
                List<String> failed = new ArrayList<>(data.failedAudioKeys);
                if ("audio".equals(track)) {
                    String key = audioKey(item);
                    if (!failed.contains(key)) failed.add(key);
                }
                String signature = "audio".equals(track)
                        ? audioSignature(data.audioItem == null ? item : data.audioItem) : null;
                PlayData fresh = rebuild(vid, quality, true, "audio".equals(track) ? failed : null, signature);
                if (fresh != null) {
                    data = fresh;
                    YTFormat refreshed = selectTrack(fresh, track, params.get("itag"));
                    if (refreshed != null && !TextUtils.isEmpty(refreshed.url)) {
                        item = refreshed;
                        response = fetch(item, item.url, range);
                    }
                }
            }
        }
        String contentType = fallback(response.contentType, "application/octet-stream");
        Map<String, String> headers = mediaHeaders(contentType, response);
        return bytes(response.code, contentType, response.body, headers);
    }

    private YTFormat selectTrack(PlayData data, String track, String wantItag) {
        if ("video".equals(track)) {
            List<YTFormat> tracks = data.videoTracks.isEmpty()
                    ? Collections.singletonList(data.videoItem) : data.videoTracks;
            for (YTFormat item : tracks) {
                if (item != null && String.valueOf(item.itag).equals(wantItag)) return item;
            }
            return tracks.isEmpty() ? null : tracks.get(0);
        }
        return data.audioItem;
    }

    private Object[] proxySingle(Map<String, String> params) {
        String vid = params.get("vid");
        PlayData data = vid == null ? null : playCache.get("yt_single_" + vid);
        if (data == null) return text(404, "播放缓存已过期或不存在");
        YTFormat item = data.videoItem;
        if (item == null || TextUtils.isEmpty(item.url)) return text(404, "播放地址不存在");
        YTHttp.Result response = fetch(item, item.url, range(params));
        String contentType = fallback(response.contentType, "video/mp4");
        return bytes(response.code, contentType, response.body, mediaHeaders(contentType, response));
    }

    /**
     * Serves a muxed (progressive) stream in bounded chunks.
     *
     * <p>An open-ended Range is never forwarded: the whole file would be buffered in memory before
     * the player received anything.
     */
    private Object[] proxyProgressive(Map<String, String> params) {
        String vid = params.get("vid");
        if (vid == null) return text(404, "渐进式播放地址不存在");
        long chunk = YouTubeLite.optLong(ext, "progressive_chunk_bytes", 16 * 1024 * 1024);
        chunk = Math.min(32 * 1024 * 1024, Math.max(1024 * 1024, chunk));
        long start = 0;
        Long end = null;
        long[] parsed = parseRange(range(params));
        if (parsed != null) {
            start = parsed[0] < 0 ? 0 : parsed[0];
            end = parsed[1] < 0 ? null : parsed[1];
        }
        if (end == null) end = start + chunk - 1;
        String bounded = "bytes=" + start + "-" + end;
        try {
            List<YTFormat> muxed = new ArrayList<>();
            for (YTFormat item : yt.extract(vid).formats) {
                if (item.isSabr() || TextUtils.isEmpty(item.url)) continue;
                if (item.hasVideo() && item.hasAudio()) muxed.add(item);
            }
            muxed.sort((a, b) -> Long.compare(b.bitrate, a.bitrate));
            for (YTFormat item : muxed) {
                YTHttp.Result response = fetch(item, item.url, bounded);
                if (response.code != 200 && response.code != 206) continue;
                String contentType = fallback(response.contentType, mimeBase(fallback(item.mimeType, "video/mp4")));
                Map<String, String> headers = mediaHeaders(contentType, response);
                int code = response.contentRange == null ? response.code : 206;
                return bytes(code, contentType, response.body, headers);
            }
            return text(502, "所有音视频合流候选均被 YouTube 拒绝");
        } catch (Throwable e) {
            return text(502, "YouTube 媒体代理失败: " + e);
        }
    }

    /* ------------------------------------------------------------------ */
    /* SABR candidates                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * SABR client polling order.
     *
     * <p>TVHTML5 is the only client used by this full-length SABR path.
     */
    private List<String> clientPriority() {
        List<String> out = new ArrayList<>();
        JsonElement configured = ext.get("sabr_clients");
        if (configured != null && configured.isJsonPrimitive()) {
            for (String part : configured.getAsString().split(",")) {
                String name = part.trim().toUpperCase(Locale.US);
                if (!name.isEmpty() && !out.contains(name)) out.add(name);
            }
        } else if (configured != null && configured.isJsonArray()) {
            for (JsonElement item : configured.getAsJsonArray()) {
                String name = item.getAsString().trim().toUpperCase(Locale.US);
                if (!name.isEmpty() && !out.contains(name)) out.add(name);
            }
        }
        if (out.isEmpty()) out.add("TVHTML5");
        return out;
    }

    private Set<Integer> skipItags() {
        Set<Integer> bad = new HashSet<>();
        bad.add(337);
        bad.add(401);
        JsonElement configured = ext.get("sabr_skip_itags");
        if (configured != null && configured.isJsonArray()) {
            for (JsonElement item : configured.getAsJsonArray()) {
                try {
                    bad.add(item.getAsInt());
                } catch (Throwable ignored) {
                    // Non-numeric entries are skipped.
                }
            }
        }
        return bad;
    }

    /** SABR video preference: SDR before HDR, then resolution, then VP9/H264 before AV1. */
    private List<YTFormat> qualityVideos(List<YTFormat> formats, String quality) {
        Set<Integer> bad = skipItags();
        List<YTFormat> videos = new ArrayList<>();
        for (YTFormat item : formats) {
            if (!item.isSabr() || !item.hasVideo() || item.hasAudio()) continue;
            if (bad.contains(item.itag)) continue;
            videos.add(item);
        }
        if ("8k".equals(quality) || "8k_hdr".equals(quality)) {
            videos = heights(videos, 4320, Integer.MAX_VALUE);
        } else if ("4k".equals(quality)) {
            videos = heights(videos, 2160, 4320);
        } else if ("2k".equals(quality)) {
            videos = heights(videos, 1440, 2160);
        } else if ("1080p".equals(quality)) {
            videos = heights(videos, 1000, 1440);
        } else {
            List<YTFormat> capped = heights(videos, 0, 2161);
            if (!capped.isEmpty()) videos = capped;
        }
        Comparator<YTFormat> order = (a, b) -> {
            int cmp = Integer.compare(yt.isHdrVideo(a) ? 0 : 1, yt.isHdrVideo(b) ? 0 : 1);
            if (cmp != 0) return -cmp;
            cmp = Integer.compare(b.height, a.height);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(yt.videoCodecPriority(b), yt.videoCodecPriority(a));
            if (cmp != 0) return cmp;
            return Long.compare(b.bitrate, a.bitrate);
        };
        videos.sort(order);
        return videos;
    }

    private static List<YTFormat> heights(List<YTFormat> items, int min, int maxExclusive) {
        List<YTFormat> out = new ArrayList<>();
        for (YTFormat item : items) if (item.height >= min && item.height < maxExclusive) out.add(item);
        return out;
    }

    private static String signature(YTFormat item, boolean video) {
        return item.itag + "|" + low(mimeBase(item.mimeType)) + "|" + low(item.codecs)
                + "|" + (video ? item.height : 0) + "|" + (video ? item.width : 0);
    }

    /**
     * Pairs each client's best SABR video with a compatible audio track.
     *
     * <p>Formats and SABR session data from different player responses are never mixed: the
     * ustreamer config and the streaming URL are session-bound. Fallback clients must also expose
     * an identical media signature, otherwise the already-published MPD would stop matching.
     */
    private List<Candidate> buildCandidates(List<YTFormat> sabrFormats, String quality) {
        List<YTFormat> videos = qualityVideos(sabrFormats, quality);
        List<Candidate> candidates = new ArrayList<>();
        String primaryVideoSig = null;
        String primaryAudioSig = null;
        for (String client : clientPriority()) {
            List<YTFormat> clientVideos = new ArrayList<>();
            for (YTFormat item : videos) {
                if (client.equalsIgnoreCase(item.client)) clientVideos.add(item);
            }
            List<YTFormat> clientAudios = new ArrayList<>();
            for (YTFormat item : sabrFormats) {
                if (!item.isSabr() || item.hasVideo() || !item.hasAudio()) continue;
                if (client.equalsIgnoreCase(item.client)) clientAudios.add(item);
            }
            if (clientVideos.isEmpty() || clientAudios.isEmpty()) continue;
            if (primaryVideoSig != null) {
                List<YTFormat> v = new ArrayList<>();
                for (YTFormat item : clientVideos) if (primaryVideoSig.equals(signature(item, true))) v.add(item);
                List<YTFormat> a = new ArrayList<>();
                for (YTFormat item : clientAudios) if (primaryAudioSig.equals(signature(item, false))) a.add(item);
                clientVideos = v;
                clientAudios = a;
                if (clientVideos.isEmpty() || clientAudios.isEmpty()) continue;
            }
            YTFormat video = clientVideos.get(0);
            YTFormat audio = yt.chooseAudio(clientAudios, "sabr", client);
            if (audio == null) continue;
            YTSabr.Config videoCfg = video.sabrConfig;
            if (videoCfg == null || TextUtils.isEmpty(videoCfg.serverAbrStreamingUrl)
                    || TextUtils.isEmpty(videoCfg.videoPlaybackUstreamerConfig)) continue;
            YTSabr.Config audioCfg = audio.sabrConfig;
            if (audioCfg == null || !videoCfg.serverAbrStreamingUrl.equals(audioCfg.serverAbrStreamingUrl)) {
                YTFormat sameSession = null;
                for (YTFormat item : clientAudios) {
                    if (item.sabrConfig != null
                            && videoCfg.serverAbrStreamingUrl.equals(item.sabrConfig.serverAbrStreamingUrl)) {
                        sameSession = item;
                        break;
                    }
                }
                if (sameSession == null) continue;
                audio = sameSession;
            }
            String videoSig = signature(video, true);
            String audioSig = signature(audio, false);
            if (primaryVideoSig == null) {
                primaryVideoSig = videoSig;
                primaryAudioSig = audioSig;
            } else if (!videoSig.equals(primaryVideoSig) || !audioSig.equals(primaryAudioSig)) {
                continue;
            }
            Candidate candidate = new Candidate();
            candidate.client = client;
            candidate.video = video;
            candidate.audio = audio;
            candidate.videoSig = videoSig;
            candidate.audioSig = audioSig;
            candidates.add(candidate);
        }
        return candidates;
    }

    private SabrData activate(String vid, SabrData data, int index, String cacheKey) {
        if (index < 0 || index >= data.candidates.size()) return null;
        Candidate selected = data.candidates.get(index);
        String generationKey = (cacheKey == null ? "yt_sabr_" + vid : cacheKey);
        // Reuse the current generation when re-activating the identical candidate. Bumping it
        // unconditionally discarded a live, already-negotiated session on every replay of the same
        // video and forced a cold restart. Only a real candidate change (client failover) needs a
        // new generation, because only then is the old UMP state genuinely incompatible.
        String signature = selected.client + ":" + selected.video.itag + ":" + selected.audio.itag;
        long generation;
        synchronized (sabrGenerations) {
            Long current = sabrGenerations.get(generationKey);
            String previous = sabrSignatures.get(generationKey);
            if (current != null && signature.equals(previous)) {
                generation = current;
            } else {
                generation = current == null ? 1L : current + 1L;
                sabrGenerations.put(generationKey, generation);
                sabrSignatures.put(generationKey, signature);
            }
        }
        String stateKey = vid + ":sabr:" + generation + ":" + signature;
        boolean reused;
        synchronized (YouTubeLite.sabrState) {
            // Keep a session already parked under this exact key: same video, same candidate, same
            // generation means the negotiated state is still valid and reusable.
            reused = YouTubeLite.sabrState.containsKey(stateKey)
                    && !YouTubeLite.sabrState.get(stateKey).isCanceled();
            if (!reused) {
                YTSabrSession stale = YouTubeLite.sabrState.remove(stateKey);
                YouTubeLite.sabrOwners.remove(stateKey);
                if (stale != null) stale.cancel();
                ownedStateKeys.remove(stateKey);
            }
        }
        if (reused) {
            com.github.catvod.crawler.SpiderDebug.log("YouTube SABR 复用会话: key=" + stateKey);
        }
        data.activeIndex = index;
        data.videoItem = selected.video;
        data.audioItem = selected.audio;
        data.stateKey = stateKey;
        sabrCache.put(cacheKey == null ? "yt_sabr_" + vid : cacheKey, data);
        return data;
    }

    private SabrData newSabrData(String vid, YouTubeLite.Extracted extracted, String quality, String cacheKey) {
        List<Candidate> candidates = buildCandidates(extracted.sabrFormats, quality);
        if (candidates.isEmpty()) {
            com.github.catvod.crawler.SpiderDebug.log("YouTube TVHTML5 SABR 候选为空: formats="
                    + extracted.sabrFormats.size() + ", quality=" + quality);
            return null;
        }
        SabrData data = new SabrData();
        data.candidates = candidates;
        data.duration = extracted.duration;
        data.expires = System.currentTimeMillis() + SABR_CACHE_MS;
        return activate(vid, data, 0, cacheKey);
    }

    /** Moves to the next compatible client after an init failure. */
    private SabrData switchClient(String vid, int failedIndex, String cacheKey) {
        synchronized (sabrSwitchLock) {
            String key = cacheKey == null ? "yt_sabr_" + vid : cacheKey;
            SabrData current = sabrCache.get(key);
            if (current == null) return null;
            // Parallel audio/video init may already have handled this exact failure.
            if (current.activeIndex != failedIndex) return current;
            int next = current.activeIndex + 1;
            if (next >= current.candidates.size()) return null;
            return activate(vid, current, next, key);
        }
    }

    /**
     * Returns the session for a state key, adopting one that survived a host registry clear.
     *
     * <p>When Android reclaims the backgrounded HomeActivity, the host destroys every Spider and
     * empties its registry, then rebuilds a Spider on the next request. Because the session map is
     * static, that rebuilt instance finds the still-running session here — same playback cookie, rn
     * sequence and cached segments — instead of renegotiating from zero against a manifest the
     * player is already using.
     */
    private YTSabrSession session(String stateKey) {
        synchronized (YouTubeLite.sabrState) {
            YTSabrSession found = YouTubeLite.sabrState.get(stateKey);
            // A cancelled session must never be adopted: its media cache is already released and its
            // pump refuses to run, so it would answer every request with an error.
            if (found != null && found.isCanceled()) {
                YouTubeLite.sabrState.remove(stateKey);
                YouTubeLite.sabrOwners.remove(stateKey);
                found = null;
            }
            if (found != null) {
                if (ownedStateKeys.add(stateKey)) {
                    com.github.catvod.crawler.SpiderDebug.log("YouTube SABR 会话接管: key=" + stateKey);
                }
                YouTubeLite.sabrOwners.put(stateKey, ownerId);
                return found;
            }
            YTSabrSession created = new YTSabrSession(yt.http(),
                    (int) YouTubeLite.optLong(ext, "sabr_max_parts", 4096),
                    YouTubeLite.optLong(ext, "sabr_video_cache_bytes", videoCacheBudget()),
                    YouTubeLite.optLong(ext, "sabr_audio_cache_bytes", audioCacheBudget()),
                    (int) YouTubeLite.optLong(ext, "sabr_segment_fetch_requests", 14));
            YouTubeLite.sabrState.put(stateKey, created);
            YouTubeLite.sabrOwners.put(stateKey, ownerId);
            ownedStateKeys.add(stateKey);
            reapIdleSessions();
            return created;
        }
    }

    /** Drops sessions whose producer already self-terminated, bounding the static map. */
    private static void reapIdleSessions() {
        if (YouTubeLite.sabrState.size() <= 6) return;
        for (String key : new ArrayList<>(YouTubeLite.sabrState.keySet())) {
            YTSabrSession value = YouTubeLite.sabrState.get(key);
            if (value == null || !value.isIdle()) continue;
            value.cancel();
            YouTubeLite.sabrState.remove(key);
            YouTubeLite.sabrOwners.remove(key);
            com.github.catvod.crawler.SpiderDebug.log("YouTube SABR 会话回收: key=" + key);
        }
    }

    /**
     * Cancels sessions belonging to any video other than {@code keepVid}.
     *
     * <p>Switching episodes used to leave the previous video's producer running: the reaper only
     * fires when the map exceeds a size threshold, and the old session is not idle while it is busy
     * retrying, so it kept issuing SABR requests for a video nobody is watching (observed: the old
     * itag-328 producer still pumping rn=9 after the next video had started). State keys are
     * {@code vid:...}, so the video is identifiable from the key alone.
     */
    private void cancelOtherVideos(String keepVid) {
        if (TextUtils.isEmpty(keepVid)) return;
        String prefix = keepVid + ":";
        synchronized (YouTubeLite.sabrState) {
            for (String key : new ArrayList<>(YouTubeLite.sabrState.keySet())) {
                if (key.startsWith(prefix)) continue;
                // Only touch sessions this instance owns; another live Spider may be serving
                // a different video legitimately (background preload, cast).
                if (!ownerId.equals(YouTubeLite.sabrOwners.get(key))) continue;
                YTSabrSession value = YouTubeLite.sabrState.remove(key);
                YouTubeLite.sabrOwners.remove(key);
                ownedStateKeys.remove(key);
                if (value == null) continue;
                value.cancel();
                com.github.catvod.crawler.SpiderDebug.log("YouTube SABR 切换视频停止旧会话: key=" + key);
            }
        }
    }

    /**
     * Video cache ceiling, derived from the real Java heap rather than a fixed number.
     *
     * <p>The previous 576 MiB default exceeded the whole heap on common devices (observed limit:
     * 512 MiB). A 2160p segment here is ~19.6 MiB, so a handful of them plus the host player's own
     * allocator pushed the process to 424 MiB used, at which point the host demoted its buffer to
     * {@code capacity-limited} (134 MiB → 24 MiB) and preloading stopped. Cap at a quarter of the
     * heap, clamped to a range that still holds enough segments for retries.
     */
    private static long videoCacheBudget() {
        long max = Runtime.getRuntime().maxMemory();
        long quarter = max <= 0 ? 96L * 1024 * 1024 : max / 4;
        return Math.max(64L * 1024 * 1024, Math.min(160L * 1024 * 1024, quarter));
    }

    private static long audioCacheBudget() {
        long max = Runtime.getRuntime().maxMemory();
        long share = max <= 0 ? 16L * 1024 * 1024 : max / 32;
        return Math.max(12L * 1024 * 1024, Math.min(32L * 1024 * 1024, share));
    }

    private SabrData sabrData(String vid, String quality, String cacheKey, boolean rebuild) {
        SabrData data = sabrCache.get(cacheKey);
        // An MPD URL can outlive its session-bound SABR cache. Never publish a manifest backed by
        // expired play data; rebuild it from a fresh player response instead.
        if (data != null && data.expires <= System.currentTimeMillis()) {
            sabrCache.remove(cacheKey);
            data = null;
        }
        if (data == null && rebuild) {
            Object lock;
            String extractLockKey = vid;
            synchronized (sabrExtractLocks) {
                lock = sabrExtractLocks.get(extractLockKey);
                if (lock == null) {
                    lock = new Object();
                    sabrExtractLocks.put(extractLockKey, lock);
                }
            }
            synchronized (lock) {
                data = sabrCache.get(cacheKey);
                if (data == null) {
                    try {
                        YouTubeLite.Extracted extracted = yt.extract(vid, true);
                        data = newSabrData(vid, extracted, quality, cacheKey);
                    } catch (Throwable e) {
                        com.github.catvod.crawler.SpiderDebug.log("YouTube SABR extraction failed: vid="
                                + vid + ", error=" + String.valueOf(e));
                    }
                }
            }
        }
        return data;
    }

    private void resetSabr(String vid, String cacheKey) {
        SabrData old = sabrCache.remove(cacheKey);
        if (old == null || old.stateKey == null) return;
        synchronized (YouTubeLite.sabrState) {
            YTSabrSession stale = YouTubeLite.sabrState.remove(old.stateKey);
            YouTubeLite.sabrOwners.remove(old.stateKey);
            if (stale != null) stale.cancel();
        }
        ownedStateKeys.remove(old.stateKey);
    }

    /* ------------------------------------------------------------------ */
    /* SABR manifests                                                     */
    /* ------------------------------------------------------------------ */

    private Object[] proxySabrMpd(Map<String, String> params) {
        String vid = params.get("vid");
        String quality = params.get("quality") == null ? "best" : params.get("quality");
        String sid = params.get("sid");
        String cacheKey = sabrCacheKey(vid, quality, sid, false);
        SabrData data = vid == null ? null : sabrData(vid, quality, cacheKey, true);
        if (data == null || data.videoItem == null || data.audioItem == null) return text(404, "SABR 音视频缓存不存在");
        YTFormat video = data.videoItem;
        YTFormat audio = data.audioItem;
        long duration = data.duration;
        String base = localUrl("&type=sabr&vid=" + enc(vid) + "&quality=" + enc(quality)
                + (TextUtils.isEmpty(sid) ? "" : "&sid=" + enc(sid)));
        long videoSegMs = (long) ((video.sabrConfig == null ? 6 : video.sabrConfig.targetDurationSec) * 1000);
        if (videoSegMs <= 0) videoSegMs = 6000;
        long audioSegMs = (long) ((audio.sabrConfig == null ? 10 : audio.sabrConfig.targetDurationSec) * 1000);
        if (audioSegMs < 8000) audioSegMs = 10000;
        List<YTFormat.Seg> videoTimeline = loadTimeline(video, duration * 1000);
        List<YTFormat.Seg> audioTimeline = loadTimeline(audio, duration * 1000);
        String videoRows = YTIndex.segmentTimelineXml(videoTimeline);
        String audioRows = YTIndex.segmentTimelineXml(audioTimeline);
        if (videoRows.isEmpty()) videoRows = evenRows(duration * 1000, videoSegMs);
        if (audioRows.isEmpty()) audioRows = evenRows(duration * 1000, audioSegMs);
        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT")
                .append(duration).append("S\" minBufferTime=\"PT10S\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
                .append("  <Period id=\"1\" start=\"PT0S\">\n")
                .append(templateSet(video, base, "video", videoRows, true))
                .append(templateSet(audio, base, "audio", audioRows, false))
                .append("  </Period>\n</MPD>");
        return bytes(200, "application/dash+xml", mpd.toString().getBytes(), null);
    }

    private String templateSet(YTFormat item, String base, String track, String rows, boolean video) {
        StringBuilder sb = new StringBuilder();
        sb.append("    <AdaptationSet id=\"").append(video ? 1 : 2).append("\" contentType=\"").append(track)
                .append("\" mimeType=\"").append(esc(mimeBase(fallback(item.mimeType, video ? "video/webm" : "audio/webm"))))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">\n")
                .append("      <Representation id=\"sabr-").append(video ? "v" : "a").append(item.itag)
                .append("\" bandwidth=\"").append(item.bitrate == 0 ? (video ? 1000000 : 128000) : item.bitrate)
                .append("\" codecs=\"").append(esc(item.codecs)).append("\"");
        if (video) sb.append(" width=\"").append(item.width).append("\" height=\"").append(item.height).append("\"");
        sb.append(">\n")
                .append("        <SegmentTemplate timescale=\"1000\" startNumber=\"1\" initialization=\"")
                .append(esc(base + "&track=" + track + "&seg=init")).append("\" media=\"")
                .append(esc(base + "&track=" + track + "&seg=")).append("$Number$\">")
                .append("<SegmentTimeline>").append(rows).append("</SegmentTimeline></SegmentTemplate>\n")
                .append("      </Representation>\n")
                .append("    </AdaptationSet>\n");
        return sb.toString();
    }

    private static String evenRows(long totalMs, long segMs) {
        long repeats = segMs <= 0 ? 0 : Math.max(0, totalMs / segMs - 1);
        return "<S t=\"0\" d=\"" + segMs + "\" r=\"" + repeats + "\"/>";
    }

    /**
     * SABR-B manifest backed by target-time requests, not sidx/Cues.
     *
     * <p>The timeline below is only a request grid. Every media URL carries both the requested
     * presentation time and local segment number. {@link YTSabrSession} then pumps SABR, parses
     * the real MEDIA_HEADER boundaries, maps the local number to a native sequence, and serves
     * the cached complete native segment. This keeps B usable for SABR formats that have no
     * direct URL from which an sidx/Cues index could be read.
     *
     * Final startup/rebuffer stabilization pass: B retries target-time gaps instead of restarting.
     */
    private Object[] proxySabrMpd2(Map<String, String> params) {
        String vid = params.get("vid");
        String quality = params.get("quality") == null ? "best" : params.get("quality");
        String sid = params.get("sid");
        String cacheKey = sabrCacheKey(vid, quality, sid, true);
        SabrData data = vid == null ? null : sabrData(vid, quality, cacheKey, true);
        if (data == null || data.videoItem == null || data.audioItem == null) return text(404, "SABR 音视频缓存不存在");
        long durationMs = Math.max(1000L, data.duration * 1000L);
        String stateKey = data.stateKey == null ? vid + ":sabr:b" : data.stateKey;
        YTSabrSession bSession = session(stateKey);
        bSession.touch();
        bSession.startProducer(data.videoItem, data.audioItem, durationMs);
        // The producer owns SABR I/O. Snapshot only what is already indexed; later media requests
        // continue using the producer's real MEDIA_HEADER time/native-sequence index.
        // Do not fetch a direct URL/index on the MPD request thread. B's only source of truth is
        // the SABR producer's MEDIA_HEADER index; the producer will extend it while playback runs.
        List<YTFormat.Seg> videoReal = bSession.snapshotTimeline(data.videoItem.itag);
        List<YTFormat.Seg> audioReal = bSession.snapshotTimeline(data.audioItem.itag);
        String videoRows = timeRows(videoReal, durationMs, sabrRequestGridMs(data.videoItem, true));
        String audioRows = timeRows(audioReal, durationMs, sabrRequestGridMs(data.audioItem, false));
        com.github.catvod.crawler.SpiderDebug.log("YouTube SABR-B MPD: vid=" + vid
                + ", videoReal=" + videoReal.size() + ", audioReal=" + audioReal.size()
                + ", videoGridMs=" + sabrRequestGridMs(data.videoItem, true)
                + ", audioGridMs=" + sabrRequestGridMs(data.audioItem, false));
        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT")
                .append(data.duration).append("S\" minBufferTime=\"PT30S\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
                .append("  <Period id=\"1\" start=\"PT0S\">\n")
                .append(timeTemplateSet(vid, quality, sid, data.videoItem, "video", true, videoRows))
                .append(timeTemplateSet(vid, quality, sid, data.audioItem, "audio", false, audioRows))
                .append("  </Period>\n</MPD>");
        return bytes(200, "application/dash+xml", mpd.toString().getBytes(), null);
    }

    private String timeTemplateSet(String vid, String quality, String sid, YTFormat item, String track,
                                   boolean video, String rows) {
        String sidParam = TextUtils.isEmpty(sid) ? "" : "&sid=" + enc(sid);
        String init = localUrl("&type=sabr_time&vid=" + enc(vid) + "&quality=" + enc(quality)
                + sidParam + "&track=" + track + "&seg=init");
        String media = localUrl("&type=sabr_time&vid=" + enc(vid) + "&quality=" + enc(quality)
                + sidParam + "&track=" + track + "&seg=t=$Time$");
        StringBuilder sb = new StringBuilder();
        sb.append("    <AdaptationSet id=\"").append(video ? 1 : 2).append("\" contentType=\"")
                .append(track).append("\" mimeType=\"")
                .append(esc(mimeBase(fallback(item.mimeType, video ? "video/webm" : "audio/webm"))))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">\n")
                .append("      <Representation id=\"sabr-b-").append(video ? "v" : "a").append(item.itag)
                .append("\" bandwidth=\"").append(item.bitrate == 0 ? (video ? 1000000 : 128000) : item.bitrate)
                .append("\" codecs=\"").append(esc(item.codecs)).append("\"");
        if (video) sb.append(" width=\"").append(item.width).append("\" height=\"").append(item.height).append("\"");
        sb.append(">\n")
                .append("        <SegmentTemplate timescale=\"1000\" startNumber=\"1\" initialization=\"")
                .append(esc(init)).append("\" media=\"").append(esc(media)).append("\">\n")
                .append("          <SegmentTimeline>").append(rows)
                .append("</SegmentTimeline>\n")
                .append("        </SegmentTemplate>\n      </Representation>\n")
                .append("    </AdaptationSet>\n");
        return sb.toString();
    }

    private List<YTFormat.Seg> warmSabrTimeline(SabrData data, String stateKey, String track) {
        List<YTFormat.Seg> empty = new ArrayList<>();
        if (data == null || data.videoItem == null || data.audioItem == null) return empty;
        try {
            YTSabrSession sabr = session(stateKey);
            // One init request establishes the UMP/SABR session and normally also returns the
            // first MEDIA_HEADERs. A target-time request then pumps until the first real media
            // interval is cached. Later MPD requests reuse this same session and cache.
            sabr.getSegment(data.videoItem, data.audioItem, track, "init");
            sabr.getSegment(data.videoItem, data.audioItem, track, "t=0");
            int itag = "video".equals(track) ? data.videoItem.itag : data.audioItem.itag;
            return sabr.snapshotTimeline(itag);
        } catch (Throwable e) {
            com.github.catvod.crawler.SpiderDebug.log("YouTube SABR-B 时间轴预热失败: track="
                    + track + ", error=" + String.valueOf(e));
            return empty;
        }
    }

    private static String timeRows(List<YTFormat.Seg> real, long durationMs, long fallbackMs) {
        StringBuilder rows = new StringBuilder();
        long cursor = 0;
        if (real != null) {
            for (YTFormat.Seg seg : real) {
                if (seg == null || seg.d <= 0 || seg.t < cursor || seg.t >= durationMs) continue;
                rows.append("<S t=\"").append(seg.t).append("\" d=\"")
                        .append(Math.min(seg.d, durationMs - seg.t)).append("\"/>");
                cursor = Math.min(durationMs, seg.t + seg.d);
            }
        }
        long step = Math.max(1000L, fallbackMs);
        while (cursor < durationMs) {
            long d = Math.min(step, durationMs - cursor);
            rows.append("<S t=\"").append(cursor).append("\" d=\"")
                    .append(Math.max(1L, d)).append("\"/>");
            cursor += d;
        }
        return rows.toString();
    }

    private long sabrRequestGridMs(YTFormat item, boolean video) {
        double target = item != null && item.sabrConfig != null ? item.sabrConfig.targetDurationSec : 0;
        long ms = target > 0 ? (long) (target * 1000.0) : (video ? 6000L : 10000L);
        if (video && ms < 3000L) ms = 6000L;
        if (!video && ms < 8000L) ms = 10000L;
        return Math.max(1000L, ms);
    }

    private static String virtualTimeRows(long durationMs, long segmentMs) {
        StringBuilder rows = new StringBuilder();
        long t = 0;
        long step = Math.max(1000L, segmentMs);
        while (t < durationMs) {
            long d = Math.min(step, durationMs - t);
            rows.append("<S t=\"").append(t).append("\" d=\"").append(Math.max(1L, d)).append("\"/>");
            t += d;
        }
        return rows.toString();
    }

    private String baseSet(String vid, YTFormat item, String track, boolean video) {
        long[] initRange = item.indexSource != null && item.indexSource.initRange != null
                ? item.indexSource.initRange : item.initRange;
        long[] indexRange = item.indexSource != null && item.indexSource.indexRange != null
                ? item.indexSource.indexRange : item.indexRange;
        String base = localUrl("&type=sabr_range&vid=" + enc(vid) + "&track=" + track);
        StringBuilder sb = new StringBuilder();
        sb.append("    <AdaptationSet id=\"").append(video ? 1 : 2).append("\" contentType=\"").append(track)
                .append("\" mimeType=\"").append(esc(mimeBase(fallback(item.mimeType, video ? "video/webm" : "audio/mp4"))))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">\n")
                .append("      <Representation id=\"sabr-").append(video ? "v" : "a").append(item.itag)
                .append("\" bandwidth=\"").append(item.bitrate == 0 ? (video ? 1000000 : 128000) : item.bitrate)
                .append("\" codecs=\"").append(esc(item.codecs)).append("\"");
        if (video) sb.append(" width=\"").append(item.width).append("\" height=\"").append(item.height).append("\"");
        sb.append(">\n")
                .append("        <BaseURL>").append(esc(base)).append("</BaseURL>\n")
                .append("        <SegmentBase indexRange=\"").append(rangeText(indexRange))
                .append("\" indexRangeExact=\"true\">\n")
                .append("          <Initialization range=\"").append(rangeText(initRange)).append("\"/>\n")
                .append("        </SegmentBase>\n")
                .append("      </Representation>\n")
                .append("    </AdaptationSet>\n");
        return sb.toString();
    }

    /** Fetches and caches a format's real segment index, so the manifest carries exact t/d values. */
    private List<YTFormat.Seg> loadTimeline(YTFormat item, long totalMs) {
        if (item.timeline != null && !item.timeline.isEmpty()) return item.timeline;
        YTFormat.IndexSource source = item.indexSource;
        if (source == null || TextUtils.isEmpty(source.url) || source.indexRange == null) return new ArrayList<>();
        long start = source.indexRange[0];
        long end = source.indexRange[1];
        if (end < start) return new ArrayList<>();
        Map<String, String> headers = new HashMap<>(header);
        headers.putAll(source.headers);
        YTHttp.Result response = yt.http().get(source.url, headers, "bytes=" + start + "-" + end);
        if (response.code != 200 && response.code != 206) return new ArrayList<>();
        List<YTFormat.Seg> timeline = low(item.mimeType).contains("webm")
                ? YTIndex.parseWebmCues(response.body, totalMs)
                : YTIndex.parseMp4Sidx(response.body, totalMs);
        if (timeline != null && !timeline.isEmpty()) item.timeline = timeline;
        return timeline == null ? new ArrayList<>() : timeline;
    }

    /* ------------------------------------------------------------------ */
    /* SABR segments                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Serves B's target-time media requests. The DASH grid is only a hint; the SABR session
     * returns the complete cached native segment whose real MEDIA_HEADER range covers targetMs.
     */
    private Object[] proxySabrTime(Map<String, String> params) {
        String vid = params.get("vid");
        String quality = params.get("quality") == null ? "best" : params.get("quality");
        String sid = params.get("sid");
        String cacheKey = sabrCacheKey(vid, quality, sid, true);
        String track = params.get("track") == null ? "video" : params.get("track");
        String segment = params.get("seg") == null ? "init" : params.get("seg");
        if (!"video".equals(track) && !"audio".equals(track)) return text(400, "无效 SABR 轨道");

        SabrData data = vid == null ? null : sabrCache.get(cacheKey);
        if (data == null && vid != null) data = sabrData(vid, quality, cacheKey, true);
        if (data == null || data.videoItem == null || data.audioItem == null) {
            return text(404, "SABR-B 缓存不存在");
        }
        YTFormat item = "video".equals(track) ? data.videoItem : data.audioItem;
        // Keep one SABR protocol session for both tracks. The playback cookie, rn sequence and
        // buffered context are shared by the video and audio itags; the session lock is fair so
        // a large 4K video pump still yields between requests.
        String stateKey = data.stateKey == null ? vid + ":sabr:b" : data.stateKey;
        String requested = "init".equals(segment) ? "init" : null;
        if (requested == null) {
            if (!segment.startsWith("t=")) return text(400, "无效 SABR-B 时间段");
            try {
                long targetMs = Math.max(0L, Long.parseLong(segment.substring(2)));
                // B is intentionally time-only. The DASH number belongs to the virtual request
                // grid, not to the SABR native sequence; feeding it back forces a false one-to-one
                // mapping and produces video-only 503s when real segment duration changes.
                requested = "t=" + targetMs;
            } catch (Throwable e) {
                return text(400, "无效 SABR-B 时间段");
            }
        }
        // Two attempts: the second one runs against a freshly extracted player response, which is
        // the only way to recover when the server sends RELOAD_PLAYER_RESPONSE (UMP part 46).
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                YTSabrSession active = session(stateKey);
                // A segment request can arrive against a session that was just replaced (the previous
                // one cancelled on a video switch, or reaped). A fresh session has no producer until
                // the next manifest fetch, so start it here instead of waiting for a timeout.
                active.startProducer(data.videoItem, data.audioItem,
                        Math.max(1000L, data.duration * 1000L));
                // A cold session must negotiate before it can answer, and one SABR pump at 2160p was
                // measured at 6-18s of pure download. A flat 12s deadline therefore expired mid-fetch
                // and returned 503 even though the transfer was healthy, which the player reports as
                // a connection timeout. Give a cold session more room, and keep the short deadline
                // once it is warm so a genuine stall is still caught quickly.
                long deadline = active.hasMedia() ? 12000L : 25000L;
                YTSabrSession.Found found = active
                        .awaitProducedSegment(data.videoItem, data.audioItem, track, requested, deadline);
                if (found == null || found.media == null || found.media.length == 0) {
                    return text(503, "SABR-B 未产生目标时间媒体");
                }
                String contentType = mimeBase(fallback(item.mimeType,
                        "video".equals(track) ? "video/webm" : "audio/webm"));
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", contentType);
                headers.put("Content-Length", String.valueOf(found.media.length));
                headers.put("Cache-Control", "private, max-age=30");
                headers.put("Accept-Ranges", "none");
                return bytes(200, contentType, found.media, headers);
            } catch (YTSabrSession.ReloadRequired reload) {
                com.github.catvod.crawler.SpiderDebug.log("YouTube SABR-B 需重新提取: track=" + track
                        + ", attempt=" + attempt + ", reason=" + reload.getMessage());
                if (attempt > 0) return text(503, "SABR-B 需重新提取但重试已用尽");
                SabrData rebuilt = reextract(vid, quality, cacheKey);
                if (rebuilt == null) return text(503, "SABR-B 重新提取失败");
                data = rebuilt;
                item = "video".equals(track) ? data.videoItem : data.audioItem;
                stateKey = data.stateKey == null ? vid + ":sabr:b" : data.stateKey;
            } catch (Throwable e) {
                com.github.catvod.crawler.SpiderDebug.log("YouTube SABR-B 取段失败: track=" + track
                        + ", segment=" + segment + ", status=" + session(stateKey).lastStatus()
                        + ", error=" + String.valueOf(e));
                return text(503, "SABR-B 取段失败: " + String.valueOf(e));
            }
        }
        return text(503, "SABR-B 取段失败");
    }

    /**
     * Discards the cached player response for a video and extracts a fresh one.
     *
     * <p>Required when the server answers with {@code RELOAD_PLAYER_RESPONSE}: the streaming URL and
     * ustreamer config are bound to a player response that has expired, so no amount of retrying the
     * same payload will ever return media. Also clears the extraction cache, otherwise the rebuild
     * would hand back the identical stale config.
     */
    private SabrData reextract(String vid, String quality, String cacheKey) {
        resetSabr(vid, cacheKey);
        try {
            yt.invalidateExtract(vid);
        } catch (Throwable ignored) {
            // Best effort: a forced extract below still refreshes most of the state.
        }
        synchronized (refreshMarks) {
            refreshMarks.remove(cacheKey);
        }
        return sabrData(vid, quality, cacheKey, true);
    }

    /**
     * Legacy SegmentBase byte-range endpoint retained for compatibility with old cached MPDs.
     * New B manifests use {@link #proxySabrTime(Map)} and never depend on sidx/Cues.
     */
    private Object[] proxySabrRange(Map<String, String> params) {
        return text(410, "旧 SABR-B 字节范围桥已停用");
    }

    private Object[] rangeFromDirect(YTFormat item, Long start, Long end, long indexEnd) {
        YTFormat.IndexSource source = item.indexSource;
        String url = source != null && !TextUtils.isEmpty(source.url) ? source.url : item.url;
        if (TextUtils.isEmpty(url)) return text(404, "缺少索引直链");
        Map<String, String> headers = new HashMap<>(header);
        headers.putAll(source != null ? source.headers : item.headers);
        long begin = start == null ? 0 : start;
        long upper = end == null ? indexEnd : Math.min(end, indexEnd);
        YTHttp.Result response = yt.http().get(url, headers, "bytes=" + begin + "-" + upper);
        if (response.code != 200 && response.code != 206) return text(response.code, "索引取回失败");
        String contentType = mimeBase(fallback(item.mimeType, "application/octet-stream"));
        Map<String, String> out = new LinkedHashMap<>();
        out.put("Content-Type", contentType);
        out.put("Content-Length", String.valueOf(response.body.length));
        out.put("Accept-Ranges", "bytes");
        out.put("Cache-Control", "private, max-age=300");
        if (response.contentRange != null) out.put("Content-Range", response.contentRange);
        return bytes(response.code == 206 ? 206 : 200, contentType, response.body, out);
    }

    /**
     * Serves one {@code $Number$} segment from the SABR session.
     *
     * <p>A 200 response carrying only control parts means the candidate is dead, exactly like an
     * HTTP failure. For init requests only, switch to the next compatible client rather than
     * returning 500 and letting the parallel audio/video init requests start a request storm.
     */
    private Object[] proxySabr(Map<String, String> params) {
        String vid = params.get("vid");
        String quality = params.get("quality") == null ? "best" : params.get("quality");
        String sid = params.get("sid");
        String cacheKey = sabrCacheKey(vid, quality, sid, false);
        String track = params.get("track") == null ? "video" : params.get("track");
        String segment = params.get("seg") == null ? "init" : params.get("seg");
        SabrData data = vid == null ? null : sabrCache.get(cacheKey);
        if (data == null) return text(404, "SABR 缓存不存在");
        if (!"video".equals(track) && !"audio".equals(track)) return text(400, "无效 SABR 轨道");
        boolean init = "init".equals(segment);
        int attempts = 1 + (init ? data.candidates.size() : 0);
        String lastError = null;
        boolean rebuilt = false;
        for (int attempt = 0; attempt < attempts; attempt++) {
            SabrData current = sabrCache.get(cacheKey);
            if (current != null) data = current;
            int requestIndex = data.activeIndex;
            String stateKey = data.stateKey == null ? vid + ":sabr" : data.stateKey;
            YTFormat item = "video".equals(track) ? data.videoItem : data.audioItem;
            try {
                YTSabrSession.Found found = session(stateKey)
                        .getSegment(data.videoItem, data.audioItem, track, segment);
                if (found == null || found.media == null) {
                    lastError = found == null ? "empty response" : found.error;
                    if (init && switchClient(vid, requestIndex, cacheKey) != null) continue;
                    return text(500, "SABR 分段不可用: " + lastError);
                }
                SabrData latest = sabrCache.get(cacheKey);
                if (init && latest != null && latest.activeIndex != requestIndex) continue;
                String contentType = mimeBase(fallback(item == null ? null : item.mimeType,
                        "video".equals(track) ? "video/webm" : "audio/webm"));
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", contentType);
                headers.put("Content-Length", String.valueOf(found.media.length));
                headers.put("Cache-Control", "private, max-age=30");
                headers.put("Accept-Ranges", "none");
                return bytes(200, contentType, found.media, headers);
            } catch (Throwable e) {
                lastError = String.valueOf(e);
                com.github.catvod.crawler.SpiderDebug.log("YouTube SABR 取段失败: track=" + track
                        + ", segment=" + segment + ", status=" + session(stateKey).lastStatus()
                        + ", error=" + lastError);
                boolean canFailover = init && lastError.contains("SABR HTTP 4");
                // A stale MPD/session pair is common when the host advances to the next episode
                // or seeks while parallel init requests are still in flight. Rebuild once for A
                // before returning an HTTP 500 that makes the host leave the detail page.
                if (init && !rebuilt && (lastError.contains("IllegalArgumentException")
                        || lastError.contains("empty") || lastError.contains("SABR"))) {
                    rebuilt = true;
                    resetSabr(vid, cacheKey);
                    SabrData fresh = sabrData(vid, quality, cacheKey, true);
                    if (fresh != null) {
                        data = fresh;
                        attempts = Math.max(attempts, 1 + fresh.candidates.size());
                        continue;
                    }
                }
                if (!canFailover || switchClient(vid, requestIndex, cacheKey) == null) break;
            }
        }
        return text(500, "SABR 代理失败: " + lastError);
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    private YTHttp.Result fetch(YTFormat item, String url, String range) {
        Map<String, String> headers = new HashMap<>(header);
        if (item != null) headers.putAll(item.headers);
        return yt.http().get(url, headers, range);
    }

    private static Map<String, String> mediaHeaders(String contentType, YTHttp.Result response) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "no-cache");
        if (response.contentRange != null) headers.put("Content-Range", response.contentRange);
        if (response.contentLength != null) headers.put("Content-Length", response.contentLength);
        else if (response.body != null) headers.put("Content-Length", String.valueOf(response.body.length));
        return headers;
    }

    private static String range(Map<String, String> params) {
        String value = params.get("range");
        return value == null ? params.get("Range") : value;
    }

    /** Parses {@code bytes=start-end}; a missing bound is reported as -1. */
    private static long[] parseRange(String value) {
        if (value == null || value.isEmpty()) return null;
        String text = value.trim().toLowerCase(Locale.US);
        if (text.startsWith("bytes=")) text = text.substring(6);
        int comma = text.indexOf(',');
        if (comma >= 0) text = text.substring(0, comma);
        text = text.trim();
        int dash = text.indexOf('-');
        if (dash < 0) return null;
        long start = -1;
        long end = -1;
        String rawStart = text.substring(0, dash).trim();
        String rawEnd = text.substring(dash + 1).trim();
        try {
            if (!rawStart.isEmpty()) start = Long.parseLong(rawStart);
        } catch (Throwable ignored) {
            start = -1;
        }
        try {
            if (!rawEnd.isEmpty()) end = Long.parseLong(rawEnd);
        } catch (Throwable ignored) {
            end = -1;
        }
        return new long[]{start, end};
    }

    private static String rangeText(long[] range) {
        if (range == null) return "0-0";
        return range[0] + "-" + range[1];
    }

    private static String fallback(String value, String other) {
        return TextUtils.isEmpty(value) ? other : value;
    }

    private static String enc(String value) {
        return value == null ? "" : android.net.Uri.encode(value);
    }

    private static String esc(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&" + "quot;").replace("'", "&#39;");
    }

    /**
     * Rebuilds the manifest query for the JAR-owned server, tagging it so the redirect happens
     * once. Only the parameters the manifest routes actually read are forwarded.
     */
    private static String manifestQuery(Map<String, String> params, String type) {
        String vid = params.get("vid");
        if (TextUtils.isEmpty(vid)) return null;
        StringBuilder sb = new StringBuilder();
        sb.append("&type=").append(type).append("&vid=").append(enc(vid));
        String quality = params.get("quality");
        sb.append("&quality=").append(enc(TextUtils.isEmpty(quality) ? "best" : quality));
        String sid = params.get("sid");
        if (!TextUtils.isEmpty(sid)) sb.append("&sid=").append(enc(sid));
        sb.append("&ytr=1");
        return sb.toString();
    }

    private static Object[] redirect(String location) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Location", location);
        headers.put("Content-Length", "0");
        headers.put("Cache-Control", "no-store");
        return new Object[]{302, "text/plain; charset=utf-8", new ByteArrayInputStream(new byte[0]), headers};
    }

    private static Object[] text(int code, String message) {
        return bytes(code, "text/plain; charset=utf-8", message.getBytes(), null);
    }

    private static Object[] bytes(int code, String contentType, byte[] body, Map<String, String> headers) {
        byte[] payload = body == null ? new byte[0] : body;
        if (headers == null) {
            return new Object[]{code, contentType, new ByteArrayInputStream(payload)};
        }
        return new Object[]{code, contentType, new ByteArrayInputStream(payload), headers};
    }
}