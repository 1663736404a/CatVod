package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
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
 * <p>The SABR bridge ({@code sabr_mpd} + {@code sabr}) serves formats with no per-format URL,
 * negotiated through {@link YTSabrSession}. It runs exclusively in <b>micro-segment mode</b>
 * (see {@link #proxySabrMpd}): MPD windows much shorter than real SABR segments keep the player
 * polling, and each window is answered with just the clusters/fragments starting inside it
 * (0-byte 200 when a window's payload was already delivered with an earlier window).
 */
final class YTPlay {

    private static final long SABR_CACHE_MS = 1800 * 1000L;

    private final YouTubeLite yt;
    private final Map<String, String> header;
    private final JsonObject ext;
    private final String siteKey;

    private final Map<String, SabrData> sabrCache = new HashMap<>();

    private final Object sabrSwitchLock = new Object();

    YTPlay(YouTubeLite yt, Map<String, String> header, JsonObject ext, String siteKey) {
        this.yt = yt;
        this.header = header;
        this.ext = ext;
        this.siteKey = siteKey;
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
        /** Micro-segment window in ms. */
        long microSegMs;
    }

    /* ------------------------------------------------------------------ */
    /* proxy entry point                                                  */
    /* ------------------------------------------------------------------ */

    Object[] proxy(Map<String, String> params) {
        String type = params.get("type");
        if (type == null) return null;
        switch (type) {
            case "sabr_mpd":
                return proxySabrMpd(params);
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

    

    

    private static String low(String text) {
        return text == null ? "" : text.toLowerCase(Locale.US);
    }

    private static String mimeBase(String mime) {
        if (mime == null) return "";
        int index = mime.indexOf(';');
        return index < 0 ? mime : mime.substring(0, index);
    }

    

    

    /**
     * Ranks direct audio-only formats.
     *
     * <p>EC-3/AC-3 and friends are only used when nothing else exists, so a high-bitrate surround
     * track can never displace plain AAC.
     */
    

    /**
     * Switches to the next cached audio candidate after a 403.
     *
     * <p>The MPD has already been published, so only a candidate with the same itag/container/codec
     * may be substituted; anything else would break SegmentBase compatibility.
     */
    

    /**
     * Re-resolves direct tracks when the cache is gone or a URL was rejected.
     *
     * <p>Forced refreshes are throttled: one per 10s per {@code vid+quality}. Without it every
     * rejected Range triggers its own extraction, and the refreshed URLs get rejected just as fast.
     */
    

    

    /* ------------------------------------------------------------------ */
    /* direct proxies                                                     */
    /* ------------------------------------------------------------------ */

    

    

    

    

    /**
     * Serves a muxed (progressive) stream in bounded chunks.
     *
     * <p>An open-ended Range is never forwarded: the whole file would be buffered in memory before
     * the player received anything.
     */
    

    /* ------------------------------------------------------------------ */
    /* SABR candidates                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * SABR client polling order.
     *
     * <p>ANDROID is the only client verified in this environment; the others answer 403 and must
     * not be polled unless the config asks for them. WEB/WEB_INITIAL are deliberately excluded.
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
        if (out.isEmpty()) out.add("ANDROID");
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
        String stateKey = vid + ":sabr:" + selected.client + ":" + selected.video.itag + ":" + selected.audio.itag;
        synchronized (yt.sabrState) {
            yt.sabrState.remove(stateKey);
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
        if (candidates.isEmpty()) return null;
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

    /**
     * Builds the {@code sabr_mpd} manifest (SegmentTemplate/{@code $Number$}).
     *
     * <p><b>Micro-segment mode</b> (default): the MPD declares windows far shorter than a real SABR
     * segment ({@code sabr_micro_seg_ms}, default 1000ms), so the player keeps asking for the next
     * {$Number$} instead of silently waiting out a declared duration whose payload it has already
     * exhausted. Each window is answered with exactly the clusters (WebM) or movie fragments
     * (fMP4) whose decode time falls inside it; a window whose payload was already delivered
     * inside an earlier window gets a 0-byte 200. The union over windows reproduces each native
     * segment once, so no data can be skipped or duplicated regardless of how far ahead the
     * player prefetches. There is no legacy fallback: micro mode is the only manifest.
     */
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
        long microMs = Math.min(4000, YouTubeLite.optLong(ext, "sabr_micro_seg_ms", 1000));
        data.microSegMs = Math.max(200, microMs);
        String rows = evenRows(duration * 1000, data.microSegMs);
        SpiderDebug.log("SABR 微片模式 window=" + data.microSegMs + "ms 时长=" + duration
                + "s video=" + low(mimeBase(fallback(video.mimeType, "")))
                + " audio=" + low(mimeBase(fallback(audio.mimeType, ""))));
        StringBuilder mpd = new StringBuilder();
        mpd.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
                .append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" type=\"static\" mediaPresentationDuration=\"PT")
                .append(duration).append("S\" minBufferTime=\"PT10S\" ")
                .append("profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\">\n")
                .append("  <Period id=\"1\" start=\"PT0S\">\n")
                .append(templateSet(video, base, "video", rows, true))
                .append(templateSet(audio, base, "audio", rows, false))
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
     * SegmentBase form of the SABR manifest.
     *
     * <p>The player requests byte ranges and this proxy converts them to timestamps via the real
     * sidx/Cues index, so no {@code $Number$} mapping error can accumulate.
     */
    

    

    /** Fetches and caches a format's real segment index, so the manifest carries exact t/d values. */
    

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
            YTFormat item = "video".equals(track) ? data.videoItem : data.audioItem;
            // Micro-segment mode: a $Number$ maps to a short declared window, not to a native
            // segment number. Fetch the native segment covering the window's start, then hand out
            // only its clusters whose Timecode falls inside [winStart, winStart + window). Windows
            // whose clusters already went out with an earlier window get a 0-byte 200.
            String fetchKey = segment;
            long winStart = -1;
            long winMs = 0;
            if (!init && data.microSegMs > 0) {
                try {
                    long num = Long.parseLong(segment);
                    winMs = data.microSegMs;
                    winStart = Math.max(0, (num - 1) * winMs);
                    fetchKey = "t=" + winStart;
                } catch (NumberFormatException ignored) {
                    // Non-numeric media keys keep legacy handling.
                }
            }
            try {
                YTSabrSession.Found found = session(stateKey)
                        .getSegment(data.videoItem, data.audioItem, track, fetchKey);
                if (found == null || found.media == null) {
                    lastError = found == null ? "empty response" : found.error;
                    if (init && switchClient(vid, requestIndex, cacheKey) != null) continue;
                    SpiderDebug.log("SABR 微片失败 track=" + track + " seg=" + segment + " err=" + lastError);
                    return text(500, "SABR 分段不可用: " + lastError);
                }
                SabrData latest = sabrCache.get(cacheKey);
                if (init && latest != null && latest.activeIndex != requestIndex) continue;
                String contentType = mimeBase(fallback(item == null ? null : item.mimeType,
                        "video".equals(track) ? "video/webm" : "audio/webm"));
                byte[] payload = found.media;
                if (winStart >= 0) {
                    List<YTIndex.Cluster> units = slicePayload(data, track, found.media);
                    if (units != null) {
                        payload = windowClusters(units, winStart, winMs);
                        String tag = ("video".equals(track) ? "v#" : "a#") + segment;
                        if (payload.length == 0) {
                            SpiderDebug.log("SABR 微片 " + tag + " t=" + winStart + " 空200(数据已随前窗交付)");
                        } else {
                            SpiderDebug.log("SABR 微片 " + tag + " t=" + winStart + " 簇=" + countClusters(units, winStart, winMs)
                                    + " 字节=" + payload.length + "/" + found.media.length);
                        }
                    } else {
                        SpiderDebug.log("SABR 微片切片失败 track=" + track + " seg=" + segment
                                + " 回退整段 " + found.media.length + "B(可能有重复数据)");
                    }
                }
                Map<String, String> headers = new LinkedHashMap<>();
                headers.put("Content-Type", contentType);
                headers.put("Content-Length", String.valueOf(payload.length));
                headers.put("Cache-Control", "private, max-age=30");
                headers.put("Accept-Ranges", "none");
                return bytes(200, contentType, payload, headers);
            } catch (Throwable e) {
                lastError = String.valueOf(e);
                SpiderDebug.log("SABR 微片异常 track=" + track + " seg=" + segment + " err=" + lastError);
                boolean canFailover = init && lastError.contains("SABR HTTP 4");
                if (!canFailover || switchClient(vid, requestIndex, cacheKey) == null) break;
            }
        }
        return text(500, "SABR 代理失败: " + lastError);
    }

    /* ------------------------------------------------------------------ */
    /* helpers                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * Splits one native SABR payload into sliceable units for the micro windows.
     *
     * <p>WebM payloads are split at top-level Clusters; fMP4 payloads at {@code [moof][mdat]}
     * fragments, using the track's cached init segment for the timescale.
     *
     * @return the units, or {@code null} when the container is neither or does not parse.
     */
    private List<YTIndex.Cluster> slicePayload(SabrData data, String track, byte[] media) {
        String mime = low(mimeBase(fallback(("video".equals(track) ? data.videoItem : data.audioItem) == null
                ? null : ("video".equals(track) ? data.videoItem : data.audioItem).mimeType, "")));
        if (mime.contains("webm")) return YTIndex.splitWebmClusters(media);
        if (mime.contains("mp4")) {
            try {
                String stateKey = data.stateKey;
                YTSabrSession.Found init = session(stateKey)
                        .getSegment(data.videoItem, data.audioItem, track, "init");
                if (init == null || init.media == null) return null;
                return YTIndex.splitMp4Fragments(init.media, media);
            } catch (Throwable e) {
                SpiderDebug.log("SABR 微片 init 取回失败 track=" + track + " err=" + e);
                return null;
            }
        }
        return null;
    }

    /** Concatenates the clusters whose start time falls inside one micro window. */
    private static byte[] windowClusters(List<YTIndex.Cluster> clusters, long winStart, long winMs) {        int total = 0;
        for (YTIndex.Cluster cluster : clusters) {
            if (cluster.ptsMs >= winStart && cluster.ptsMs < winStart + winMs) total += cluster.data.length;
        }
        byte[] out = new byte[total];
        int pos = 0;
        for (YTIndex.Cluster cluster : clusters) {
            if (cluster.ptsMs >= winStart && cluster.ptsMs < winStart + winMs) {
                System.arraycopy(cluster.data, 0, out, pos, cluster.data.length);
                pos += cluster.data.length;
            }
        }
        return out;
    }

    private static int countClusters(List<YTIndex.Cluster> clusters, long winStart, long winMs) {
        int count = 0;
        for (YTIndex.Cluster cluster : clusters) {
            if (cluster.ptsMs >= winStart && cluster.ptsMs < winStart + winMs) count++;
        }
        return count;
    }

    

    

    

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