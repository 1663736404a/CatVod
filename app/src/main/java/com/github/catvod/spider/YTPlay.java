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
 *   <li><b>SABR</b> ({@code sabr_mpd}/{@code sabr_mpd2}/{@code sabr}/{@code sabr_range}) — formats
 *       with no per-format URL, negotiated through {@link YTSabrSession}. {@code sabr_mpd} uses
 *       {@code SegmentTemplate/$Number$}; {@code sabr_mpd2} uses {@code SegmentBase} byte ranges,
 *       which removes the number-to-native-sequence mapping error entirely.</li>
 * </ul>
 */
final class YTPlay {

    private static final long PLAY_CACHE_MS = 21600 * 1000L;
    private static final long SABR_CACHE_MS = 1800 * 1000L;
    private static final String[] RISKY_AUDIO = {"ec-3", "ec3", "eac3", "ac-3", "ac3", "dts", "truehd"};

    private final YouTubeLite yt;
    private final Map<String, String> header;
    private final JsonObject ext;
    private final String siteKey;

    private final Map<String, PlayData> playCache = new HashMap<>();
    private final Map<String, SabrData> sabrCache = new HashMap<>();
    private final Map<String, Long> refreshMarks = new HashMap<>();
    private final Object sabrSwitchLock = new Object();

    YTPlay(YouTubeLite yt, Map<String, String> header, JsonObject ext, String siteKey) {
        this.yt = yt;
        this.header = header;
        this.ext = ext;
        this.siteKey = siteKey;
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
        /** Highest-ranked representation used as the initial video track. */
        YTFormat video;
        /** Same-session representations Exo may switch between by bandwidth. */
        List<YTFormat> videos = new ArrayList<>();
        YTFormat audio;
        String videoSig;
        String audioSig;
    }

    /** Cached SABR session state for one video. */
    private static class SabrData {
        List<Candidate> candidates = new ArrayList<>();
        int activeIndex;
        YTFormat videoItem;
        List<YTFormat> videoItems = new ArrayList<>();
        YTFormat audioItem;
        String stateKey;
        long duration;
        long expires;
    }

    /* ------------------------------------------------------------------ */
    /* proxy entry point                                                  */
    /* ------------------------------------------------------------------ */

