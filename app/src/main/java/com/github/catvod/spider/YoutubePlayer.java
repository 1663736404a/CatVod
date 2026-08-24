package com.github.catvod.spider;

import android.net.Uri;

/** Holds the TVHTML5 player request identity and integrity condition. */
final class YoutubePlayer {
    static final String CLIENT = "TVHTML5";
    static final String DEFAULT_VERSION = "7.20250312.16.00";
    static final String DEFAULT_UA = "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) AppleWebKit/605.1.15 "
            + "(KHTML, like Gecko) Version/16.0 Safari/605.1.15";

    private YoutubePlayer() {}

    static String version(com.google.gson.JsonObject ext) {
        return YouTubeLite.optString(ext, "tvhtml5_client_version", DEFAULT_VERSION);
    }

    static String userAgent(com.google.gson.JsonObject ext) {
        return YouTubeLite.optString(ext, "tvhtml5_user_agent", DEFAULT_UA);
    }

    static String url(String videoId) {
        return "https://www.youtube.com/watch?v=" + Uri.encode(videoId);
    }

}
