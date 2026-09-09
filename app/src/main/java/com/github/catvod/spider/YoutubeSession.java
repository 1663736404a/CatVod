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
    private long tokenAttemptAt;
    private static final long TOKEN_RETRY_MS = 5000L;

    YoutubeSession(android.content.Context context, JsonObject config) {
        poTokens = new YoutubePoToken(config);
        botGuard = new YoutubeBotGuard(context, poTokens);
    }

    void bind(String visitorData, Integer signatureTimestamp) {
        if (this.visitorData == null || !this.visitorData.equals(visitorData)) {
            cachedBinding = visitorData;
            cachedToken = null;
            tokenAttempted = false;
            tokenAttemptAt = 0L;
        }
        this.visitorData = visitorData;
        this.signatureTimestamp = signatureTimestamp;
    }

    /**
     * The token from the site config, without minting one.
     *
     * <p>Used on the OAuth path: the authenticated session already carries its own integrity proof,
     * so a WebView/BotGuard round trip would be pure latency on every extraction. A token the user
     * configured explicitly is still passed along, because that is them asking for it.
     */
    synchronized String configuredToken() {
        return visitorData == null ? null : poTokens.get(YoutubePlayer.CLIENT, visitorData);
    }

    synchronized String poToken() {
        if (visitorData == null) return null;
        long now = System.currentTimeMillis();
        if (visitorData.equals(cachedBinding) && tokenAttempted
                && (cachedToken != null || now - tokenAttemptAt < TOKEN_RETRY_MS)) return cachedToken;
        cachedBinding = visitorData;
        tokenAttempted = true;
        tokenAttemptAt = now;
        cachedToken = botGuard.token(visitorData);
        // A transient WebView timeout must not permanently poison this visitor binding. The next
        // extraction after the short cooldown gets one fresh BotGuard attempt, while concurrent
        // callers remain serialized by this method's monitor.
        return cachedToken;
    }

    synchronized void retryToken() {
        tokenAttempted = false;
        tokenAttemptAt = 0L;
        cachedToken = null;
    }
}
