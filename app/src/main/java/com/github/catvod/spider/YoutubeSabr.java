package com.github.catvod.spider;

import android.text.TextUtils;

/** Validates the player fields required before a SABR session can be created. */
final class YoutubeSabr {
    private YoutubeSabr() {}

    /**
     * @param expectedClient the client the player response was requested as; SABR state belongs to
     *                       that identity, so a response from any other client is not usable here.
     */
    static String validate(YTSabr.Config config, String expectedClient) {
        if (config == null) return "missing-config";
        if (expectedClient == null || !expectedClient.equals(config.clientName)) {
            return "client-mismatch:" + (config.clientName == null ? "none" : config.clientName);
        }
        if (TextUtils.isEmpty(config.serverAbrStreamingUrl)) return "missing-server-abr-url";
        if (TextUtils.isEmpty(config.videoPlaybackUstreamerConfig)) return "missing-ustreamer-config";
        if (config.clientInfo == null || !YoutubeVisitor.usable(config.clientInfo.visitorData)) return "missing-visitor-data";
        if (TextUtils.isEmpty(config.poToken)) return "missing-visitor-bound-potoken";
        return null;
    }
}
