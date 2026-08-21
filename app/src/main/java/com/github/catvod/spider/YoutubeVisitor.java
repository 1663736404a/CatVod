package com.github.catvod.spider;

import android.text.TextUtils;
import com.google.gson.JsonObject;

/** Resolves and validates the visitor identity shared by watch, player and BotGuard. */
final class YoutubeVisitor {
    private YoutubeVisitor() {}

    static String resolve(JsonObject config, JsonObject ytcfg, JsonObject playerResponse) {
        String value = YouTubeLite.optString(config, "visitor_data", null);
        if (TextUtils.isEmpty(value)) value = YouTubeLite.optString(ytcfg, "VISITOR_DATA", null);
        if (TextUtils.isEmpty(value)) value = YouTubeLite.traverseString(ytcfg, "INNERTUBE_CONTEXT", "client", "visitorData");
        if (TextUtils.isEmpty(value)) value = YouTubeLite.traverseString(playerResponse, "responseContext", "visitorData");
        return TextUtils.isEmpty(value) ? null : value;
    }

    static boolean usable(String value) {
        return !TextUtils.isEmpty(value) && value.length() >= 12;
    }
}
