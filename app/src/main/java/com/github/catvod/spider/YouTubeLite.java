package com.github.catvod.spider;

import android.net.Uri;
import android.text.TextUtils;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube player-response extractor: watch page parsing, InnerTube player calls, signature
 * handling and format normalisation.
 *
 * <p>Ported from the reference Python implementation. Track-selection ordering and the SABR
 * session rules are preserved exactly, since they encode behaviour verified against live playback.
 */
class YouTubeLite {

    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
/** Matches nothing; stands in for a pattern that failed to compile. */
    private static final Pattern NEVER = Pattern.compile("(?!x)x");

    /**
     * Compiles a pattern without letting a bad one abort class initialization.
     *
     * <p>A {@code PatternSyntaxException} thrown from a static field initializer surfaces as
     * {@code ExceptionInInitializerError}, which makes the whole class unloadable and takes the
     * spider down before {@code init} can return. Android's ICU engine is stricter than the
     * desktop JDK engine (an unescaped closing brace is rejected, for one), so a pattern that
     * compiles during a desktop build can still fail on device. Degrading to a never-matching
     * pattern keeps the rest of the spider usable.
     */
    private static Pattern safePattern(String regex, int flags) {
        try {
            return Pattern.compile(regex, flags);
        } catch (Throwable e) {
            SpiderDebug.log("YouTube 正则编译失败，该规则已停用: " + regex + " " + e);
            return NEVER;
        }
    }

    private static Pattern safePattern(String regex) {
        return safePattern(regex, 0);
    }


    private static final Pattern RE_VIDEO_ID_URL =
            safePattern("(?:v=|/v/|/embed/|/shorts/|youtu\\.be/)([0-9A-Za-z_-]{11})");
    private static final Pattern RE_VIDEO_ID_BARE = safePattern("^([0-9A-Za-z_-]{11})$");
    private static final Pattern RE_CODECS = safePattern("codecs=\"([^\"]+)\"");
    private static final Pattern RE_API_KEY = safePattern("\"INNERTUBE_API_KEY\":\"([^\"]+)\"");
    private static final Pattern RE_STS = safePattern("(?:signatureTimestamp|sts)\\s*:\\s*(\\d{5})");
    // Android's ICU regex engine rejects an unescaped closing brace, unlike the desktop JDK
    // engine. Every literal brace in this file is escaped for that reason.
    private static final Pattern RE_YTCFG = safePattern("ytcfg\\.set\\s*\\(\\s*(\\{.+?\\})\\s*\\)\\s*;", Pattern.DOTALL);

    /** Result of one successful extraction. */
    static class Extracted {
        String id;
        String title;
        long duration;
        boolean isLive;
        boolean isLiveContent;
        String liveStart = "";
        String hlsUrl = "";
        String dashUrl = "";
        List<YTFormat> formats = new ArrayList<>();
        List<YTFormat> sabrFormats = new ArrayList<>();
        String playerUrl = "";
    }

    private static class CacheEntry {
        Extracted data;
        long expires;
    }

    private final YTHttp http;
    private final Map<String, String> headers;
    private final JsonObject config;
    private final Map<String, String> playerCache = new HashMap<>();
    private final Map<String, Extracted> extractCacheData = new HashMap<>();
    private final Map<String, CacheEntry> extractCache = new HashMap<>();
    private final Map<String, List<String[]>> sigPlanCache = new HashMap<>();
    private final long extractCacheTtl;

    /** SABR session state, keyed by {@code vid:client:videoItag:audioItag}. */
    final Map<String, YTSabrSession> sabrState = new HashMap<>();

    YouTubeLite(YTHttp http, Map<String, String> headers, JsonObject config) {
        this.http = http;
        this.headers = headers == null ? new HashMap<>() : headers;
        this.config = config == null ? new JsonObject() : config;
        this.extractCacheTtl = optLong(this.config, "extract_cache_ttl", 300);
    }

    YTHttp http() {
        return http;
    }

    Map<String, String> headers() {
        return headers;
    }

    /* ------------------------------------------------------------------ */
    /* extraction                                                         */
    /* ------------------------------------------------------------------ */

    Extracted extract(String urlOrId) throws Exception {
        return extract(urlOrId, false);
    }

