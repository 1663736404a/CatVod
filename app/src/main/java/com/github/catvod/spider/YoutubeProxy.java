package com.github.catvod.spider;

import java.util.Map;

/** JAR-owned proxy boundary for YouTube playback. */
final class YoutubeProxy {
    private final YTPlay play;

    YoutubeProxy(YTPlay play) {
        this.play = play;
    }

    Object[] handle(Map<String, String> params) {
        return play.proxy(params);
    }
}
