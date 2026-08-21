package com.github.catvod.spider;

import android.text.TextUtils;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/** Supplies a GVS poToken and enforces its visitor-data binding declaration. */
final class YoutubePoToken {
    private final JsonObject config;

    YoutubePoToken(JsonObject config) {
        this.config = config == null ? new JsonObject() : config;
    }

    String get(String clientName, String visitorData) {
        JsonElement tokens = config.has("po_token") ? config.get("po_token") : config.get("po_tokens");
        String token = token(tokens, clientName);
        if (TextUtils.isEmpty(token)) return null;
        String binding = YouTubeLite.optString(config, "po_token_binding", visitorData);
        return visitorData != null && visitorData.equals(binding) ? token : null;
    }

    private static String token(JsonElement tokens, String clientName) {
        if (tokens == null || tokens.isJsonNull()) return null;
        if (tokens.isJsonPrimitive()) return tokens.getAsString();
        if (!tokens.isJsonObject()) return null;
        JsonObject object = tokens.getAsJsonObject();
        String key = clientName + ".gvs";
        if (object.has(key)) return object.get(key).getAsString();
        if (object.has(clientName)) return object.get(clientName).getAsString();
        return object.has("gvs") ? object.get("gvs").getAsString() : null;
    }
}
