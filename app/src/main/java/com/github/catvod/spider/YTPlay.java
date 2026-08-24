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
 * Playback bridge for the YouTube spider: builds the local DASH manifest and answers the segment
 * requests the player issues against it.
 *
 * <p>One bridge only: {@code sabr_mpd} publishes a micro-window {@code $Time$} SegmentTemplate and
 * {@code sabr_time} serves each window from {@link YTSabrSession}. Formats reached this way have no
 * per-format URL and are negotiated entirely through SABR/UMP.
 *
 * <p><b>Micro-window scheme.</b> The MPD declares windows much shorter than a real SABR segment
 * ({@code sabr_micro_seg_ms}, default 1000ms) so the player keeps requesting the next one instead
 * of waiting out a declared duration whose payload it has already consumed. A native segment is
 * delivered whole by the single window that owns it — {@code owner = ceil(start/win)*win} — and
 * every other window answers 0 bytes with HTTP 200.
 */
final class YTPlay {

    private static final long SABR_CACHE_MS = 1800 * 1000L;

    private static final java.util.concurrent.atomic.AtomicLong OWNER_SEQ =
            new java.util.concurrent.atomic.AtomicLong();

    private final YouTubeLite yt;
    private final Map<String, String> header;
    private final JsonObject ext;
    private final String siteKey;
    /** Identifies this YTPlay instance inside {@link YTServer}'s owner registry. */
    private final String ownerId = "p" + OWNER_SEQ.incrementAndGet();