    Object[] proxy(Map<String, String> params) {
        String type = params.get("type");
        if (type == null) return null;
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
            // SegmentBase bridge, mirroring youtubei.js's is_sabr manifest: a SABR VOD manifest
            // uses SegmentBase + indexRange rather than SegmentTemplate/$Number$. The older
            // sabr_mpd route stays available for comparison and fallback.
            case "sabr_mpd2":
                return proxySabrMpd2(params);
            case "sabr_range":
                return proxySabrRange(params);
            case "sabr":
                return proxySabr(params);
            default:
                return null;
        }
    }

    /** Absolute local-proxy URL, usable inside a manifest. */
    String localUrl(String params) {
        return Proxy.getUrl() + "?do=csp&siteKey=" + siteKey + params;
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

    /** Groups codec strings such as vp09.00.40.08 and vp09.00.50.08 for DASH adaptation. */
    private static String codecFamily(YTFormat item) {
        String text = low(item == null ? null : item.codecs);
        if (text.contains("vp09") || text.contains("vp9")) return "vp9";
        if (text.contains("av01") || text.contains("av1")) return "av1";
        if (text.contains("avc") || text.contains("h264")) return "avc";
        return text;
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
        // The extractor only accepts TVHTML5 for full-length SABR.  Older site JSON
        // may still say ANDROID; keep it as a harmless fallback entry but always append
        // TVHTML5 so a stale configuration cannot make every candidate disappear.
        if (!out.contains("TVHTML5")) out.add("TVHTML5");
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
            // Keep 2160p as the initial choice, but expose 1080p/1440p in the same
            // adaptation set so Exo can step down when the link cannot sustain 4K.
            videos = heights(videos, 1080, 2161);
        } else if ("2k".equals(quality)) {
            videos = heights(videos, 1080, 2160);
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
            // For UHD, a 30fps track is a much better Exo starting point than a
            // 60fps track with nearly twice the bitrate.  Lower bitrate is also
            // preferred within the same UHD/fps class; adaptive playback can still
            // move to a higher representation when bandwidth permits.
            if (a.height >= 2160 && b.height >= 2160) {
                int aFps = a.fps > 30 ? 1 : 0;
                int bFps = b.fps > 30 ? 1 : 0;
                cmp = Integer.compare(aFps, bFps);
                if (cmp != 0) return cmp;
                // Keep VP9/H.264 ahead of AV1 on Android even when AV1 happens
                // to advertise a smaller bitrate.
                cmp = Integer.compare(yt.videoCodecPriority(b), yt.videoCodecPriority(a));
                if (cmp != 0) return cmp;
                cmp = Long.compare(a.bitrate, b.bitrate);
                if (cmp != 0) return cmp;
            } else {
                cmp = Integer.compare(yt.videoCodecPriority(b), yt.videoCodecPriority(a));
                if (cmp != 0) return cmp;
                return Long.compare(b.bitrate, a.bitrate);
            }
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

            // Keep every representation from this exact TVHTML5/SABR session that has
            // the same codec family and SDR/HDR class.  A single 4K representation
            // makes Exo lock onto (for example) 28 Mbps VP9/60 and leaves it no way
            // to recover on a 2-3 Mbps link.  These entries become one DASH adaptation
            // set; the requested itag is carried back to the SABR session per segment.
            List<YTFormat> adaptive = new ArrayList<>();
            String mime = low(mimeBase(video.mimeType));
            String codecs = codecFamily(video);
            boolean hdr = yt.isHdrVideo(video);
            Set<Integer> seenItags = new HashSet<>();
            for (YTFormat item : clientVideos) {
                YTSabr.Config itemCfg = item.sabrConfig;
                if (itemCfg == null || !videoCfg.serverAbrStreamingUrl.equals(itemCfg.serverAbrStreamingUrl)) continue;
                if (!mime.equals(low(mimeBase(item.mimeType))) || !codecs.equals(codecFamily(item))) continue;
                if (yt.isHdrVideo(item) != hdr || seenItags.contains(item.itag)) continue;
                seenItags.add(item.itag);
                adaptive.add(item);
            }
            if (adaptive.isEmpty()) adaptive.add(video);
            adaptive.sort((a, b) -> {
                int cmp = Integer.compare(a.height, b.height);
                if (cmp != 0) return cmp;
                return Long.compare(a.bitrate, b.bitrate);
            });
            Candidate candidate = new Candidate();
            candidate.client = client;
            candidate.video = video;
            candidate.videos = adaptive;
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
        String stateKey = vid + ":sabr:" + selected.client + ":" + selected.video.itag + ":" + selected.audio.itag;
        synchronized (yt.sabrState) {
            yt.sabrState.remove(stateKey);
        }
        data.activeIndex = index;
        data.videoItem = selected.video;
        data.videoItems = selected.videos == null || selected.videos.isEmpty()
                ? new ArrayList<>(Collections.singletonList(selected.video))
                : new ArrayList<>(selected.videos);
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

    private YTSabrSession session(String stateKey) {
        synchronized (yt.sabrState) {
            YTSabrSession found = yt.sabrState.get(stateKey);
            if (found != null) return found;
            YTSabrSession created = new YTSabrSession(yt.http(),
                    (int) YouTubeLite.optLong(ext, "sabr_max_parts", 4096),
                    YouTubeLite.optLong(ext, "sabr_video_cache_bytes", 512L * 1024 * 1024),
                    YouTubeLite.optLong(ext, "sabr_audio_cache_bytes", 32L * 1024 * 1024),
                    (int) YouTubeLite.optLong(ext, "sabr_segment_fetch_requests", 10));
            yt.sabrState.put(stateKey, created);
            return created;
        }
    }

    private SabrData sabrData(String vid, String quality, String cacheKey, boolean rebuild) {
        SabrData data = sabrCache.get(cacheKey);
        // An MPD URL can outlive its session-bound SABR cache. Never publish a manifest backed by
        // expired play data; rebuild it from a fresh player response instead.
        if (data != null && data.expires <= System.currentTimeMillis()) data = null;
        if (data == null && rebuild) {
            try {
                YouTubeLite.Extracted extracted = yt.extract(vid, true);
                data = newSabrData(vid, extracted, quality, cacheKey);
            } catch (Throwable ignored) {
                // Fall through to the caller's not-found response.
            }
        }
        return data;
    }

    /* ------------------------------------------------------------------ */
    /* SABR manifests                                                     */
    /* ------------------------------------------------------------------ */

    private Object[] proxySabrMpd(Map<String, String> params) {
        String vid = params.get("vid");
        String quality = params.get("quality") == null ? "best" : params.get("quality");
        String cacheKey = "best".equals(quality) ? "yt_sabr_" + vid : "yt_sabr_" + vid + "_" + quality;
        SabrData data = vid == null ? null : sabrData(vid, quality, cacheKey, true);
        if (data == null || data.videoItem == null || data.audioItem == null) return text(404, "SABR 音视频缓存不存在");
        YTFormat video = data.videoItem;
        YTFormat audio = data.audioItem;
        long duration = data.duration;
        String base = localUrl("&type=sabr&vid=" + enc(vid) + "&quality=" + enc(quality));
        List<YTFormat> videos = data.videoItems == null || data.videoItems.isEmpty()
                ? new ArrayList<>(Collections.singletonList(video)) : data.videoItems;
        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT")
                .append(duration).append("S\" minBufferTime=\"PT10S\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
                .append("  <Period id=\"1\" start=\"PT0S\">\n")
                .append(templateVideoSet(videos, base, duration * 1000))
                .append(templateSet(audio, base, "audio", duration * 1000, false))
                .append("  </Period>\n</MPD>");
        return bytes(200, "application/dash+xml", mpd.toString().getBytes(), null);
    }

    /** One adaptive video set; each representation stays in the same SABR session. */
    private String templateVideoSet(List<YTFormat> items, String base, long totalMs) {
        if (items == null || items.isEmpty()) return "";
        YTFormat first = items.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append("    <AdaptationSet id=\"1\" contentType=\"video\" mimeType=\"")
                .append(esc(mimeBase(fallback(first.mimeType, "video/webm"))))
                .append("\" segmentAlignment=\"true\" startWithSAP=\"1\">\n");
        for (YTFormat item : items) {
            long segMs = (long) ((item.sabrConfig == null ? 6 : item.sabrConfig.targetDurationSec) * 1000);
            if (segMs <= 0) segMs = 6000;
            List<YTFormat.Seg> timeline = loadTimeline(item, totalMs);
            String rows = YTIndex.segmentTimelineXml(timeline);
            if (rows.isEmpty()) rows = evenRows(totalMs, segMs);
            sb.append("      <Representation id=\"sabr-v").append(item.itag)
                    .append("\" bandwidth=\"").append(item.bitrate == 0 ? 1000000 : item.bitrate)
                    .append("\" codecs=\"").append(esc(item.codecs)).append("\" width=\"")
                    .append(item.width).append("\" height=\"").append(item.height);
            if (item.fps > 0) sb.append(" frameRate=\"").append(item.fps).append("/1\"");
            sb.append(">\n")
                    .append("        <SegmentTemplate timescale=\"1000\" startNumber=\"1\" initialization=\"")
                    .append(esc(base + "&track=video&itag=" + item.itag + "&seg=init")).append("\" media=\"")
                    .append(esc(base + "&track=video&itag=" + item.itag + "&seg=")).append("$Number$\">")
                    .append("<SegmentTimeline>").append(rows).append("</SegmentTimeline></SegmentTemplate>\n")
                    .append("      </Representation>\n");
        }
        return sb.append("    </AdaptationSet>\n").toString();
    }

    private String templateSet(YTFormat item, String base, String track, long totalMs, boolean video) {
        if (item == null) return "";
        long segMs = (long) ((item.sabrConfig == null ? (video ? 6 : 10) : item.sabrConfig.targetDurationSec) * 1000);
        if (video && segMs <= 0) segMs = 6000;
        if (!video && segMs < 8000) segMs = 10000;
        List<YTFormat.Seg> timeline = loadTimeline(item, totalMs);
        String rows = YTIndex.segmentTimelineXml(timeline);
        if (rows.isEmpty()) rows = evenRows(totalMs, segMs);
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
     * SegmentBase form of the SABR manifest.
     *
     * <p>The player requests byte ranges and this proxy converts them to timestamps via the real
     * sidx/Cues index, so no {@code $Number$} mapping error can accumulate.
     */
    private Object[] proxySabrMpd2(Map<String, String> params) {
        String vid = params.get("vid");
        String cacheKey = "yt_sabr_" + vid;
        SabrData data = vid == null ? null : sabrData(vid, "best", cacheKey, true);
        if (data == null || data.videoItem == null || data.audioItem == null) return text(404, "SABR 音视频缓存不存在");
        YTFormat video = data.videoItem;
        YTFormat audio = data.audioItem;
        long duration = data.duration;
        List<YTFormat.Seg> videoTimeline = loadTimeline(video, duration * 1000);
        List<YTFormat.Seg> audioTimeline = loadTimeline(audio, duration * 1000);
        if (videoTimeline.isEmpty() || audioTimeline.isEmpty()) return text(500, "SABR SegmentBase 需要 sidx/Cues 时间轴");
        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT")
                .append(duration).append("S\" minBufferTime=\"PT5S\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
                .append("  <Period id=\"1\" start=\"PT0S\">\n")
                .append(baseSet(vid, video, "video", true))
                .append(baseSet(vid, audio, "audio", false))
                .append("  </Period>\n</MPD>");
        return bytes(200, "application/dash+xml", mpd.toString().getBytes(), null);
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
     * Answers a SegmentBase byte range.
     *
     * <p>init/index ranges come straight from the matching direct URL: SABR never sends a sidx and
     * both ranges are tiny. Media ranges are mapped to a timestamp through the index and fetched
     * from the SABR session, then sliced to the requested length.
     */
    private Object[] proxySabrRange(Map<String, String> params) {
        String vid = params.get("vid");
        String track = params.get("track") == null ? "video" : params.get("track");
        if (!"video".equals(track) && !"audio".equals(track)) return text(400, "无效 SABR 轨道");
        SabrData data = vid == null ? null : sabrCache.get("yt_sabr_" + vid);
        if (data == null) return text(404, "SABR 缓存不存在");
        YTFormat item = "video".equals(track) ? data.videoItem : data.audioItem;
        if (item == null) return text(404, track + " 流不存在");
        long[] parsed = parseRange(range(params));
        Long start = parsed == null || parsed[0] < 0 ? null : parsed[0];
        Long end = parsed == null || parsed[1] < 0 ? null : parsed[1];
        long[] indexRange = item.indexSource != null && item.indexSource.indexRange != null
                ? item.indexSource.indexRange : item.indexRange;
        long indexEnd = indexRange == null ? 0 : indexRange[1];
        List<YTFormat.Seg> timeline = item.timeline == null ? new ArrayList<>() : item.timeline;
        long totalBytes = YTIndex.totalBytes(timeline) + indexEnd + 1;
        if (start != null && start <= indexEnd) return rangeFromDirect(item, start, end, indexEnd);
        Long targetMs = YTIndex.timeForByte(timeline, start, indexEnd);
        if (targetMs == null) return text(416, "无法将字节范围映射到时间轴");
        String stateKey = data.stateKey == null ? vid + ":sabr" : data.stateKey;
        YTSabrSession.Found found;
        try {
            found = session(stateKey).getSegment(data.videoItem, data.audioItem, track, "t=" + targetMs);
        } catch (Throwable e) {
            return text(500, "SABR 取段失败: " + e);
        }
        if (found == null || found.media == null) return text(500, "SABR 分段不可用");
        // The player asks for the byte range the sidx declared, while SABR returns whole native
        // segments. Align by the requested length: return short content as-is, truncate overlong
        // content. ExoPlayer trusts Content-Range for what it actually received.
        byte[] media = found.media;
        byte[] body = media;
        if (start != null && end != null) {
            int requested = (int) Math.max(0, end - start + 1);
            if (requested < media.length) {
                body = new byte[requested];
                System.arraycopy(media, 0, body, 0, requested);
            }
        }
        long begin = start == null ? 0 : start;
        long realEnd = begin + body.length - 1;
        String contentType = mimeBase(fallback(item.mimeType, "video".equals(track) ? "video/webm" : "audio/mp4"));
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", contentType);
        headers.put("Content-Length", String.valueOf(body.length));
        headers.put("Content-Range", "bytes " + begin + "-" + realEnd + "/" + totalBytes);
        headers.put("Accept-Ranges", "bytes");
        headers.put("Cache-Control", "no-cache");
        return bytes(206, contentType, body, headers);
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
        String cacheKey = "best".equals(quality) ? "yt_sabr_" + vid : "yt_sabr_" + vid + "_" + quality;
        String track = params.get("track") == null ? "video" : params.get("track");
        String segment = params.get("seg") == null ? "init" : params.get("seg");
        SabrData data = vid == null ? null : sabrCache.get(cacheKey);
        if (data == null) return text(404, "SABR 缓存不存在");
        if (!"video".equals(track) && !"audio".equals(track)) return text(400, "无效 SABR 轨道");
        boolean init = "init".equals(segment);
        int attempts = 1 + (init ? data.candidates.size() : 0);
        String lastError = null;
        for (int attempt = 0; attempt < attempts; attempt++) {
            SabrData current = sabrCache.get(cacheKey);
            if (current != null) data = current;
            int requestIndex = data.activeIndex;
            String stateKey = data.stateKey == null ? vid + ":sabr" : data.stateKey;
            YTFormat requestedVideo = data.videoItem;
            if ("video".equals(track) && params.get("itag") != null) {
                try {
                    int requestedItag = Integer.parseInt(params.get("itag"));
                    if (data.videoItems != null) {
                        for (YTFormat candidate : data.videoItems) {
                            if (candidate != null && candidate.itag == requestedItag) {
                                requestedVideo = candidate;
                                break;
                            }
                        }
                    }
                } catch (Throwable ignored) {
                    // Keep the initial representation when the player sends a malformed itag.
                }
            }
            YTFormat item = "video".equals(track) ? requestedVideo : data.audioItem;
            try {
                YTSabrSession.Found found = session(stateKey)
                        .getSegment(requestedVideo, data.audioItem, track, segment);
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