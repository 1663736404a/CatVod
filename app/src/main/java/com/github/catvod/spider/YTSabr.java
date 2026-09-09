package com.github.catvod.spider;

import android.text.TextUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Builders for {@code VideoPlaybackAbrRequest} and its nested messages.
 *
 * <p>Field numbers follow yt-dlp's {@code _proto/videostreaming} definitions.
 */
final class YTSabr {

    /* UMP part ids */
    static final int MEDIA_HEADER = 20;
    static final int MEDIA = 21;
    static final int MEDIA_END = 22;
    static final int NEXT_REQUEST_POLICY = 35;
    static final int SABR_REDIRECT = 43;
    static final int SABR_ERROR = 44;
    static final int RELOAD_PLAYER_RESPONSE = 46;
    static final int STREAM_PROTECTION_STATUS = 58;
    /**
     * Mid-session context update. Failing to echo it back makes the server stop sending the
     * video track after 20-40s while audio keeps flowing, i.e. frozen picture with sound.
     */
    static final int SABR_CONTEXT_UPDATE = 57;
    static final int SABR_CONTEXT_SENDING_POLICY = 48;

    private YTSabr() {
    }

    /** Client identity echoed back to the SABR endpoint. */
    static final class ClientInfo {
        String hl = "en";
        String gl = "US";
        String deviceMake;
        String deviceModel;
        String visitorData;
        String userAgent;
        int clientNameId = 1;
        String clientName;
        String clientVersion;
        String osName;
        String osVersion;
        Long androidSdkVersion;
    }

    /** Session-bound SABR parameters taken from one player response. */
    static final class Config {
        String serverAbrStreamingUrl;
        String videoPlaybackUstreamerConfig;
        String clientName;
        ClientInfo clientInfo;
        String poToken;
        /** True when the player response was fetched with an OAuth bearer token. */
        boolean authenticated;
        /** Server-issued cookie from {@code streamingData.playbackCookie}, when present. */
        byte[] playbackCookie;
        /** All SABR video formats of the player response; feeds the preferred-format lists. */
        List<YTFormat> videoPool;
        /** All SABR audio formats of the player response. */
        List<YTFormat> audioPool;
        int itag;
        String xtags;
        String lastModified;
        double targetDurationSec;
        boolean preferHdr;
    }

    /**
     * Client behavior fingerprint the Cobalt client reports in every ClientAbrState. Missing these
     * fields is what flips STREAM_PROTECTION_STATUS (UMP part 58) to a non-OK value ~8 requests in,
     * after which the server answers 200 but never serves media again (the "60s cutoff").
     */
    static final class AbrEnv {
        long playerTimeMs;
        boolean hasVideo;
        boolean hasAudio;
        boolean hdrVideo;
        boolean drcAudio;
        long bandwidth;
        long sessionElapsedMs;
        long sinceAccessMs;
        long seekAgeMs;
        boolean live;
        /** True for the session-opening request; suppresses clientAbrState field 29. */
        boolean init;
    }

    /** A single {@code SabrContextUpdate} the server asked us to replay. */
    static final class SabrContext {
        Long scope;
        byte[] value;
    }

    static byte[] buildFormatId(Integer itag, String lmt, String xtags) {
        byte[] p = YTProto.EMPTY;
        if (itag != null && itag != 0) p = YTProto.concat(p, YTProto.pbInt(1, itag.longValue()));
        if (lmt != null && !lmt.isEmpty()) {
            try {
                p = YTProto.concat(p, YTProto.pbInt(2, Long.parseLong(lmt.trim())));
            } catch (Throwable ignored) {
                // A non-numeric lastModified is simply omitted.
            }
        }
        if (xtags != null && !xtags.isEmpty()) p = YTProto.concat(p, YTProto.pbStr(3, xtags));
        return p;
    }

    static byte[] buildFormatId(Integer itag) {
        return buildFormatId(itag, null, null);
    }