    Extracted extract(String urlOrId, boolean forceRefresh) throws Exception {
        String videoId = extractVideoId(urlOrId);
        CacheEntry cached = extractCache.get(videoId);
        long now = System.currentTimeMillis();
        if (!forceRefresh && cached != null && cached.expires > now) return cached.data;

        String watchUrl = "https://www.youtube.com/watch?v=" + videoId;
        String page = http.string(watchUrl);

        JsonObject ytcfg = extractYtcfg(page);
        JsonObject initialPr = extractJsonAfter(page, "ytInitialPlayerResponse");
        String playerUrl = extractPlayerUrl(page);
        String apiKey = optString(ytcfg, "INNERTUBE_API_KEY", search(RE_API_KEY, page));
        String visitorData = extractVisitorData(ytcfg, initialPr);
        Integer sts = extractSignatureTimestamp(playerUrl);

        JsonObject context = ytcfg.has("INNERTUBE_CONTEXT")
                ? ytcfg.getAsJsonObject("INNERTUBE_CONTEXT")
                : defaultContext();

        List<JsonObject> responses = new ArrayList<>();
        if (initialPr != null && initialPr.size() > 0) {
            initialPr.addProperty("_client_name", "WEB_INITIAL");
            String ua = headers.containsKey("User-Agent") ? headers.get("User-Agent") : UA;
            initialPr.addProperty("_client_ua", ua);
            JsonObject info = new JsonObject();
            info.addProperty("clientNameId", 1);
            info.addProperty("clientName", "WEB");
            info.addProperty("clientVersion", "initial");
            info.addProperty("userAgent", ua);
            info.addProperty("hl", "en");
            info.addProperty("gl", "US");
            if (visitorData != null) info.addProperty("visitorData", visitorData);
            initialPr.add("_client_info", info);
            responses.add(initialPr);
        }
        if (!TextUtils.isEmpty(apiKey)) {
            responses.addAll(callPlayerApi(videoId, apiKey, context, watchUrl, visitorData, sts));
        }

        JsonObject best = null;
        // TVHTML5 is the only client in this implementation that is allowed to supply the
        // full-length SABR response. Do not let the watch-page WEB_INITIAL response win merely
        // because it appears first in the list.
        for (JsonObject response : responses) {
            if ("TVHTML5".equals(optString(response, "_client_name", ""))
                    && "OK".equals(traverseString(response, "playabilityStatus", "status"))) {
                best = response;
                break;
            }
        }
        if (best == null) {
            for (JsonObject response : responses) {
                if ("OK".equals(traverseString(response, "playabilityStatus", "status"))) {
                    best = response;
                    break;
                }
            }
        }
        if (best == null) best = initialPr == null ? new JsonObject() : initialPr;

        String status = traverseString(best, "playabilityStatus", "status");
        JsonObject streaming = traverseObject(best, "streamingData");
        if (status != null && !"OK".equals(status) && !"LIVE_STREAM_OFFLINE".equals(status)
                && (streaming == null || streaming.size() == 0)) {
            String reason = traverseString(best, "playabilityStatus", "reason");
            throw new Exception("YouTube 不可播放: " + (reason == null ? status : reason));
        }

        JsonObject details = traverseObject(best, "videoDetails");
        JsonObject microformat = traverseObject(best, "microformat", "playerMicroformatRenderer");
        JsonObject liveDetails = microformat == null ? null : traverseObject(microformat, "liveBroadcastDetails");
        boolean isLive = optBool(details, "isLive") || optBool(liveDetails, "isLiveNow");
        boolean isLiveContent = optBool(details, "isLiveContent") || isLive;

        String hlsUrl = "";
        String dashUrl = "";
        for (JsonObject response : responses) {
            JsonObject sd = traverseObject(response, "streamingData");
            if (sd == null) continue;
            if (hlsUrl.isEmpty()) hlsUrl = optString(sd, "hlsManifestUrl", "");
            if (dashUrl.isEmpty()) dashUrl = optString(sd, "dashManifestUrl", "");
        }

        Extracted result = new Extracted();
        result.id = videoId;
        result.title = optString(details, "title", videoId);
        result.duration = optLong(details, "lengthSeconds", 0);
        result.isLive = isLive;
        result.isLiveContent = isLiveContent;
        result.liveStart = optString(liveDetails, "startTimestamp", "");
        result.hlsUrl = hlsUrl;
        result.dashUrl = dashUrl;
        result.playerUrl = playerUrl;
        extractFormats(responses, playerUrl, result);

        CacheEntry entry = new CacheEntry();
        entry.data = result;
        entry.expires = System.currentTimeMillis() + extractCacheTtl * 1000;
        extractCache.put(videoId, entry);
        return result;
    }

    static String extractVideoId(String text) throws Exception {
        String value = text == null ? "" : text.trim();
        Matcher m = RE_VIDEO_ID_URL.matcher(value);
        if (m.find()) return m.group(1);
        m = RE_VIDEO_ID_BARE.matcher(value);
        if (m.find()) return m.group(1);
        throw new Exception("无法识别 YouTube 视频 ID");
    }

    int clientNameId(String clientName) {
        if (clientName == null) return 1;
        switch (clientName) {
            case "WEB": return 1;
            case "MWEB": return 2;
            case "ANDROID": return 3;
            case "IOS": return 5;
            case "TVHTML5": return 7;
            case "ANDROID_VR": return 28;
            case "WEB_EMBEDDED_PLAYER": return 56;
            case "WEB_REMIX": return 67;
            default: return 1;
        }
    }

    private String extractVisitorData(JsonObject ytcfg, JsonObject playerResponse) {
        String fromConfig = optString(config, "visitor_data", null);
        if (fromConfig != null) return fromConfig;
        String value = optString(ytcfg, "VISITOR_DATA", null);
        if (value != null) return value;
        value = traverseString(ytcfg, "INNERTUBE_CONTEXT", "client", "visitorData");
        if (value != null) return value;
        return traverseString(playerResponse, "responseContext", "visitorData");
    }

    private Integer extractSignatureTimestamp(String playerUrl) {
        try {
            String code = playerCode(playerUrl);
            String sts = search(RE_STS, code);
            return sts == null ? null : Integer.parseInt(sts);
        } catch (Throwable e) {
            return null;
        }
    }

