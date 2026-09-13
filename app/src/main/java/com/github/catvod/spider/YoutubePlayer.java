package com.github.catvod.spider;

import android.net.Uri;

import com.google.gson.JsonObject;

/**
 * Player request identity.
 *
 * <p>The offline minter in {@code libpot.so} writes a fixed package name into the descriptor it
 * signs: disassembly shows {@code __strncpy_chk2} copying {@code "com.google.android.youtube"}
 * (va 0x128a) into the structure at 0x22f0, with a 64-byte bound. A token minted that way claims
 * to come from the Android YouTube app, so the InnerTube request it accompanies has to present the
 * same identity. Asking as TVHTML5 while presenting an ANDROID-bound token is a mismatch, which is
 * consistent with the server serving a few segments and then stopping: UMP part 58
 * (STREAM_PROTECTION_STATUS) grew from 2 to 4 bytes and media stopped arriving.
 *
 * <p>So the client used for the player call follows what the token declares. TVHTML5 remains
 * available through {@code ext.player_client} for comparison, since it is the identity the
 * WebView-minted tokens on the previous branch were bound to.
 */
final class YoutubePlayer {

    /** Package name the native minter binds its descriptor to. */
    static final String ANDROID_PACKAGE = "com.google.android.youtube";

    static final String CLIENT_ANDROID = "ANDROID";
    static final String CLIENT_TVHTML5 = "TVHTML5";

    /** Client whose identity matches the minted token. */
    static final String CLIENT = CLIENT_ANDROID;

    static final String ANDROID_VERSION = "20.10.38";
    static final String ANDROID_SDK = "34";
    static final String ANDROID_OS_VERSION = "14";
    static final String ANDROID_UA = "com.google.android.youtube/" + ANDROID_VERSION
            + " (Linux; U; Android " + ANDROID_OS_VERSION + "; en_US) gzip";

    static final String TVHTML5_VERSION = "7.20250312.16.00";
    static final String TVHTML5_UA = "Mozilla/5.0 (PlayStation; PlayStation 4/12.00) "
            + "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15";

    private YoutubePlayer() {}

    /** @return the client to use for the player call; {@code ext.player_client} overrides it. */
    static String client(JsonObject ext) {
        String configured = YouTubeLite.optString(ext, "player_client", CLIENT);
        return CLIENT_TVHTML5.equalsIgnoreCase(configured) ? CLIENT_TVHTML5 : CLIENT_ANDROID;
    }

    static String version(JsonObject ext) {
        return CLIENT_TVHTML5.equals(client(ext))
                ? YouTubeLite.optString(ext, "tvhtml5_client_version", TVHTML5_VERSION)
                : YouTubeLite.optString(ext, "android_client_version", ANDROID_VERSION);
    }

    static String userAgent(JsonObject ext) {
        return CLIENT_TVHTML5.equals(client(ext))
                ? YouTubeLite.optString(ext, "tvhtml5_user_agent", TVHTML5_UA)
                : YouTubeLite.optString(ext, "android_user_agent", ANDROID_UA);
    }

    static String url(String videoId) {
        return "https://www.youtube.com/watch?v=" + Uri.encode(videoId);
    }
}