    private final Map<String, SabrData> sabrCache = new HashMap<>();
    // A and B can request the same video MPD concurrently. Serialize extraction per video so
    // both routes share one successful TVHTML5/poToken response instead of racing BotGuard.
    private final Map<String, Object> sabrExtractLocks = new HashMap<>();
    // A local MPD can outlive the host's current episode during sequential playback.
    // Include a generation in the SABR state key so a new extraction never reuses old UMP state.
    // Static: a Spider rebuilt after the host's registry clear must not restart the counter at 1 and
    // collide with a state key the previous instance is still serving.
    private static final Map<String, Long> sabrGenerations = new HashMap<>();
    /** Candidate signature behind each generation, so re-activating the same one does not bump it. */
    private static final Map<String, String> sabrSignatures = new HashMap<>();
    /** State keys created by this instance; only these may be torn down by it. */
    private final Set<String> ownedStateKeys = Collections.synchronizedSet(new HashSet<>());
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
        /** Micro-window size in ms, published in the manifest and used for owner arithmetic. */
        long microSegMs;
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
        if ("sabr_mpd".equals(type)) {
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
            case "sabr_mpd":
                return proxySabrMpd(params);
            case "sabr_time":
                return proxySabrTime(params);
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

    private static String low(String text) {
        return text == null ? "" : text.toLowerCase(Locale.US);
    }

    private static String mimeBase(String mime) {
        if (mime == null) return "";
        int index = mime.indexOf(';');
        return index < 0 ? mime : mime.substring(0, index);
    }

    /* ------------------------------------------------------------------ */
    /* direct proxies                                                     */
    /* ------------------------------------------------------------------ */

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

    /**
     * Publishes the micro-window manifest.
     *
     * <p>The {@code SegmentTimeline} is a pure request grid of {@code sabr_micro_seg_ms} windows
     * (default 1000ms), deliberately much shorter than a real SABR segment. A player that has
     * consumed a segment's payload before its declared duration elapses would otherwise sit and
     * wait; with short windows it keeps asking for the next {@code $Time$}, and
     * {@link #proxySabrTime} answers either the owning segment or an empty 200.
     *
     * <p>Request-driven on purpose: no warm producer is started from the manifest request.
     * googlevideo issues each {@code VideoPlaybackAbrRequest} at the player's actual position, so
     * starting a pump at t=0 here would make a resumed or seeked first request queue behind it.
     */
    private Object[] proxySabrMpd(Map<String, String> params) {
        String vid = params.get("vid");
        String quality = params.get("quality") == null ? "best" : params.get("quality");
        String sid = params.get("sid");
        String cacheKey = sabrCacheKey(vid, quality, sid, true);
        SabrData data = vid == null ? null : sabrData(vid, quality, cacheKey, true);
        if (data == null || data.videoItem == null || data.audioItem == null) return text(404, "SABR 音视频缓存不存在");
        long durationMs = Math.max(1000L, data.duration * 1000L);
        String stateKey = data.stateKey == null ? vid + ":sabr:b" : data.stateKey;
        session(stateKey).touch();
        long winMs = microWindowMs();
        data.microSegMs = winMs;
        String rows = windowRows(durationMs, winMs);
        com.github.catvod.crawler.SpiderDebug.log("YouTube 微片 MPD: vid=" + vid
                + ", window=" + winMs + "ms, 时长=" + data.duration
                + "s, video=" + low(mimeBase(fallback(data.videoItem.mimeType, "")))
                + ", audio=" + low(mimeBase(fallback(data.audioItem.mimeType, ""))));
        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT")
                .append(data.duration).append("S\" minBufferTime=\"PT30S\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
                .append("  <Period id=\"1\" start=\"PT0S\">\n")
                .append(timeTemplateSet(vid, quality, sid, data.videoItem, "video", true, rows))
                .append(timeTemplateSet(vid, quality, sid, data.audioItem, "audio", false, rows))
                .append("  </Period>\n</MPD>");
        return bytes(200, "application/dash+xml", mpd.toString().getBytes(), null);
    }

    /** Window size in ms, clamped so a window is always shorter than a plausible native segment. */
    private long microWindowMs() {
        long value = YouTubeLite.optLong(ext, "sabr_micro_seg_ms", 1000);
        return Math.max(200L, Math.min(4000L, value));
    }

    /** Uniform {@code <S t d>} grid covering the whole presentation. */
    private static String windowRows(long durationMs, long winMs) {
        long count = Math.max(1L, (durationMs + winMs - 1) / winMs);
        return "<S t=\"0\" d=\"" + winMs + "\" r=\"" + (count - 1) + "\"/>";
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
                .append("      <Representation id=\"sabr-").append(video ? "v" : "a").append(item.itag)
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

    /* ------------------------------------------------------------------ */
    /* SABR segments                                                      */
    /* ------------------------------------------------------------------ */

    /**
     * Serves one micro window.
     *
     * <p>The requested {@code t=} is a window start, not a segment boundary. The session returns the
     * complete native segment whose real MEDIA_HEADER interval covers that instant; this method then
     * decides whether the window <em>owns</em> that segment. Ownership is
     * {@code owner = ceil(start/win)*win} — the first window boundary at or after the segment start
     * — so each native segment is handed to the player by exactly one window and every other window
     * gets an empty 200. That keeps the player polling (which is the point of the short windows)
     * without ever duplicating or skipping payload, regardless of how far ahead it prefetches.
     *
     * <p>Because a window is shorter than a native segment, {@code owner} always falls inside the
     * segment, so the owning window's request really does resolve to that segment. Segments shorter
     * than a window cannot satisfy that and fall back to the window containing their start.
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
            return text(404, "SABR 缓存不存在");
        }
        YTFormat item = "video".equals(track) ? data.videoItem : data.audioItem;
        // Keep one SABR protocol session for both tracks. The playback cookie, rn sequence and
        // buffered context are shared by the video and audio itags; the session lock is fair so
        // a large 4K video pump still yields between requests.
        String stateKey = data.stateKey == null ? vid + ":sabr:b" : data.stateKey;
        boolean init = "init".equals(segment);
        long winStart = -1;
        String requested = "init";
        if (!init) {
            if (!segment.startsWith("t=")) return text(400, "无效微片时间段");
            try {
                winStart = Math.max(0L, Long.parseLong(segment.substring(2)));
                requested = "t=" + winStart;
            } catch (Throwable e) {
                return text(400, "无效微片时间段");
            }
        }
        long winMs = data.microSegMs > 0 ? data.microSegMs : microWindowMs();
        String tag = ("video".equals(track) ? "v" : "a") + "@" + (init ? "init" : winStart);
        // Two attempts: the second one runs against a freshly extracted player response, which is
        // the only way to recover when the server sends RELOAD_PLAYER_RESPONSE (UMP part 46).
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                YTSabrSession active = session(stateKey);
                // Do not wait for a background producer. Fetch this exact player request directly,
                // using getSegment()'s SABR request/UMP loop. This mirrors googlevideo's
                // request-driven stream and PipePipe's requestOnce(): the requested t= value is
                // immediately encoded as player_time_ms, so resume and seek do not queue behind t=0.
                YTSabrSession.Found found = active
                        .getSegment(data.videoItem, data.audioItem, track, requested);
                if (found == null || found.media == null || found.media.length == 0) {
                    com.github.catvod.crawler.SpiderDebug.log("YouTube 微片失败 " + tag
                            + " err=" + (found == null ? "null" : found.error)
                            + " status=" + active.lastStatus());
                    return text(503, "微片未产生媒体");
                }
                byte[] payload = found.media;
                if (!init) {
                    YTSabrSession.Meta meta = found.meta;
                    if (meta == null) {
                        com.github.catvod.crawler.SpiderDebug.log("YouTube 微片 " + tag
                                + " 无meta 整段交付 " + payload.length + "B");
                    } else {
                        long start = Math.max(0L, meta.startMs);
                        long end = start + Math.max(1L, meta.durationMs);
                        long owner = ((start + winMs - 1) / winMs) * winMs;
                        if (owner >= end) {
                            // Shorter than a window: its owner boundary lies past the segment, so no
                            // window request could ever resolve to it there. Use the window that
                            // contains the start instead.
                            owner = (start / winMs) * winMs;
                            com.github.catvod.crawler.SpiderDebug.log("YouTube 微片 " + tag
                                    + " 分片短于窗口 start=" + start + " dur=" + meta.durationMs
                                    + " 改用起点窗 " + owner);
                        }
                        if (owner == winStart) {
                            com.github.catvod.crawler.SpiderDebug.log("YouTube 微片 " + tag
                                    + " 交付分片 start=" + start + " dur=" + meta.durationMs
                                    + " 字节=" + payload.length);
                        } else {
                            payload = new byte[0];
                            com.github.catvod.crawler.SpiderDebug.log("YouTube 微片 " + tag
                                    + " 空200(分片 start=" + start + " 归属窗 " + owner + ")");
                        }
                    }
                }
                String contentType = mimeBase(fallback(item.mimeType,
                        "video".equals(track) ? "video/webm" : "audio/webm"));
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", contentType);
                headers.put("Content-Length", String.valueOf(payload.length));
                headers.put("Cache-Control", "private, max-age=30");
                headers.put("Accept-Ranges", "none");
                return bytes(200, contentType, payload, headers);
            } catch (YTSabrSession.ReloadRequired reload) {
                com.github.catvod.crawler.SpiderDebug.log("YouTube 微片需重新提取 " + tag
                        + " attempt=" + attempt + " reason=" + reload.getMessage());
                if (attempt > 0) return text(503, "微片需重新提取但重试已用尽");
                SabrData rebuilt = reextract(vid, quality, cacheKey);
                if (rebuilt == null) return text(503, "微片重新提取失败");
                data = rebuilt;
                item = "video".equals(track) ? data.videoItem : data.audioItem;
                stateKey = data.stateKey == null ? vid + ":sabr:b" : data.stateKey;
                if (data.microSegMs > 0) winMs = data.microSegMs;
            } catch (Throwable e) {
                com.github.catvod.crawler.SpiderDebug.log("YouTube 微片取段失败 " + tag
                        + " status=" + session(stateKey).lastStatus() + " error=" + String.valueOf(e));
                return text(503, "微片取段失败: " + String.valueOf(e));
            }
        }
        return text(503, "微片取段失败");
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
        return sabrData(vid, quality, cacheKey, true);
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    /** Parses {@code bytes=start-end}; a missing bound is reported as -1. */

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