    private String poToken(String clientName) {
        JsonElement tokens = config.has("po_token") ? config.get("po_token")
                : config.has("po_tokens") ? config.get("po_tokens") : null;
        if (tokens == null) return null;
        if (tokens.isJsonPrimitive()) return tokens.getAsString();
        if (tokens.isJsonObject()) {
            JsonObject obj = tokens.getAsJsonObject();
            String key = clientName + ".gvs";
            if (obj.has(key)) return obj.get(key).getAsString();
            if (clientName != null && obj.has(clientName)) return obj.get(clientName).getAsString();
            if (obj.has("gvs")) return obj.get("gvs").getAsString();
        }
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* player API                                                         */
    /* ------------------------------------------------------------------ */

    private static JsonObject clientContext(String json) {
        return Json.safeObject(json);
    }

    private List<JsonObject> callPlayerApi(String videoId, String apiKey, JsonObject webContext,
                                           String referer, String visitorData, Integer sts) {
        List<JsonObject> clients = new ArrayList<>();
        String version = optString(config, "tvhtml5_client_version", "7.20250312.16.00");
        String ua = optString(config, "tvhtml5_user_agent", "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) "
                + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15");
        JsonObject tv = new JsonObject();
        JsonObject tvClient = new JsonObject();
        tvClient.addProperty("clientName", "TVHTML5");
        tvClient.addProperty("clientVersion", version);
        tvClient.addProperty("userAgent", ua);
        tvClient.addProperty("hl", "en");
        tvClient.addProperty("gl", "US");
        tv.add("client", tvClient);
        clients.add(tv);

        List<JsonObject> results = new ArrayList<>();
        for (JsonObject ctx : clients) {
            JsonObject client = traverseObject(ctx, "client");
            if (client == null) continue;
            String clientName = optString(client, "clientName", null);
            try {
                String url = "https://www.youtube.com/youtubei/v1/player?key=" + apiKey + "&prettyPrint=false";
                JsonObject playbackCtx = new JsonObject();
                JsonObject contentCtx = new JsonObject();
                contentCtx.addProperty("html5Preference", "HTML5_PREF_WANTS");
                if (sts != null) contentCtx.addProperty("signatureTimestamp", sts);
                playbackCtx.add("contentPlaybackContext", contentCtx);

                if (visitorData != null) client.addProperty("visitorData", visitorData);
                JsonObject payload = new JsonObject();
                payload.add("context", ctx);
                payload.addProperty("videoId", videoId);
                payload.add("playbackContext", playbackCtx);
                payload.addProperty("contentCheckOk", true);
                payload.addProperty("racyCheckOk", true);
                String token = poToken(clientName);
                if (!TextUtils.isEmpty(token)) {
                    JsonObject integrity = new JsonObject();
                    integrity.addProperty("poToken", token);
                    payload.add("serviceIntegrityDimensions", integrity);
                }

                Map<String, String> reqHeaders = new HashMap<>();
                reqHeaders.put("Origin", "https://www.youtube.com");
                reqHeaders.put("Referer", referer);
                reqHeaders.put("X-YouTube-Client-Name", String.valueOf(clientNameId(clientName)));
                reqHeaders.put("X-YouTube-Client-Version", optString(client, "clientVersion", ""));
                if (visitorData != null) reqHeaders.put("X-Goog-Visitor-Id", visitorData);
                String clientUa = optString(client, "userAgent", null);
                if (clientUa != null) reqHeaders.put("User-Agent", clientUa);

                String body = http.postJson(url, payload.toString(), reqHeaders);
                JsonObject data = Json.safeObject(body);
                JsonObject sd = traverseObject(data, "streamingData");
                if (sd == null || sd.size() == 0) continue;

                data.addProperty("_client_name", clientName);
                if (clientUa != null) data.addProperty("_client_ua", clientUa);
                JsonObject info = new JsonObject();
                info.addProperty("clientNameId", clientNameId(clientName));
                info.addProperty("clientName", clientName);
                info.addProperty("clientVersion", optString(client, "clientVersion", null));
                info.addProperty("userAgent", clientUa);
                info.addProperty("deviceMake", optString(client, "deviceMake", null));
                info.addProperty("deviceModel", optString(client, "deviceModel", null));
                if (client.has("androidSdkVersion")) {
                    info.addProperty("androidSdkVersion", client.get("androidSdkVersion").getAsLong());
                }
                info.addProperty("osName", optString(client, "osName", null));
                info.addProperty("osVersion", optString(client, "osVersion", null));
                info.addProperty("hl", optString(client, "hl", "en"));
                info.addProperty("gl", optString(client, "gl", "US"));
                if (visitorData != null) info.addProperty("visitorData", visitorData);
                data.add("_client_info", info);
                results.add(data);
            } catch (Throwable ignored) {
                // One failing client must not abort the others.
            }
        }
        return results;
    }

    /* ------------------------------------------------------------------ */
    /* formats                                                            */
    /* ------------------------------------------------------------------ */

    private void extractFormats(List<JsonObject> responses, String playerUrl, Extracted out) {
        Set<String> seenDirect = new HashSet<>();
        Set<String> seenSabr = new HashSet<>();
        // Keep watch-page metadata, but only accept SABR representations from the TVHTML5
        // player response. Mixing WEB/ANDROID entries here breaks the TVHTML5-bound session.
        for (JsonObject response : responses) {
            if (response == null) continue;
            if (!"TVHTML5".equals(optString(response, "_client_name", ""))) continue;
            JsonObject sd = traverseObject(response, "streamingData");
            if (sd == null) continue;
            List<JsonObject> rawList = new ArrayList<>();
            rawList.addAll(arrayObjects(sd, "formats"));
            rawList.addAll(arrayObjects(sd, "adaptiveFormats"));
            String clientName = optString(response, "_client_name", null);
            String clientUa = optString(response, "_client_ua", null);
            JsonObject clientInfo = traverseObject(response, "_client_info");
            String serverAbrUrl = optString(sd, "serverAbrStreamingUrl", null);
            String ustreamerConfig = traverseString(response, "playerConfig", "mediaCommonConfig",
                    "mediaUstreamerRequestConfig", "videoPlaybackUstreamerConfig");

            for (JsonObject raw : rawList) {
                String cipher = optString(raw, "signatureCipher", optString(raw, "cipher", null));
                String directKey = clientName + "|" + optString(raw, "itag", "")
                        + "|" + optString(raw, "url", cipher == null ? optString(raw, "mimeType", "") : cipher);
                if (!seenDirect.contains(directKey)) {
                    seenDirect.add(directKey);
                    YTFormat item = normalizeFormat(raw, playerUrl, clientName, clientUa);
                    if (item != null && !TextUtils.isEmpty(item.url)) out.formats.add(item);
                }
                if (!TextUtils.isEmpty(serverAbrUrl) && !TextUtils.isEmpty(ustreamerConfig)) {
                    String sabrKey = clientName + "|" + optString(raw, "itag", "")
                            + "|" + optString(raw, "mimeType", "") + "|" + optString(raw, "xtags", "");
                    if (!seenSabr.contains(sabrKey)) {
                        seenSabr.add(sabrKey);
                        YTFormat sabrItem = normalizeSabrFormat(raw, serverAbrUrl, ustreamerConfig,
                                clientName, clientUa, clientInfo);
                        if (sabrItem != null) out.sabrFormats.add(sabrItem);
                    }
                }
            }
        }
        // SABR segments share native boundaries with the matching direct representation. Keeping
        // its WebM Cues / MP4 sidx location lets us build a real SegmentTimeline without
        // downloading any media.
        Map<String, YTFormat> directByKey = new HashMap<>();
        for (YTFormat item : out.formats) {
            if (item.indexRange == null || TextUtils.isEmpty(item.url)) continue;
            String key = item.client.toUpperCase(Locale.US) + "|" + item.itag;
            if (!directByKey.containsKey(key)) directByKey.put(key, item);
        }
        for (YTFormat item : out.sabrFormats) {
            YTFormat direct = directByKey.get(item.client.toUpperCase(Locale.US) + "|" + item.itag);
            if (direct == null) continue;
            YTFormat.IndexSource source = new YTFormat.IndexSource();
            source.url = direct.url;
            source.headers = new HashMap<>(direct.headers);
            source.indexRange = direct.indexRange;
            source.initRange = direct.initRange;
            source.contentLength = direct.contentLength;
            item.indexSource = source;
        }
    }

    private YTFormat normalizeFormat(JsonObject fmt, String playerUrl, String clientName, String clientUa) {
        String mediaUrl = optString(fmt, "url", null);
        if (mediaUrl == null) {
            String cipher = optString(fmt, "signatureCipher", optString(fmt, "cipher", null));
            if (cipher != null) mediaUrl = decryptSignatureCipher(cipher, playerUrl);
        }
        if (TextUtils.isEmpty(mediaUrl)) return null;
        mediaUrl = syncNParam(mediaUrl);
        String token = clientName == null ? null : poToken(clientName);
        if (!TextUtils.isEmpty(token)) {
            mediaUrl = mediaUrl + (mediaUrl.contains("?") ? "&" : "?") + "pot=" + Uri.encode(token);
        }

        String mime = optString(fmt, "mimeType", "");
        String codecs = search(RE_CODECS, mime);
        if (codecs == null) codecs = "";
        boolean hasAudio = mime.startsWith("audio/") || containsAny(codecs, "mp4a", "opus", "vorbis");
        boolean hasVideo = mime.startsWith("video/") || containsAny(codecs, "avc", "vp9", "av01", "h264");

        YTFormat item = new YTFormat();
        item.itag = (int) optLong(fmt, "itag", 0);
        item.url = mediaUrl;
        item.mimeType = mime;
        item.client = clientName == null ? "" : clientName;
        item.ext = mime.contains("mp4") ? "mp4" : mime.contains("webm") ? "webm" : "unknown";
        item.width = (int) optLong(fmt, "width", 0);
        item.height = (int) optLong(fmt, "height", 0);
        item.fps = (int) optLong(fmt, "fps", 0);
        item.bitrate = optLong(fmt, "bitrate", optLong(fmt, "averageBitrate", 0));
        item.contentLength = optString(fmt, "contentLength", null);
        item.initRange = YTFormat.range(fmt.get("initRange"));
        item.indexRange = YTFormat.range(fmt.get("indexRange"));
        item.codecs = codecs;
        item.quality = optString(fmt, "qualityLabel", optString(fmt, "quality", ""));
        item.colorInfo = traverseObject(fmt, "colorInfo");
        item.vcodec = hasVideo ? codecs : "none";
        item.acodec = hasAudio ? codecs : "none";
        if (clientUa != null) item.headers.put("User-Agent", clientUa);
        return item;
    }

    private YTFormat normalizeSabrFormat(JsonObject fmt, String serverAbrUrl, String ustreamerConfig,
                                         String clientName, String clientUa, JsonObject clientInfoJson) {
        String mime = optString(fmt, "mimeType", "");
        String codecs = search(RE_CODECS, mime);
        if (codecs == null) codecs = "";
        boolean hasAudio = mime.startsWith("audio/") || containsAny(codecs, "mp4a", "opus", "vorbis");
        boolean hasVideo = mime.startsWith("video/") || containsAny(codecs, "avc", "vp9", "vp09", "av01", "h264");
        // Muxed formats have no SABR representation.
        if (hasAudio && hasVideo) return null;
        int itag = (int) optLong(fmt, "itag", 0);
        if (itag == 0) return null;

        YTFormat item = new YTFormat();
        item.itag = itag;
        item.url = serverAbrUrl.replace(".c.youtube.com/videoplayback", ".googlevideo.com/videoplayback");
        item.protocol = "sabr";
        item.mimeType = mime;
        item.client = clientName == null ? "" : clientName;
        item.ext = mime.contains("mp4") ? "mp4" : mime.contains("webm") ? "webm" : "unknown";
        item.width = (int) optLong(fmt, "width", 0);
        item.height = (int) optLong(fmt, "height", 0);
        item.fps = (int) optLong(fmt, "fps", 0);
        item.bitrate = optLong(fmt, "bitrate", optLong(fmt, "averageBitrate", 0));
        item.contentLength = optString(fmt, "contentLength", null);
        item.codecs = codecs;
        item.quality = optString(fmt, "qualityLabel", optString(fmt, "quality", ""));
        item.colorInfo = traverseObject(fmt, "colorInfo");
        item.vcodec = hasVideo ? codecs : "none";
        item.acodec = hasAudio ? codecs : "none";
        if (clientUa != null) item.headers.put("User-Agent", clientUa);

        YTSabr.Config cfg = new YTSabr.Config();
        cfg.serverAbrStreamingUrl = serverAbrUrl;
        cfg.videoPlaybackUstreamerConfig = ustreamerConfig;
        cfg.clientName = clientName;
        cfg.clientInfo = toClientInfo(clientInfoJson, clientName, clientUa);
        cfg.poToken = clientName == null ? null : poToken(clientName);
        cfg.itag = itag;
        cfg.xtags = optString(fmt, "xtags", null);
        cfg.lastModified = optString(fmt, "lastModified", null);
        cfg.targetDurationSec = optLong(fmt, "targetDurationSec", 0);
        item.sabrConfig = cfg;
        return item;
    }

    private YTSabr.ClientInfo toClientInfo(JsonObject json, String clientName, String clientUa) {
        YTSabr.ClientInfo info = new YTSabr.ClientInfo();
        info.clientName = clientName;
        info.userAgent = clientUa;
        info.clientNameId = clientNameId(clientName);
        if (json == null) return info;
        info.hl = optString(json, "hl", "en");
        info.gl = optString(json, "gl", "US");
        info.deviceMake = optString(json, "deviceMake", null);
        info.deviceModel = optString(json, "deviceModel", null);
        info.visitorData = optString(json, "visitorData", null);
        String ua = optString(json, "userAgent", clientUa);
        if (ua != null) info.userAgent = ua;
        if (json.has("clientNameId")) info.clientNameId = (int) optLong(json, "clientNameId", info.clientNameId);
        info.clientVersion = optString(json, "clientVersion", null);
        info.osName = optString(json, "osName", null);
        info.osVersion = optString(json, "osVersion", null);
        if (json.has("androidSdkVersion")) info.androidSdkVersion = optLong(json, "androidSdkVersion", 0);
        return info;
    }

    /* ------------------------------------------------------------------ */
    /* signatures                                                         */
    /* ------------------------------------------------------------------ */

    private String decryptSignatureCipher(String cipher, String playerUrl) {
        Map<String, String> data = parseQuery(cipher);
        String mediaUrl = data.get("url");
        String sig = data.get("s");
        String sp = data.containsKey("sp") ? data.get("sp") : "sig";
        if (TextUtils.isEmpty(mediaUrl)) return "";
        if (!TextUtils.isEmpty(sig)) {
            String decoded = decryptSig(sig, playerUrl);
            mediaUrl = mediaUrl + (mediaUrl.contains("?") ? "&" : "?") + sp + "=" + Uri.encode(decoded);
        }
        return mediaUrl;
    }

    private String decryptSig(String sig, String playerUrl) {
        String cacheKey = playerUrl == null ? "" : playerUrl;
        List<String[]> plan;
        if (sigPlanCache.containsKey(cacheKey)) {
            plan = sigPlanCache.get(cacheKey);
        } else {
            plan = extractSigPlan(playerCode(playerUrl));
            sigPlanCache.put(cacheKey, plan);
        }
        if (plan == null || plan.isEmpty()) return sig;
        List<Character> arr = new ArrayList<>();
        for (char c : sig.toCharArray()) arr.add(c);
        for (String[] step : plan) {
            String op = step[0];
            int arg = 0;
            try {
                arg = Integer.parseInt(step[1]);
            } catch (Throwable ignored) {
                // A malformed argument degrades to 0, matching the reference behaviour.
            }
            if ("reverse".equals(op)) {
                Collections.reverse(arr);
            } else if ("slice".equals(op) || "splice".equals(op)) {
                if (arg >= 0 && arg <= arr.size()) arr = new ArrayList<>(arr.subList(arg, arr.size()));
            } else if ("swap".equals(op) && !arr.isEmpty()) {
                int j = arg % arr.size();
                Character tmp = arr.get(0);
                arr.set(0, arr.get(j));
                arr.set(j, tmp);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (Character c : arr) sb.append(c);
        return sb.toString();
    }

    /**
     * Keeps the {@code /n/} path segment in sync with the {@code n} query parameter.
     *
     * <p>A query-only {@code n} is normal for current YouTube URLs and needs no rewriting.
     */
    private String syncNParam(String mediaUrl) {
        try {
            Uri uri = Uri.parse(mediaUrl);
            String nValue = uri.getQueryParameter("n");
            if (TextUtils.isEmpty(nValue)) return mediaUrl;
            String path = uri.getPath();
            if (path == null) return mediaUrl;
            Matcher m = safePattern("/n/([^/]+)").matcher(path);
            if (m.find() && !m.group(1).equals(nValue)) {
                return mediaUrl.replaceFirst("/n/" + Pattern.quote(m.group(1)), "/n/" + nValue);
            }
            return mediaUrl;
        } catch (Throwable e) {
            return mediaUrl;
        }
    }

    private String playerCode(String playerUrl) {
        if (TextUtils.isEmpty(playerUrl)) return "";
        if (playerCache.containsKey(playerUrl)) return playerCache.get(playerUrl);
        String url = playerUrl;
        if (url.startsWith("//")) url = "https:" + url;
        else if (url.startsWith("/")) url = "https://www.youtube.com" + url;
        String code = http.string(url);
        playerCache.put(playerUrl, code);
        return code;
    }

    private List<String[]> extractSigPlan(String code) {
        if (TextUtils.isEmpty(code)) return null;
        String name = null;
        for (String pattern : new String[]{
                "\\.sig\\|\\|([a-zA-Z0-9_$]+)\\(",
                "\"signature\",\\s*([a-zA-Z0-9_$]+)\\(",
                "([a-zA-Z0-9_$]+)=function\\(a\\)\\{a=a\\.split\\(\"\"\\);",
        }) {
            Matcher m = safePattern(pattern).matcher(code);
            if (m.find()) {
                name = m.group(1);
                break;
            }
        }
        if (name == null) return null;
        String body = extractJsFunctionBody(code, name);
        if (TextUtils.isEmpty(body)) return null;
        String helper = search(safePattern("([a-zA-Z0-9_$]+)\\.[a-zA-Z0-9_$]+\\(a,\\d+\\)"), body);
        Map<String, String> helperMap = helper == null ? new HashMap<>() : extractHelperObject(code, helper);
        List<String[]> plan = new ArrayList<>();
        for (String part : body.split(";")) {
            if (part.contains("reverse()")) {
                plan.add(new String[]{"reverse", "0"});
                continue;
            }
            Matcher m = safePattern("\\.slice\\((\\d+)\\)").matcher(part);
            if (m.find()) {
                plan.add(new String[]{"slice", m.group(1)});
                continue;
            }
            m = safePattern("\\.splice\\(0,(\\d+)\\)").matcher(part);
            if (m.find()) {
                plan.add(new String[]{"splice", m.group(1)});
                continue;
            }
            m = safePattern("([a-zA-Z0-9_$]+)\\.([a-zA-Z0-9_$]+)\\(a,(\\d+)\\)").matcher(part);
            if (m.find() && m.group(1).equals(helper)) {
                String op = helperMap.get(m.group(2));
                if (op != null) plan.add(new String[]{op, m.group(3)});
            }
        }
        return plan.isEmpty() ? null : plan;
    }

    private Map<String, String> extractHelperObject(String code, String name) {
        Map<String, String> result = new HashMap<>();
        if (TextUtils.isEmpty(name)) return result;
        Matcher m = safePattern("var\\s+" + Pattern.quote(name) + "=\\{(.+?)\\};", Pattern.DOTALL).matcher(code);
        if (!m.find()) {
            m = safePattern(Pattern.quote(name) + "=\\{(.+?)\\};", Pattern.DOTALL).matcher(code);
            if (!m.find()) return result;
        }
        Matcher fn = safePattern("([a-zA-Z0-9_$]+):function\\([a-z,]+\\)\\{(.*?)\\}", Pattern.DOTALL)
                .matcher(m.group(1));
        while (fn.find()) {
            String method = fn.group(1);
            String body = fn.group(2);
            if (body.contains(".reverse(")) result.put(method, "reverse");
            else if (body.contains(".splice(")) result.put(method, "splice");
            else if (body.contains(".slice(")) result.put(method, "slice");
            else if (body.contains("a[0]") && body.contains("length")) result.put(method, "swap");
        }
        return result;
    }

    /** Extracts a JS function body by brace matching, skipping string literals. */
    private String extractJsFunctionBody(String code, String name) {
        int start = -1;
        for (String pattern : new String[]{
                "function\\s+" + Pattern.quote(name) + "\\s*\\([^)]*\\)\\s*\\{",
                Pattern.quote(name) + "\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{",
                "var\\s+" + Pattern.quote(name) + "\\s*=\\s*function\\s*\\([^)]*\\)\\s*\\{",
        }) {
            Matcher m = safePattern(pattern).matcher(code);
            if (m.find()) {
                start = m.end() - 1;
                break;
            }
        }
        if (start < 0) return "";
        int depth = 0;
        char inStr = 0;
        boolean escape = false;
        for (int i = start; i < code.length(); i++) {
            char ch = code.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (ch == '\\') {
                escape = true;
                continue;
            }
            if (inStr != 0) {
                if (ch == inStr) inStr = 0;
                continue;
            }
            if (ch == '"' || ch == '\'' || ch == '`') {
                inStr = ch;
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) return code.substring(start + 1, i);
            }
        }
        return "";
    }

    /* ------------------------------------------------------------------ */
    /* track selection                                                    */
    /* ------------------------------------------------------------------ */

    /**
     * Codec preference: VP9/HDR > H264 > AV1.
     *
     * <p>AV1 is deliberately last so the huge itag 701/702 segments are never the default.
     */
    int videoCodecPriority(YTFormat item) {
        String mime = item.mimeType == null ? "" : item.mimeType.toLowerCase(Locale.US);
        String codecs = item.codecs == null ? "" : item.codecs.toLowerCase(Locale.US);
        if (mime.contains("vp9.2") || codecs.contains("vp09.02")) return 4;
        if (mime.contains("vp9") || codecs.contains("vp09")) return 3;
        if (codecs.contains("avc") || codecs.contains("h264")) return 2;
        if (codecs.contains("av01")) return 1;
        return 0;
    }

    boolean isHdrVideo(YTFormat item) {
        String mime = item.mimeType == null ? "" : item.mimeType.toLowerCase(Locale.US);
        String codecs = item.codecs == null ? "" : item.codecs.toLowerCase(Locale.US);
        if (mime.contains("vp9.2") || codecs.contains("vp09.02")) return true;
        JsonObject color = item.colorInfo;
        if (color == null) return false;
        if (color.has("hdrMetadataInfo") || color.has("hdrMetadata")) return true;
        String colorText = color.toString().toLowerCase(Locale.US);
        for (String marker : new String[]{"smpte2084", "arib-std-b67", "bt2020", "hdr10", "hlg", "pq"}) {
            if (colorText.contains(marker)) return true;
        }
        return false;
    }

    private boolean isRiskyBestVideo(YTFormat item) {
        return item.codecs != null && item.codecs.toLowerCase(Locale.US).contains("av01");
    }

    /** Picks the single best video-only track for a quality bucket. */
    YTFormat choosePlayable(List<YTFormat> formats, String quality) {
        List<YTFormat> allVideos = new ArrayList<>();
        for (YTFormat item : formats) {
            if (item.hasVideo() && !item.hasAudio()) allVideos.add(item);
        }
        List<YTFormat> candidates = new ArrayList<>(allVideos);
        if ("8k".equals(quality) || "8k_hdr".equals(quality)) {
            candidates = filterHeight(candidates, 4320, Integer.MAX_VALUE);
            List<YTFormat> byHdr = new ArrayList<>();
            for (YTFormat item : candidates) {
                if ("8k".equals(quality) ? !isHdrVideo(item) : isHdrVideo(item)) byHdr.add(item);
            }
            candidates = byHdr;
        } else if ("4k".equals(quality)) {
            candidates = filterHeight(candidates, 2160, 4320);
        } else if ("2k".equals(quality)) {
            candidates = filterHeight(candidates, 1440, 2160);
        } else if ("1080p".equals(quality)) {
            candidates = filterHeight(candidates, 1000, 1440);
        } else if ("best".equals(quality)) {
            List<YTFormat> safe = new ArrayList<>();
            for (YTFormat item : candidates) if (!isRiskyBestVideo(item)) safe.add(item);
            if (!safe.isEmpty()) candidates = safe;
        } else {
            candidates = filterHeight(candidates, 1080, Integer.MAX_VALUE);
        }
        if (candidates.isEmpty() && "best".equals(quality)) candidates = allVideos;
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> {
            int cmp = Integer.compare(videoCodecPriority(b), videoCodecPriority(a));
            if (cmp != 0) return cmp;
            cmp = Integer.compare(b.height, a.height);
            if (cmp != 0) return cmp;
            return Long.compare(b.bitrate, a.bitrate);
        });
        return candidates.get(0);
    }

    /** Returns one SDR and one HDR track for the requested quality bucket. */
    List<YTFormat> chooseVideoTracks(List<YTFormat> formats, String quality, String protocol) {
        List<YTFormat> videos = new ArrayList<>();
        for (YTFormat item : formats) {
            if (!item.hasVideo() || item.hasAudio()) continue;
            if (protocol != null && !protocol.equals(item.protocol)) continue;
            videos.add(item);
        }
        if ("best".equals(quality)) {
            List<YTFormat> capped = filterHeight(videos, 0, 2161);
            if (!capped.isEmpty()) videos = capped;
        } else if ("8k".equals(quality) || "8k_hdr".equals(quality)) {
            videos = filterHeight(videos, 4320, Integer.MAX_VALUE);
        } else if ("4k".equals(quality)) {
            videos = filterHeight(videos, 2160, 4320);
        } else if ("2k".equals(quality)) {
            videos = filterHeight(videos, 1440, 2160);
        } else if ("1080p".equals(quality)) {
            videos = filterHeight(videos, 1000, 1440);
        }
        List<YTFormat> sdr = new ArrayList<>();
        List<YTFormat> hdr = new ArrayList<>();
        for (YTFormat item : videos) {
            if (isHdrVideo(item)) hdr.add(item);
            else sdr.add(item);
        }
        java.util.Comparator<YTFormat> sortKey = (a, b) -> {
            int cmp = Integer.compare(b.height, a.height);
            if (cmp != 0) return cmp;
            cmp = Integer.compare(videoCodecPriority(b), videoCodecPriority(a));
            if (cmp != 0) return cmp;
            return Long.compare(b.bitrate, a.bitrate);
        };
        sdr.sort(sortKey);
        hdr.sort(sortKey);
        List<YTFormat> tracks = new ArrayList<>();
        if (!sdr.isEmpty()) {
            YTFormat item = sdr.get(0).copy();
            item.trackName = "SDR";
            item.isHdr = false;
            tracks.add(item);
        }
        if (!hdr.isEmpty()) {
            YTFormat item = hdr.get(0).copy();
            item.trackName = "HDR";
            item.isHdr = true;
            tracks.add(item);
        }
        if (tracks.isEmpty()) {
            YTFormat item = choosePlayable(videos, quality);
            if (item != null) {
                YTFormat copy = item.copy();
                copy.isHdr = isHdrVideo(copy);
                copy.trackName = copy.isHdr ? "HDR" : "SDR";
                tracks.add(copy);
            }
        }
        return tracks;
    }

    List<YTFormat> chooseVideoTracks(List<YTFormat> formats, String quality) {
        return chooseVideoTracks(formats, quality, null);
    }

    YTFormat chooseAudio(List<YTFormat> formats, String protocol, String sameClient) {
        List<YTFormat> candidates = new ArrayList<>();
        for (YTFormat item : formats) {
            if (!item.hasAudio() || item.hasVideo()) continue;
            if (protocol != null && !protocol.equals(item.protocol)) continue;
            candidates.add(item);
        }
        if (!TextUtils.isEmpty(sameClient)) {
            List<YTFormat> same = new ArrayList<>();
            for (YTFormat item : candidates) {
                if (sameClient.equalsIgnoreCase(item.client)) same.add(item);
            }
            if (!same.isEmpty()) candidates = same;
        }
        if (candidates.isEmpty()) return null;
        candidates.sort((a, b) -> {
            int cmp = Integer.compare("mp4".equals(b.ext) ? 1 : 0, "mp4".equals(a.ext) ? 1 : 0);
            if (cmp != 0) return cmp;
            return Long.compare(b.bitrate, a.bitrate);
        });
        return candidates.get(0);
    }

    private static List<YTFormat> filterHeight(List<YTFormat> items, int min, int maxExclusive) {
        List<YTFormat> out = new ArrayList<>();
        for (YTFormat item : items) {
            if (item.height >= min && item.height < maxExclusive) out.add(item);
        }
        return out;
    }

    /* ------------------------------------------------------------------ */
    /* json helpers                                                       */
    /* ------------------------------------------------------------------ */

    JsonObject extractYtcfg(String text) {
        Matcher m = RE_YTCFG.matcher(text == null ? "" : text);
        if (!m.find()) return new JsonObject();
        return Json.safeObject(m.group(1));
    }

    /** Extracts the first balanced JSON object following a marker, skipping string literals. */
    JsonObject extractJsonAfter(String text, String marker) {
        if (text == null) return null;
        int pos = text.indexOf(marker);
        if (pos < 0) return null;
        int start = text.indexOf('{', pos);
        if (start < 0) return null;
        int depth = 0;
        boolean inStr = false;
        boolean escape = false;
        for (int i = start; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (escape) {
                escape = false;
                continue;
            }
            if (ch == '\\') {
                escape = true;
                continue;
            }
            if (inStr) {
                if (ch == '"') inStr = false;
                continue;
            }
            if (ch == '"') {
                inStr = true;
                continue;
            }
            if (ch == '{') {
                depth++;
            } else if (ch == '}') {
                depth--;
                if (depth == 0) return Json.safeObject(text.substring(start, i + 1));
            }
        }
        return null;
    }

    String extractPlayerUrl(String text) {
        for (String pattern : new String[]{
                "\"jsUrl\":\"([^\"]+)\"",
                "\"PLAYER_JS_URL\":\"([^\"]+)\"",
                "(/s/player/[^\"\\\\]+/base\\.js)",
        }) {
            String value = search(safePattern(pattern), text);
            if (value != null) return value.replace("\\/", "/");
        }
        return "";
    }

    static String search(Pattern pattern, String text) {
        if (text == null) return null;
        Matcher m = pattern.matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static JsonObject defaultContext() {
        return Json.safeObject("{\"client\":{\"clientName\":\"WEB\","
                + "\"clientVersion\":\"2.20240310.01.00\",\"hl\":\"en\",\"gl\":\"US\"}}");
    }

    private static boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String needle : needles) if (text.contains(needle)) return true;
        return false;
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null) return out;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx <= 0) continue;
            String key = Uri.decode(pair.substring(0, idx));
            String value = Uri.decode(pair.substring(idx + 1));
            if (!out.containsKey(key)) out.put(key, value);
        }
        return out;
    }

    static JsonObject traverseObject(JsonObject root, String... path) {
        JsonElement cur = root;
        for (String key : path) {
            if (cur == null || !cur.isJsonObject()) return null;
            cur = cur.getAsJsonObject().get(key);
        }
        return cur != null && cur.isJsonObject() ? cur.getAsJsonObject() : null;
    }

    static String traverseString(JsonObject root, String... path) {
        JsonElement cur = root;
        for (String key : path) {
            if (cur == null || !cur.isJsonObject()) return null;
            cur = cur.getAsJsonObject().get(key);
        }
        if (cur == null || !cur.isJsonPrimitive()) return null;
        return cur.getAsString();
    }

    static List<JsonObject> arrayObjects(JsonObject root, String key) {
        List<JsonObject> out = new ArrayList<>();
        if (root == null || !root.has(key) || !root.get(key).isJsonArray()) return out;
        JsonArray array = root.getAsJsonArray(key);
        for (JsonElement element : array) {
            if (element != null && element.isJsonObject()) out.add(element.getAsJsonObject());
        }
        return out;
    }

    static String optString(JsonObject obj, String key, String fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsString();
        } catch (Throwable e) {
            return fallback;
        }
    }

    static long optLong(JsonObject obj, String key, long fallback) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return fallback;
        try {
            return obj.get(key).getAsLong();
        } catch (Throwable e) {
            try {
                return Long.parseLong(obj.get(key).getAsString().trim());
            } catch (Throwable ignored) {
                return fallback;
            }
        }
    }

    static boolean optBool(JsonObject obj, String key) {
        if (obj == null || !obj.has(key) || obj.get(key).isJsonNull()) return false;
        try {
            return obj.get(key).getAsBoolean();
        } catch (Throwable e) {
            return false;
        }
    }
}