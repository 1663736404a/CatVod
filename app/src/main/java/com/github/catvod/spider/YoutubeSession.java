package com.github.catvod.spider;

import com.google.gson.JsonObject;

/**
 * Owns the visitor/token identity for one Spider lifetime.
 *
 * <p>The poToken is minted offline by {@link YoutubePoTokenSo}; there is no WebView and no
 * BotGuard round trip, so binding a visitor costs one native call.
 */
final class YoutubeSession {
    final YoutubePoToken poTokens;
    final YoutubePoTokenSo poTokenSo;
    String visitorData;
    Integer signatureTimestamp;
    private String cachedBinding;
    private String cachedToken;
    private boolean tokenAttempted;
    private long tokenAttemptAt;
    /** Short cooldown so one failed mint does not poison a valid visitor binding. */
    private static final long TOKEN_RETRY_MS = 5000L;

    YoutubeSession(android.content.Context context, JsonObject config) {
        this(context, config, null);
    }

    /**
     * @param http retained for call-site compatibility; the offline minter performs no I/O.
     */
    YoutubeSession(android.content.Context context, JsonObject config, YTHttp http) {
        poTokens = new YoutubePoToken(config);
        poTokenSo = new YoutubePoTokenSo(context, poTokens,
                YouTubeLite.optString(config, "pot_so_path", null));
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
        cachedToken = poTokenSo.token(visitorData);
        return cachedToken;
    }

    synchronized void retryToken() {
        tokenAttempted = false;
        tokenAttemptAt = 0L;
        cachedToken = null;
    }
}
