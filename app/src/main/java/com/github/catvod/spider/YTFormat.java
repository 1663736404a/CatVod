package com.github.catvod.spider;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * One playable representation from a YouTube player response.
 *
 * <p>Covers both direct (URL-bearing) formats and SABR formats, which carry no per-format URL
 * and are fetched through a {@link YTSabr.Config} session instead.
 */
class YTFormat {

    int itag;
    String url;
    String mimeType = "";
    String client = "";
    String ext = "";
    int width;
    int height;
    int fps;
    long bitrate;
    String contentLength;
    String codecs = "";
    String quality = "";
    String vcodec = "";
    String acodec = "";
    /** "sabr" for SABR formats; null/empty for direct URLs. */
    String protocol;
    Map<String, String> headers = new HashMap<>();
    JsonObject colorInfo;
    long[] initRange;
    long[] indexRange;

    /* SABR-only state */
    YTSabr.Config sabrConfig;
    /** Byte ranges of the matching direct representation, used to fetch a real sidx/Cues index. */
    IndexSource indexSource;
    /** Segment boundaries parsed from sidx/Cues; empty until {@code loadTimeline} runs. */
    List<Seg> timeline = new ArrayList<>();

    /* view-model extras */
    String trackName;
    boolean isHdr;

    boolean hasVideo() {
        return vcodec != null && !"none".equals(vcodec);
    }

    boolean hasAudio() {
        return acodec != null && !"none".equals(acodec);
    }

    boolean isSabr() {
        return "sabr".equals(protocol);
    }

    YTFormat copy() {
        YTFormat c = new YTFormat();
        c.itag = itag;
        c.url = url;
        c.mimeType = mimeType;
        c.client = client;
        c.ext = ext;
        c.width = width;
        c.height = height;
        c.fps = fps;
        c.bitrate = bitrate;
        c.contentLength = contentLength;
        c.codecs = codecs;
        c.quality = quality;
        c.vcodec = vcodec;
        c.acodec = acodec;
        c.protocol = protocol;
        c.headers = new HashMap<>(headers);
        c.colorInfo = colorInfo;
        c.initRange = initRange;
        c.indexRange = indexRange;
        c.sabrConfig = sabrConfig;
        c.indexSource = indexSource;
        c.timeline = timeline;
        c.trackName = trackName;
        c.isHdr = isHdr;
        return c;
    }

    /** Location of the sidx/Cues index inside a direct progressive URL. */
    static class IndexSource {
        String url;
        Map<String, String> headers = new HashMap<>();
        long[] initRange;
        long[] indexRange;
        String contentLength;
    }

    /**
     * One media segment boundary.
     */
    static class Seg {
        /** presentation start, ms */
        long t;
        /** duration, ms */
        long d;
        /** byte offset, or -1 when unknown */
        long off = -1;
        /** byte size, or 0 when unknown */
        long sz;
    }

    static long[] range(JsonElement element) {
        if (element == null || !element.isJsonObject()) return null;
        JsonObject obj = element.getAsJsonObject();
        try {
            long start = obj.has("start") ? Long.parseLong(obj.get("start").getAsString()) : 0;
            long end = obj.has("end") ? Long.parseLong(obj.get("end").getAsString()) : 0;
            return new long[]{start, end};
        } catch (Throwable e) {
            return null;
        }
    }
}