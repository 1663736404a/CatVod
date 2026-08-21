package com.github.catvod.spider;

/** BotGuard integration boundary. A real provider can be added without changing the Spider. */
final class YoutubeBotGuard {
    private final YoutubePoToken tokens;

    YoutubeBotGuard(YoutubePoToken tokens) {
        this.tokens = tokens;
    }

    String token(String visitorData) {
        return tokens.get(YoutubePlayer.CLIENT, visitorData);
    }

    boolean available(String visitorData) {
        return token(visitorData) != null;
    }
}
