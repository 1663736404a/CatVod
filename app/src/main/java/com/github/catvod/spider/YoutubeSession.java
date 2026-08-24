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
        this(context, config, null);
    }

    /**
     * @param http the spider's HTTP client, used to run BotGuard's network calls through the
     *             configured proxy. WebView cannot use it; see {@link YoutubeBotGuard}.
     */
    YoutubeSession(android.content.Context context, JsonObject config, YTHttp http) {
        poTokens = new YoutubePoToken(config);
        botGuard = new YoutubeBotGuard(context, poTokens, http);
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