    /** BufferedRange: format_id=1, start_time_ms=2, duration_ms=3, start/end_segment_index=4/5. */
    static byte[] buildBufferedRange(byte[] formatId, long startMs, long durationMs, Long startSeq, Long endSeq) {
        if (formatId == null || formatId.length == 0) return YTProto.EMPTY;
        byte[] p = YTProto.pbMsg(1, formatId);
        p = YTProto.concat(p, YTProto.pbInt(2, Math.max(0, startMs)));
        p = YTProto.concat(p, YTProto.pbInt(3, Math.max(0, durationMs)));
        if (startSeq != null) p = YTProto.concat(p, YTProto.pbInt(4, startSeq));
        if (endSeq != null) p = YTProto.concat(p, YTProto.pbInt(5, endSeq));
        return p;
    }

    static byte[] buildClientInfo(ClientInfo c) {
        if (c == null) c = new ClientInfo();
        byte[] p = YTProto.EMPTY;
        p = YTProto.concat(p, YTProto.pbStr(1, c.hl == null ? "en" : c.hl));
        p = YTProto.concat(p, YTProto.pbStr(2, c.gl == null ? "US" : c.gl));
        p = YTProto.concat(p, YTProto.pbStr(12, c.deviceMake));
        p = YTProto.concat(p, YTProto.pbStr(13, c.deviceModel));
        p = YTProto.concat(p, YTProto.pbStr(14, c.visitorData));
        p = YTProto.concat(p, YTProto.pbStr(15, c.userAgent));
        p = YTProto.concat(p, YTProto.pbInt(16, c.clientNameId == 0 ? 1 : c.clientNameId));
        p = YTProto.concat(p, YTProto.pbStr(17, c.clientVersion));
        p = YTProto.concat(p, YTProto.pbStr(18, c.osName));
        p = YTProto.concat(p, YTProto.pbStr(19, c.osVersion));
        if (c.androidSdkVersion != null) p = YTProto.concat(p, YTProto.pbInt(64, c.androidSdkVersion));
        return p;
    }

    /**
     * ClientAbrState, byte-for-byte the shape pg.jar's Cobalt client sends: network type 17,
     * viewport 18/19, sticky height 16/21, bandwidth 23, player time 28, seek age 29, padding 34,
     * session uptime 36, time since last request 39, DRC 46, fixed hints 57/58/59/68/71/73/80/85,
     * viewport message 72, playback authorization 79.
     *
     * <p>Field 79 is the important one: it declares which track types the client is authorized to
     * play ({@code AuthorizedFormat{type=1 video, 2 audio, flag}}). The previous 5-field state was
     * answered with media for a grace period, then STREAM_PROTECTION_STATUS flipped and the stream
     * went silent at ~60s of playback.
     */
    static byte[] buildClientAbrState(AbrEnv env) {
        int[] viewport = readViewport();
        byte[] p = YTProto.pbInt(17, readNetworkType());
        p = YTProto.concat(p, YTProto.pbInt(18, viewport[0]));
        p = YTProto.concat(p, YTProto.pbInt(19, viewport[1]));
        // No quality pinning exists here, so sticky height (field 16) is omitted but 21 is still
        // written as zero, matching Cobalt.
        p = YTProto.concat(p, YTProto.pbInt(21, 0));
        p = YTProto.concat(p, YTProto.pbIntNZ(23, env.bandwidth));
        p = YTProto.concat(p, YTProto.pbInt(28, Math.max(0, env.playerTimeMs)));
        p = YTProto.concat(p, YTProto.pbIntNZ(29, env.init ? 0 : env.seekAgeMs));
        p = YTProto.concat(p, YTProto.pbInt(34, 5));
        p = YTProto.concat(p, YTProto.pbIntNZ(36, env.sessionElapsedMs));
        p = YTProto.concat(p, YTProto.pbIntNZ(39, env.sinceAccessMs));
        p = YTProto.concat(p, YTProto.pbIntNZ(46, env.drcAudio ? 1 : 0));
        p = YTProto.concat(p, YTProto.pbInt(57, 2000));
        p = YTProto.concat(p, YTProto.pbInt(58, 0));
        p = YTProto.concat(p, YTProto.pbInt(59, resolveAv1Threshold(viewport)));
        p = YTProto.concat(p, YTProto.pbInt(68, 0));
        p = YTProto.concat(p, YTProto.pbInt(71, 1));
        byte[] vp = YTProto.pbInt(1, 0);
        vp = YTProto.concat(vp, YTProto.pbInt(2, viewport[1]));
        vp = YTProto.concat(vp, YTProto.pbInt(3, Math.min(480, viewport[1])));
        vp = YTProto.concat(vp, YTProto.pbInt(4, 0));
        vp = YTProto.concat(vp, YTProto.pbInt(5, viewport[0]));
        vp = YTProto.concat(vp, YTProto.pbInt(6, 0));
        p = YTProto.concat(p, YTProto.pbMsg(72, vp));
        p = YTProto.concat(p, YTProto.pbInt(73, 2));
        p = YTProto.concat(p, YTProto.pbInt(76, 0));
        byte[] auth = buildPlaybackAuthorization(env);
        if (auth.length > 0) p = YTProto.concat(p, YTProto.pbBytes(79, auth));
        p = YTProto.concat(p, YTProto.pbInt(80, 1));
        p = YTProto.concat(p, YTProto.pbInt(85, 1));
        return p;
    }

