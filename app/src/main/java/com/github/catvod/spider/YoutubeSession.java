package com.github.catvod.spider;

import com.google.gson.JsonObject;

/** Owns the visitor/token identity for one Spider lifetime. */
final class YoutubeSession {
    final YoutubePoToken poTokens;
    final YoutubeBotGuard botGuard;
    String visitorData;
    Integer signatureTimestamp;
    private String cachedBinding;
    private String cachedToken;
    private boolean tokenAttempted;

    YoutubeSession(android.content.Context context, JsonObject config) {
        poTokens = new YoutubePoToken(config);
        botGuard = new YoutubeBotGuard(context, poTokens);
    }

    void bind(String visitorData, Integer signatureTimestamp) {
        if (this.visitorData == null || !this.visitorData.equals(visitorData)) {
            cachedBinding = visitorData;
            cachedToken = null;
            tokenAttempted = false;
        }
        this.visitorData = visitorData;
        this.signatureTimestamp = signatureTimestamp;
    }

    synchronized String poToken() {
        if (visitorData == null) return null;
        if (visitorData.equals(cachedBinding) && tokenAttempted) return cachedToken;
        cachedBinding = visitorData;
        tokenAttempted = true;
        cachedToken = botGuard.token(visitorData);
        return cachedToken;
    }
}
