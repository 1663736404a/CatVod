package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Executes BotGuard's HTTP calls on the spider's client and returns them to JS as JSON.
 *
 * <p>Split out of {@link YoutubeBotGuard} so the transport can be exercised without a WebView.
 * The envelope is deliberately minimal, because it crosses the {@code @JavascriptInterface}
 * boundary as a single string:
 * <pre>
 * {"status":200,"body":"...","headers":{"content-type":"..."}}
 * {"error":"..."}                                   // transport failure
 * </pre>
 */
final class YTBotGuardFetch {

    /** Long enough for a slow proxy hop, short enough to stay inside the mint deadline. */
    private static final long TIMEOUT_MS = 20000L;

    private YTBotGuardFetch() {
    }

    static String run(YTHttp http, String method, String url, String headersJson, String body) {
        if (http == null) return error("no-http-client");
        if (url == null || url.isEmpty()) return error("empty-url");
        try {
            Map<String, String> headers = headers(headersJson);
            // The page has no real origin (loadDataWithBaseURL), so restate the YouTube origin the
            // BotGuard endpoints expect.
            if (!headers.containsKey("Origin") && !headers.containsKey("origin")) {
                headers.put("Origin", "https://www.youtube.com");
            }
            if (!headers.containsKey("Referer") && !headers.containsKey("referer")) {
                headers.put("Referer", "https://www.youtube.com/");
            }
            YTHttp.Exchange result = http.exchange(method, url, headers, body, TIMEOUT_MS);
            return envelope(result);
        } catch (Throwable error) {
            SpiderDebug.log("YouTube BotGuard 请求失败: " + method + " " + host(url) + " " + error);
            return error(String.valueOf(error));
        }
    }

    /** Serialises the response. Only content-type is forwarded; bgutils reads nothing else. */
    private static String envelope(YTHttp.Exchange result) {
        StringBuilder out = new StringBuilder("{\"status\":").append(result.code);
        String type = null;
        for (Map.Entry<String, String> entry : result.headers.entrySet()) {
            if ("content-type".equalsIgnoreCase(entry.getKey())) {
                type = entry.getValue();
                break;
            }
        }
        out.append(",\"headers\":{");
        if (type != null) out.append("\"content-type\":").append(quote(type));
        out.append('}');
        out.append(",\"body\":").append(quote(result.body)).append('}');
        return out.toString();
    }

    private static String error(String message) {
        return "{\"error\":" + quote(message) + "}";
    }

    /** Host only, so a signed URL is never written to the log. */
    private static String host(String url) {
        try {
            int start = url.indexOf("://");
            if (start < 0) return "?";
            int from = start + 3;
            int end = url.indexOf('/', from);
            return end < 0 ? url.substring(from) : url.substring(from, end);
        } catch (Throwable ignored) {
            return "?";
        }
    }

    /* ------------------------------------------------------------------ */
    /* minimal JSON, avoiding a dependency for a two-shape payload        */
    /* ------------------------------------------------------------------ */

    /** Parses the flat {@code {"k":"v"}} map JS sends. */
    static Map<String, String> headers(String json) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (json == null) return headers;
        String text = json.trim();
        if (text.length() < 2 || text.charAt(0) != '{') return headers;
        int index = 1;
        int end = text.length();
        while (index < end) {
            while (index < end && (text.charAt(index) == ',' || Character.isWhitespace(text.charAt(index)))) index++;
            if (index >= end || text.charAt(index) == '}') break;
            if (text.charAt(index) != '"') break;
            StringBuilder key = new StringBuilder();
            index = readString(text, index, key);
            if (index < 0) break;
            while (index < end && Character.isWhitespace(text.charAt(index))) index++;
            if (index >= end || text.charAt(index) != ':') break;
            index++;
            while (index < end && Character.isWhitespace(text.charAt(index))) index++;
            if (index >= end) break;
            if (text.charAt(index) != '"') {
                // Skip a non-string value rather than aborting the whole map.
                while (index < end && text.charAt(index) != ',' && text.charAt(index) != '}') index++;
                continue;
            }
            StringBuilder value = new StringBuilder();
            index = readString(text, index, value);
            if (index < 0) break;
            if (key.length() > 0) headers.put(key.toString(), value.toString());
        }
        return headers;
    }

    /** Reads a JSON string starting at the opening quote. @return index after the closing quote. */
    private static int readString(String text, int start, StringBuilder out) {
        int index = start + 1;
        int end = text.length();
        while (index < end) {
            char c = text.charAt(index);
            if (c == '\\') {
                if (index + 1 >= end) return -1;
                char next = text.charAt(index + 1);
                switch (next) {
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'u':
                        if (index + 5 >= end) return -1;
                        try {
                            out.append((char) Integer.parseInt(text.substring(index + 2, index + 6), 16));
                        } catch (Throwable ignored) {
                            return -1;
                        }
                        index += 4;
                        break;
                    default: out.append(next);
                }
                index += 2;
                continue;
            }
            if (c == '"') return index + 1;
            out.append(c);
            index++;
        }
        return -1;
    }

    static String quote(String value) {
        if (value == null) return "\"\"";
        StringBuilder out = new StringBuilder(value.length() + 16).append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                default:
                    // Escape controls and the line separators that would break a JS string.
                    if (c < 0x20 || c == '\u2028' || c == '\u2029') {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.append('"').toString();
    }
}
