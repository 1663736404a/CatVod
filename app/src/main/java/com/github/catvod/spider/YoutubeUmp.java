package com.github.catvod.spider;

/** UMP/SABR protocol boundary. Parsing remains in the verified YTProto implementation. */
final class YoutubeUmp {
    private YoutubeUmp() {}

    static boolean mediaPart(int id) {
        return id == YTSabr.MEDIA || id == YTSabr.MEDIA_HEADER || id == YTSabr.MEDIA_END;
    }
}
