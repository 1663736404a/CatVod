package com.github.catvod.spider;

import java.util.List;
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
        int itag;
        String xtags;
        String lastModified;
        double targetDurationSec;
        boolean preferHdr;
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

    /** yt-dlp sends MediaCapabilities for the ANDROID/IOS/ANDROID_VR client families. */
    static byte[] buildMediaCapabilities(int clientNameId, boolean preferHdr) {
        if (clientNameId != 3 && clientNameId != 5 && clientNameId != 28 && clientNameId != 101) {
            return YTProto.EMPTY;
        }
        byte[] p = YTProto.EMPTY;
        // VideoFormatCapability: video_codec=1, efficient=2, is_10_bit_supported=15
        for (int codec : new int[]{2, 4, 8, 9}) { // H264 VP9 AV1 H265
            byte[] body = YTProto.concat(YTProto.pbInt(1, codec), YTProto.pbBool(2, true), YTProto.pbBool(15, true));
            p = YTProto.concat(p, YTProto.pbMsg(1, body));
        }
        // AudioFormatCapability: audio_codec=1
        for (int acodec : new int[]{1, 3, 9, 13}) { // AAC OPUS MP3 XHEAAC
            p = YTProto.concat(p, YTProto.pbMsg(2, YTProto.pbInt(1, acodec)));
        }
        p = YTProto.concat(p, YTProto.pbInt(5, preferHdr ? 3 : 0));
        return p;
    }

    /**
     * ClientAbrState: player_time_ms=28, media_capabilities=38,
     * enabled_track_types_bitfield=40, drc_enabled=46, enable_voice_boost=76.
     */
    static byte[] buildClientAbrState(int clientNameId, long startTimeMs, boolean preferHdr, boolean audioOnly) {
        byte[] p = YTProto.pbInt(28, Math.max(0, startTimeMs));
        byte[] mc = buildMediaCapabilities(clientNameId, preferHdr);
        if (mc.length > 0) p = YTProto.concat(p, YTProto.pbMsg(38, mc));
        p = YTProto.concat(p, YTProto.pbInt(40, audioOnly ? 1 : 0));
        p = YTProto.concat(p, YTProto.pbBool(46, true));
        p = YTProto.concat(p, YTProto.pbBool(76, true));
        return p;
    }

    /**
     * StreamerContext: client_info=1, po_token=2, playback_cookie=3, sabr_contexts=5.
     *
     * <p>Following googlevideo's {@code prepareSabrContexts}, only contexts the server marked
     * active are replayed.
     */
    static byte[] buildStreamerContext(ClientInfo clientInfo, String poToken, byte[] playbackCookie,
                                       Map<Integer, SabrContext> sabrContexts,
                                       Set<Integer> unsentContexts) {
        byte[] p = YTProto.pbMsg(1, buildClientInfo(clientInfo));
        byte[] pot = poToken == null ? null : YTProto.b64urlDecode(poToken);
        if (pot != null && pot.length > 0) p = YTProto.concat(p, YTProto.pbBytes(2, pot));
        if (playbackCookie != null && playbackCookie.length > 0) {
            p = YTProto.concat(p, YTProto.pbBytes(3, playbackCookie));
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
     * VideoPlaybackAbrRequest: client_abr_state=1, initialized_format_ids=2, buffered_ranges=3,
     * player_time_ms=4, video_playback_ustreamer_config=5, preferred_audio_format_ids=16,
     * preferred_video_format_ids=17, streamer_context=19.
     */
    static byte[] buildVpabrRequest(Config cfg, Integer videoItag, Integer audioItag, long startTimeMs,
                                    byte[] playbackCookie, List<byte[]> initializedFormatIds,
                                    List<byte[]> bufferedRanges, Map<Integer, SabrContext> sabrContexts,
                                    Set<Integer> unsentContexts) {
        ClientInfo clientInfo = cfg.clientInfo == null ? new ClientInfo() : cfg.clientInfo;
        int clientNameId = clientInfo.clientNameId == 0 ? 1 : clientInfo.clientNameId;
        boolean audioOnly = videoItag == null || videoItag == 0;
        byte[] p = YTProto.pbMsg(1, buildClientAbrState(clientNameId, startTimeMs, cfg.preferHdr, audioOnly));
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
        byte[] ustreamer = YTProto.b64urlDecode(cfg.videoPlaybackUstreamerConfig);
        if (ustreamer != null && ustreamer.length > 0) p = YTProto.concat(p, YTProto.pbBytes(5, ustreamer));
        if (audioItag != null && audioItag != 0) {
            p = YTProto.concat(p, YTProto.pbMsg(16, buildFormatId(audioItag)));
        }
        if (videoItag != null && videoItag != 0) {
            p = YTProto.concat(p, YTProto.pbMsg(17, buildFormatId(videoItag, cfg.lastModified, cfg.xtags)));
        }
        p = YTProto.concat(p, YTProto.pbMsg(19,
                buildStreamerContext(clientInfo, cfg.poToken, playbackCookie, sabrContexts, unsentContexts)));
        return p;
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

}