    /** PlaybackAuthorization: repeated authorized_format(1){type(1), flag(2)}. */
    private static byte[] buildPlaybackAuthorization(AbrEnv env) {
        byte[] p = YTProto.EMPTY;
        if (env.hasVideo) {
            p = YTProto.concat(p, YTProto.pbMsg(1, encodeAuthorizedFormat(1, env.hdrVideo)));
        }
        if (env.hasAudio) {
            p = YTProto.concat(p, YTProto.pbMsg(1, encodeAuthorizedFormat(2, false)));
            if (env.drcAudio) {
                p = YTProto.concat(p, YTProto.pbMsg(1, encodeAuthorizedFormat(2, true)));
            }
        }
        return p;
    }

    private static byte[] encodeAuthorizedFormat(int type, boolean flag) {
        byte[] p = YTProto.pbInt(1, type);
        p = YTProto.concat(p, YTProto.pbInt(2, flag ? 1 : 0));
        return p;
    }

    /** Cobalt defaults to a 1080p viewport; a real display wins when one is attached. */
    private static int[] readViewport() {
        int[] out = {1920, 1080};
        try {
            android.content.Context context = Init.context();
            if (context == null) return out;
            Object service = context.getSystemService("window");
            if (!(service instanceof android.view.WindowManager)) return out;
            android.view.Display display = ((android.view.WindowManager) service).getDefaultDisplay();
            if (display == null) return out;
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
                out[0] = metrics.widthPixels;
                out[1] = metrics.heightPixels;
            }
        } catch (Throwable ignored) {
        }
        return out;
    }

    /** pg.jar's Cobalt values: WIFI/DEFAULT 116, ETHERNET 115, CELLULAR 3. */
    private static int readNetworkType() {
        try {
            android.content.Context context = Init.context();
            if (context == null) return 116;
            Object service = context.getSystemService("connectivity");
            if (!(service instanceof android.net.ConnectivityManager)) return 116;
            android.net.NetworkInfo info = ((android.net.ConnectivityManager) service).getActiveNetworkInfo();
            if (info == null || !info.isConnected()) return 116;
            int type = info.getType();
            if (type == android.net.ConnectivityManager.TYPE_WIFI) return 116;
            if (type == android.net.ConnectivityManager.TYPE_ETHERNET) return 115;
            if (type == android.net.ConnectivityManager.TYPE_MOBILE) return 3;
            return 116;
        } catch (Throwable ignored) {
            return 116;
        }
    }

    private static int resolveAv1Threshold(int[] viewport) {
        long maxDim = Math.max(viewport[0], viewport[1]);
        if (maxDim <= 0) maxDim = 1080;
        long value = maxDim * 8192 / 2160;
        return (int) Math.max(4096, Math.min(8192, value));
    }

    /**
     * StreamerContext: client_info=1, po_token=2, playback_cookie=3, cobalt_streamer=4,
     * sabr_contexts=5.
     *
     * <p>Two shapes, exactly like pg.jar's {@code encodeStreamerContext}:
     * <ul>
     * <li>poToken line (anonymous + BotGuard): full client identity and the token in field 2 —
     * the shape this app has shipped and that plays past 60s.</li>
     * <li>no-token line (OAuth): Cobalt-flavored client info, with a Cobalt streamer id (field 4)
     * on the session-opening request and the playback cookie (field 3) on every later one.</li>
     * </ul>
     */
    static byte[] buildStreamerContext(ClientInfo clientInfo, String poToken, byte[] playbackCookie,
                                       Map<Integer, SabrContext> sabrContexts,
                                       Set<Integer> unsentContexts, boolean init) {
        byte[] p;
        if (poToken != null && !poToken.isEmpty()) {
            p = YTProto.pbMsg(1, buildClientInfo(clientInfo));
            byte[] pot = YTProto.b64urlDecode(poToken);
            if (pot != null && pot.length > 0) p = YTProto.concat(p, YTProto.pbBytes(2, pot));
            if (playbackCookie != null && playbackCookie.length > 0) {
                p = YTProto.concat(p, YTProto.pbBytes(3, playbackCookie));
            }
        } else {
            p = YTProto.pbMsg(1, buildClientInfoCobalt(clientInfo));
            if (init) {
                p = YTProto.concat(p, YTProto.pbBytes(4, buildCobaltStreamer()));
            } else if (playbackCookie != null && playbackCookie.length > 0) {
                p = YTProto.concat(p, YTProto.pbBytes(3, playbackCookie));
            }
        }
        if (sabrContexts != null && !sabrContexts.isEmpty()) {
            // SabrContextUpdate: type=1, scope=2, value=3
            for (Map.Entry<Integer, SabrContext> entry : new TreeMap<>(sabrContexts).entrySet()) {
                SabrContext ctx = entry.getValue();
                if (ctx == null || ctx.value == null || ctx.value.length == 0) continue;
                byte[] body = YTProto.pbInt(1, entry.getKey().longValue());
                if (ctx.scope != null) body = YTProto.concat(body, YTProto.pbInt(2, ctx.scope));
                body = YTProto.concat(body, YTProto.pbBytes(3, ctx.value));
                p = YTProto.concat(p, YTProto.pbMsg(5, body));
            }
        }
        // googlevideo sends StreamerContext.unsentSabrContexts as packed int32 field 6.
        // Tell the server which previously announced contexts are intentionally omitted.
        if (unsentContexts != null && !unsentContexts.isEmpty()) {
            byte[] packed = YTProto.EMPTY;
            for (Integer type : new TreeSet<>(unsentContexts)) {
                packed = YTProto.concat(packed, YTProto.varint(type == null ? 0 : type));
            }
            p = YTProto.concat(p, YTProto.pbBytes(6, packed));
        }
        return p;
    }

    /**
     * The Cobalt client info pg.jar writes in its streamerContext: hl, device make/model, a Cobalt
     * client name (7 — not the InnerTube TVHTML5 id), client version, and the OS. Notably there is
     * no visitorData and no user agent here; the session is identified by the cookie instead.
     */
    private static byte[] buildClientInfoCobalt(ClientInfo c) {
        String make = c == null || c.deviceMake == null || c.deviceMake.isEmpty()
                ? buildMake() : c.deviceMake;
        String model = c == null || c.deviceModel == null || c.deviceModel.isEmpty()
                ? buildModel() : c.deviceModel;
        String version = c == null ? null : c.clientVersion;
        if (version == null || version.isEmpty() || !Character.isDigit(version.charAt(0))) {
            version = "7.20260707.07.00";
        }
        byte[] p = YTProto.pbStr(1, c == null || c.hl == null || c.hl.isEmpty() ? "zh_CN" : c.hl);
        p = YTProto.concat(p, YTProto.pbStr(12, make));
        p = YTProto.concat(p, YTProto.pbStr(13, model));
        p = YTProto.concat(p, YTProto.pbInt(16, 7));
        p = YTProto.concat(p, YTProto.pbStr(17, version));
        p = YTProto.concat(p, YTProto.pbStr(18, "Android"));
        p = YTProto.concat(p, YTProto.pbStr(19, osRelease()));
        return p;
    }

    /** {@code Build.MANUFACTURER} with pg.jar's "Xiaomi" fallback. */
    private static String buildMake() {
        try {
            String value = android.os.Build.MANUFACTURER;
            return value == null || value.isEmpty() ? "Xiaomi" : value;
        } catch (Throwable ignored) {
            return "Xiaomi";
        }
    }

    /** {@code Build.MODEL} with pg.jar's "MI 6" fallback. */
    private static String buildModel() {
        try {
            String value = android.os.Build.MODEL;
            return value == null || value.isEmpty() ? "MI 6" : value;
        } catch (Throwable ignored) {
            return "MI 6";
        }
    }

    private static String osRelease() {
        try {
            String value = android.os.Build.VERSION.RELEASE;
            return value == null || value.isEmpty() ? "15" : value;
        } catch (Throwable ignored) {
            return "15";
        }
    }

    /** Cobalt streamer id pg.jar sends in streamerContext field 4: {3:{1:6}, 5:{1:{2:"zh"}}}. */
    private static byte[] buildCobaltStreamer() {
        byte[] streamerType = YTProto.pbInt(1, 6);
        byte[] language = YTProto.pbStr(2, "zh");
        byte[] languageWrap = YTProto.pbMsg(1, language);
        byte[] p = YTProto.pbBytes(3, streamerType);
        p = YTProto.concat(p, YTProto.pbBytes(5, languageWrap));
        return p;
    }

    /**
     * pg.jar R.G.d keeps the TV client version current by scraping the /tv page (six hour cache,
     * constant fallback), because a stale version is one of the signals the player endpoint
     * started rejecting; the hardcoded value here is only the last resort.
     */
    static String cobaltVersion() {
        return YoutubeOAuth.tvClientVersion();
    }

    /** Cobalt/Starboard identity used by pg.jar's authenticated TV client (R.G.a). */
    static String cobaltUserAgent() {
        try {
            // pg.jar prefers the release string ("Android 11") over the sdk int.
            String androidVersion = android.os.Build.VERSION.RELEASE;
            if (TextUtils.isEmpty(androidVersion)) {
                androidVersion = String.valueOf(android.os.Build.VERSION.SDK_INT);
            }
            String product = safeString(android.os.Build.PRODUCT, "Unknown_ATV");
            String id = safeString(android.os.Build.ID, "Unknown");
            String manufacturer = safeString(android.os.Build.MANUFACTURER, "Unknown");
            String model = safeString(android.os.Build.MODEL, "Unknown");
            return "Mozilla/5.0 (Linux arm64-v8a; Android " + androidVersion
                    + ") Cobalt/27.lts.1.1040559-gold (unlike Gecko) v8/13.8.258.31-jit gles Starboard/18, "
                    + product + "/" + id
                    + " (" + manufacturer + ", " + model
                    + ") com.google.android.youtube.tv/7.02.302";
        } catch (Throwable ignored) {
            return "Mozilla/5.0 (Linux arm64-v8a; Android 14) Cobalt/27.lts.1.1040559-gold "
                    + "(unlike Gecko) v8/13.8.258.31-jit gles Starboard/18, generic/generic "
                    + "(Xiaomi, MI 6) com.google.android.youtube.tv/7.02.302";
        }
    }

    private static String safeString(String value, String fallback) {
        return value == null || value.length() == 0 ? fallback : value;
    }

    /**
     * pg.jar's R.G.b header set for SABR ABR posts — the Cobalt/Starboard browser fingerprint.
     * No Authorization header; identity comes from the playback cookie in the body.
     */
    static Map<String, String> buildCobaltHeaders(String userAgent) {
        String ua = userAgent == null || userAgent.isEmpty() ? cobaltUserAgent() : userAgent;
        String make;
        String fingerprint;
        try {
            make = android.os.Build.MANUFACTURER;
            fingerprint = android.os.Build.FINGERPRINT;
        } catch (Throwable ignored) {
            make = "Xiaomi";
            fingerprint = "Xiaomi/generic/generic:15/AP3A.240905.015/eng.build.20250101:user/release-keys";
        }
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("sec-ch-ua-platform", "\"Starboard\"");
        headers.put("User-Agent", ua);
        headers.put("sec-ch-ua", "\"" + make + "\";v=\"27\"");
        headers.put("sec-ch-ua-mobile", "?0");
        headers.put("Accept", "*/*");
        headers.put("Origin", "https://www.youtube.com");
        headers.put("sec-fetch-site", "cross-site");
        headers.put("sec-fetch-mode", "cors");
        headers.put("sec-fetch-dest", "empty");
        headers.put("sec-fetch-storage-access", "none");
        headers.put("Referer", "https://www.youtube.com/");
        headers.put("Accept-Encoding", "gzip, deflate, br");
        headers.put("Accept-Language", "en-US");
        headers.put("sec-ch-ua-co-android-build-fingerprint", fingerprint);
        headers.put("sec-ch-ua-co-android-os-experience", "Watson");
        headers.put("sec-ch-ua-co-android-play-services-version", "250832035");
        headers.put("sec-ch-ua-co-youtube-certification-scope", "");
        headers.put("priority", "u=1, i");
        return headers;
    }

    /**
     * VideoPlaybackAbrRequest: client_abr_state=1, initialized_format_ids=2, buffered_ranges=3,
     * player_time_ms=4, video_playback_ustreamer_config=5, preferred_audio_format_ids=16,
     * preferred_video_format_ids=17, streamer_context=19.
     */
        /**
     * VPAbrRequest top-level fields. Two shapes:
     *
     * <p><b>Cobalt (OAuth, no poToken)</b> — exactly pg.jar's {@code buildAbrRequest}: 1 abrState,
     * 2 selected formatId (omitted on init), 3 one synthetic full buffered range (omitted on init),
     * 5 ustreamer config, 16 preferred audio format ids, 17 preferred video format ids, 19
     * streamerContext. No field 4. The server gates media on this fingerprint, so byte parity with
     * the reference client matters here.
     *
     * <p><b>Legacy (poToken line)</b> — the shape this app has always sent and that plays full
     * length with a BotGuard token: 1 abrState, 2 every initialized formatId, 3 real buffered
     * ranges, 4 player time, 5 ustreamer, 16/17 selected ids, 19 streamerContext.
     */
    static byte[] buildVpabrRequest(Config cfg, YTFormat videoItem, YTFormat audioItem, long startTimeMs,
                                    AbrEnv env, boolean init, byte[] playbackCookie,
                                    List<byte[]> initializedFormatIds,
                                    List<byte[]> bufferedRanges, Map<Integer, SabrContext> sabrContexts,
                                    Set<Integer> unsentContexts) {
        ClientInfo clientInfo = cfg.clientInfo == null ? new ClientInfo() : cfg.clientInfo;
        boolean cobalt = cfg.poToken == null || cfg.poToken.isEmpty();
        YTFormat primary = videoItem != null && videoItem.itag != 0 ? videoItem : audioItem;
        byte[] p = YTProto.pbMsg(1, buildClientAbrState(env));
        byte[] ustreamer = YTProto.b64urlDecode(cfg.videoPlaybackUstreamerConfig);
        if (cobalt) {
            if (!init && primary != null && primary.itag != 0) {
                byte[] fid = buildFormatId(primary, isVideoFormat(primary));
                p = YTProto.concat(p, YTProto.pbBytes(2, fid));
                p = YTProto.concat(p, YTProto.pbBytes(3, buildFullBufferedRange(fid)));
            }
            if (ustreamer != null && ustreamer.length > 0) p = YTProto.concat(p, YTProto.pbBytes(5, ustreamer));
            for (byte[] id : buildPreferredAudioFormatIds(cfg, audioItem)) {
                p = YTProto.concat(p, YTProto.pbBytes(16, id));
            }
            for (byte[] id : buildPreferredVideoFormatIds(cfg, primary)) {
                p = YTProto.concat(p, YTProto.pbBytes(17, id));
            }
            p = YTProto.concat(p, YTProto.pbMsg(19,
                    buildStreamerContext(clientInfo, cfg.poToken, playbackCookie, sabrContexts, unsentContexts, init)));
            return p;
        }
        if (initializedFormatIds != null) {
            for (byte[] id : initializedFormatIds) {
                if (id != null && id.length > 0) p = YTProto.concat(p, YTProto.pbMsg(2, id));
            }
        }
        if (bufferedRanges != null) {
            for (byte[] br : bufferedRanges) {
                if (br != null && br.length > 0) p = YTProto.concat(p, YTProto.pbMsg(3, br));
            }
        }
        p = YTProto.concat(p, YTProto.pbInt(4, Math.max(0, startTimeMs)));
        if (ustreamer != null && ustreamer.length > 0) p = YTProto.concat(p, YTProto.pbBytes(5, ustreamer));
        if (audioItem != null && audioItem.itag != 0) {
            p = YTProto.concat(p, YTProto.pbMsg(16, buildFormatId(audioItem.itag)));
        }
        if (videoItem != null && videoItem.itag != 0) {
            p = YTProto.concat(p, YTProto.pbMsg(17, buildFormatId(videoItem.itag, cfg.lastModified, cfg.xtags)));
        }
        p = YTProto.concat(p, YTProto.pbMsg(19,
                buildStreamerContext(clientInfo, cfg.poToken, playbackCookie, sabrContexts, unsentContexts, init)));
        return p;
    }

    private static boolean isVideoFormat(YTFormat fmt) {
        return fmt.vcodec != null && !fmt.vcodec.isEmpty() && !"none".equals(fmt.vcodec);
    }

    /** pg.jar's encodeFormatId: {1 itag, 2 lastModified, 3 xtags (always for video, even empty)}. */
    static byte[] buildFormatId(YTFormat fmt, boolean video) {
        YTSabr.Config c = fmt.sabrConfig;
        byte[] p = YTProto.pbInt(1, fmt.itag);
        if (c != null && c.lastModified != null && !c.lastModified.isEmpty()) {
            byte[] lm = YTProto.b64urlDecode(c.lastModified);
            if (lm != null && lm.length > 0) p = YTProto.concat(p, YTProto.pbBytes(2, lm));
        }
        String xtags = c == null ? null : c.xtags;
        byte[] xb = xtags == null || xtags.isEmpty() ? YTProto.EMPTY : xtags.getBytes();
        if (video || xb.length > 0) p = YTProto.concat(p, YTProto.pbBytes(3, xb));
        return p;
    }

    /**
     * pg.jar's encodeFullBufferedRange: a fixed synthetic "everything buffered" range,
     * {formatId=1, 3=INT_MAX, 4=INT_MAX, 5=INT_MAX, 6={2=INT_MAX, 3=1000}}. The Cobalt line sends
     * this constant instead of the real buffered ranges.
     */
    static byte[] buildFullBufferedRange(byte[] formatId) {
        byte[] p = YTProto.pbBytes(1, formatId);
        p = YTProto.concat(p, YTProto.pbInt(3, Integer.MAX_VALUE));
        p = YTProto.concat(p, YTProto.pbInt(4, Integer.MAX_VALUE));
        p = YTProto.concat(p, YTProto.pbInt(5, Integer.MAX_VALUE));
        byte[] sub = YTProto.pbInt(2, Integer.MAX_VALUE);
        sub = YTProto.concat(sub, YTProto.pbInt(3, 1000));
        p = YTProto.concat(p, YTProto.pbMsg(6, sub));
        return p;
    }

    /**
     * Preferred audio formats (field 16): the selected audio first when it is an opus (itag 250/251),
     * then every opus in the pool, deduped by itag, capped at 8. Falls back to the whole pool when
     * the response carries no opus at all.
     */
    static List<byte[]> buildPreferredAudioFormatIds(Config cfg, YTFormat selectedAudio) {
        List<byte[]> out = new ArrayList<>();
        List<YTFormat> pool = cfg.audioPool;
        if (pool == null || pool.isEmpty()) return out;
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        if (selectedAudio != null && (selectedAudio.itag == 250 || selectedAudio.itag == 251)) {
            out.add(buildFormatId(selectedAudio, false));
            seen.add((long) selectedAudio.itag);
        }
        for (YTFormat f : pool) {
            if (out.size() >= 8) break;
            if (f == null || (f.itag != 250 && f.itag != 251)) continue;
            if (!seen.add((long) f.itag)) continue;
            out.add(buildFormatId(f, false));
        }
        if (out.isEmpty()) {
            for (YTFormat f : pool) {
                if (out.size() >= 8) break;
                if (f == null || f.itag == 0 || !seen.add((long) f.itag)) continue;
                out.add(buildFormatId(f, false));
            }
        }
        return out;
    }

    /**
     * Preferred video formats (field 17): the selected video first, then same-codec-family
     * formats from the pool, capped at 6 total.
     */
    static List<byte[]> buildPreferredVideoFormatIds(Config cfg, YTFormat selected) {
        List<byte[]> out = new ArrayList<>();
        List<YTFormat> pool = cfg.videoPool;
        if (pool == null || pool.isEmpty()) return out;
        String family = selected == null ? null : codecFamily(selected.codecs);
        if (selected != null && selected.itag != 0) {
            out.add(buildFormatId(selected, true));
        }
        for (YTFormat f : pool) {
            if (out.size() >= 6) break;
            if (f == null || f.itag == 0 || (selected != null && f.itag == selected.itag)) continue;
            String fFamily = codecFamily(f.codecs);
            if (family == null || fFamily == null || !family.equals(fFamily)) continue;
            out.add(buildFormatId(f, true));
        }
        return out;
    }

    /** Codec family tag ("av01"/"vp09"/"avc1"/"hvc1"...) used to group preferred video formats. */
    private static String codecFamily(String codecs) {
        if (codecs == null) return null;
        String c = codecs.toLowerCase(Locale.US).trim();
        if (c.isEmpty()) return null;
        int cut = c.length();
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch == '.' || ch == ' ' || ch == ',') { cut = i; break; }
        }
        String family = c.substring(0, cut);
        return "vp9".equals(family) ? "vp09" : family;
    }

    /**
     * MediaHeader.format_id is field 13; minimal responses only expose itag in field 3.
     */
    static byte[] headerFormatId(byte[] headerData, Long fallbackItag) {
        byte[] fmt = YTProto.getBytes(headerData, 13);
        if (fmt != null && fmt.length > 0) return fmt;
        Long itag = YTProto.getInt(headerData, 3);
        if (itag == null) itag = fallbackItag;
        return itag == null ? YTProto.EMPTY : buildFormatId(itag.intValue());
    }

    static long ticksToMs(Long ticks, Long timescale) {
        try {
            long t = ticks == null ? 0 : ticks;
            long scale = timescale == null || timescale == 0 ? 1000 : timescale;
            return t * 1000 / scale;
        } catch (Throwable e) {
            return 0;
        }
    }

    /**
     * MediaHeader.time_range is field 15; TimeRange has start_ticks=1, duration_ticks=2, timescale=3.
     *
     * @return {@code {startMs, durationMs}}
     */
    static long[] timeRangeMs(byte[] headerData) {
        byte[] tr = YTProto.getBytes(headerData, 15);
        if (tr == null || tr.length == 0) return new long[]{0, 0};
        return new long[]{
                ticksToMs(YTProto.getInt(tr, 1), YTProto.getInt(tr, 3)),
                ticksToMs(YTProto.getInt(tr, 2), YTProto.getInt(tr, 3)),
        };
    }

    static List<byte[]> emptyByteList() {
        return new ArrayList<>();
    }
}