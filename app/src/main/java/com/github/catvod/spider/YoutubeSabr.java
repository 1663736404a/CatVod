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
        // A poToken is only an integrity requirement for the anonymous path. When signed in, the
        // player response is authenticated and its playback cookie is server-issued, which is the
        // credential the SABR endpoint checks; requiring a token here would block the OAuth route
        // for no reason and drag BotGuard back into playback.
        if (TextUtils.isEmpty(config.poToken) && !config.authenticated) {
            return "missing-visitor-bound-potoken";
        }
        return null;
    }
}
