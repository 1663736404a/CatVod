package com.github.catvod.spider;

import android.text.TextUtils;

/** Validates the player fields required before a SABR session can be created. */
final class YoutubeSabr {
    private YoutubeSabr() {}

    static String validate(YTSabr.Config config) {
        if (config == null) return "missing-config";
        if (!YoutubePlayer.CLIENT.equals(config.clientName)) return "not-tvhtml5";
        if (TextUtils.isEmpty(config.serverAbrStreamingUrl)) return "missing-server-abr-url";
        if (TextUtils.isEmpty(config.videoPlaybackUstreamerConfig)) return "missing-ustreamer-config";
        if (config.clientInfo == null || !YoutubeVisitor.usable(config.clientInfo.visitorData)) return "missing-visitor-data";
        // A poToken is required on every route, signed in or not.
        //
        // The OAuth route used to be exempted here on the theory that an authenticated player
        // response carries a server-issued playbackCookie which the SABR endpoint accepts instead.
        // Device logs disproved it: the OAuth player response reports playbackCookie=false every
        // time, so the cookie is locally synthesized and no integrity proof reaches the server.
        // pg.jar only skips the token when the cookie genuinely came from the server, which it
        // tracks with a dedicated playbackCookieFromServer flag.
        //
        // The observable outcome was decisive: same video, same IP, same TVHTML5 client, five
        // seconds apart, formats=45 on both — the tokenless OAuth attempt got 403 on every SABR
        // request while the anonymous attempt with a BotGuard token streamed to rn=59.
        if (TextUtils.isEmpty(config.poToken)) {
            return "missing-visitor-bound-potoken";
        }
        return null;
    }
}
