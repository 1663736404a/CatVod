package com.github.catvod.spider;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Parses the standard CatVod class/filter JSON used by the YouTube site. */
final class YTExternalCatalog {
    private YTExternalCatalog() {
    }

    static boolean valid(JsonObject root) {
        return root != null && root.has("class") && root.get("class").isJsonArray();
    }

    static List<Class> classes(JsonObject root) {
        List<Class> result = new ArrayList<>();
        if (!valid(root)) return result;
        for (JsonElement element : root.getAsJsonArray("class")) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String id = text(item, "type_id");
            String name = text(item, "type_name");
            if (!id.isEmpty() && !name.isEmpty()) result.add(new Class(id, name));
        }
        return result;
    }

    static LinkedHashMap<String, List<Filter>> filters(JsonObject root) {
        LinkedHashMap<String, List<Filter>> result = new LinkedHashMap<>();
        if (root == null || !root.has("filters") || !root.get("filters").isJsonObject()) return result;
        JsonObject groups = root.getAsJsonObject("filters");
        for (Map.Entry<String, JsonElement> entry : groups.entrySet()) {
            if (!entry.getValue().isJsonArray()) continue;
            List<Filter> filters = new ArrayList<>();
            for (JsonElement groupElement : entry.getValue().getAsJsonArray()) {
                if (!groupElement.isJsonObject()) continue;
                JsonObject group = groupElement.getAsJsonObject();
                String key = text(group, "key");
                String name = text(group, "name");
                List<Filter.Value> values = new ArrayList<>();
                if (group.has("value") && group.get("value").isJsonArray()) {
                    for (JsonElement valueElement : group.getAsJsonArray("value")) {
                        if (!valueElement.isJsonObject()) continue;
                        JsonObject value = valueElement.getAsJsonObject();
                        values.add(new Filter.Value(text(value, "n"), text(value, "v")));
                    }
                }
                if (!key.isEmpty() && !name.isEmpty()) filters.add(new Filter(key, name, values));
            }
            result.put(entry.getKey(), filters);
        }
        return result;
    }

    static String keyword(JsonObject root, String tid, Map<String, String> selected) {
        String query = tid == null ? "" : tid.trim();
        if (root != null && root.has("class") && root.get("class").isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray("class")) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if (query.equals(text(item, "type_id"))) {
                    query = text(item, "type_id");
                    break;
                }
            }
        }
        if (query.startsWith("LIST:")) query = query.substring(5).replace(',', ' ');
        List<String> terms = new ArrayList<>();
        if (!query.trim().isEmpty()) terms.add(query.trim());
        if (selected != null) {
            for (String value : selected.values()) if (value != null && !value.trim().isEmpty()) terms.add(value.trim());
        }
        return String.join(" ", terms);
    }

    static String recommend(JsonObject root) {
        return root == null ? "" : text(root, "recommend");
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object == null ? null : object.get(key);
        return value == null || value.isJsonNull() ? "" : value.getAsString();
    }
}
