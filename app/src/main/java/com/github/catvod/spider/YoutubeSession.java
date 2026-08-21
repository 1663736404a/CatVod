package com.github.catvod.spider;

import com.google.gson.JsonObject;

/** Owns the visitor/token identity for one Spider lifetime. */
final class YoutubeSession {
    final YoutubePoToken poTokens;
    final YoutubeBotGuard botGuard;
    String visitorData;
    Integer signatureTimestamp;

    YoutubeSession(android.content.Context context, JsonObject config) {
        poTokens = new YoutubePoToken(config);
        botGuard = new YoutubeBotGuard(context, poTokens);
    }

    void bind(String visitorData, Integer signatureTimestamp) {
        this.visitorData = visitorData;
        this.signatureTimestamp = signatureTimestamp;
    }

    String poToken() {
        return botGuard.token(visitorData);
    }
}
