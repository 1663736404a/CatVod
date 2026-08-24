package com.github.catvod.spider;

import com.github.catvod.crawler.SpiderDebug;

import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Thumbnail proxying for the YouTube spider.
 *
 * <p>Why this exists: {@code vod_pic} used to be a bare {@code https://i.ytimg.com/...} URL, which
 * the host's image loader fetches on its own. That fetch does not go through this spider, so it
 * never sees the proxy configured in {@code ext.proxy} — where YouTube images are blocked the
 * catalog loads but every poster is empty, and the request also leaks straight to Google.
 *
 * <p>Rather than embedding a port, images are handed back to the host as a URL pointing at the
 * host's own {@code /proxy} endpoint, which routes them into {@link YouTube#proxy(Map)} and out
 * through {@link YTHttp} — the same client, and therefore the same proxy, as the metadata calls.
 * The host owns that port and reports it via {@link Proxy}, so nothing here assumes a number.
 *
 * <p>The original URL travels as URL-safe base64 in a query parameter to keep its own query string
 * (YouTube signs thumbnails with {@code sqp}/{@code rs}) intact through the round trip.
 */
final class YTImage {

    /** Small ceiling: posters are tens of KB, so anything larger is not a thumbnail. */
    private static final int MAX_BYTES = 4 * 1024 * 1024;

    private YTImage() {
    }

    /**
     * Rewrites one image URL to travel through this spider.
     *
     * @return the proxied URL, or {@code url} unchanged when proxying is off or unavailable.
     */
    static String wrap(String siteKey, String url, boolean enabled) {
        if (!enabled || url == null || url.isEmpty()) return url;
        if (!url.startsWith("http://") && !url.startsWith("https://")) return url;
        // Already local (host proxy or this JAR's media server): leave it alone.
        if (url.contains("127.0.0.1") || url.contains("localhost")) return url;
        String base = hostProxyUrl();
        if (base == null) return url;
        String encoded = encode(url);
        if (encoded.isEmpty()) return url;
        return base + (base.contains("?") ? "&" : "?")
                + "do=csp&siteKey=" + siteKey + "&type=img&u=" + encoded;
    }

    /**
     * The host's HTTP proxy base, e.g. {@code http://127.0.0.1:9978/proxy}.
     *
     * @return {@code null} when the host has not reported a port, in which case callers keep the
     *         direct URL instead of emitting a URL that cannot be fetched.
     */
    private static String hostProxyUrl() {
        try {
            String url = Proxy.getUrl(true);
            if (url == null) return null;
            String text = url.trim();
            // Proxy.getUrl(true) is an http endpoint; a proxy:// value is for the player only and
            // no image loader understands it.
            return text.startsWith("http://") || text.startsWith("https://") ? text : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Answers a {@code type=img} request by fetching the image through the spider's client. */
    static Object[] serve(YTHttp http, Map<String, String> params) {
        String target = decode(params == null ? null : params.get("u"));
        if (http == null || target.isEmpty()) return error(400, "bad image request");
        Map<String, String> headers = new LinkedHashMap<>();
        // i.ytimg.com serves images without auth, but a YouTube referer keeps the request shaped
        // like the ones the metadata calls already make.
        headers.put("Referer", "https://www.youtube.com/");
        headers.put("Accept", "image/webp,image/avif,image/*,*/*;q=0.8");
        YTHttp.Result result = http.get(target, headers, null);
        try {
            byte[] body = result.body == null ? new byte[0] : result.body;
            if (result.code < 200 || result.code >= 300 || body.length == 0) {
                SpiderDebug.log("YouTube 图片代理失败: code=" + result.code + " bytes=" + body.length);
                // Must not be 502: the host maps this code onto NanoHTTPD's Status enum, which has
                // no 502 entry, so the lookup yields null and sendResponse() throws
                // "Status can't be null." — crashing the host while the poster grid loads.
                return error(500, "image fetch failed");
            }
            if (body.length > MAX_BYTES) {
                SpiderDebug.log("YouTube 图片过大已拒绝: bytes=" + body.length);
                return error(413, "image too large");
            }
            String type = result.contentType == null || result.contentType.isEmpty()
                    ? guessType(target) : result.contentType;
            Map<String, String> extra = new LinkedHashMap<>();
            // Posters are immutable for a given signed URL, so let the host cache them.
            extra.put("Cache-Control", "public, max-age=86400");
            extra.put("Content-Length", String.valueOf(body.length));
            return new Object[]{safeCode(200), type, new ByteArrayInputStream(body), extra};
        } finally {
            result.close();
        }
    }

    private static String guessType(String url) {
        String lower = url.toLowerCase();
        int query = lower.indexOf('?');
        if (query > 0) lower = lower.substring(0, query);
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".gif")) return "image/gif";
        return "image/jpeg";
    }

    /**
     * Status codes NanoHTTPD (the host's HTTP server) can represent.
     *
     * <p>Anything the host cannot map becomes {@code null} inside its response builder and takes the
     * whole app down with {@code java.lang.Error: sendResponse(): Status can't be null.} rather than
     * failing the one request. Returning a code from this set is therefore a hard requirement for
     * every value handed back through {@code Spider.proxy}. Notably absent: 502 and 504.
     */
    private static final int[] HOST_CODES = {
            101, 200, 201, 202, 204, 206, 207,
            301, 302, 303, 304, 307,
            400, 401, 403, 404, 405, 406, 408, 409, 410, 411, 412, 413, 415, 416, 417, 429,
            500, 501, 503, 505};

    /** Maps any code onto one the host can send, so a bad value cannot crash it. */
    static int safeCode(int code) {
        for (int candidate : HOST_CODES) {
            if (candidate == code) return code;
        }
        // Collapse to the nearest representable class rather than guessing a specific code.
        if (code >= 500) return 500;
        if (code >= 400) return 400;
        if (code >= 300) return 302;
        if (code >= 200) return 200;
        return 500;
    }

    private static Object[] error(int code, String message) {
        return new Object[]{safeCode(code), "text/plain; charset=utf-8",
                new ByteArrayInputStream(message.getBytes(java.nio.charset.StandardCharsets.UTF_8))};
    }

    /* ------------------------------------------------------------------ */
    /* url-safe base64 (no padding), implemented locally to stay API-21   */
    /* ------------------------------------------------------------------ */

    private static final String ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

    static String encode(String text) {
        if (text == null || text.isEmpty()) return "";
        byte[] data = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder(((data.length + 2) / 3) * 4);
        for (int index = 0; index < data.length; index += 3) {
            int remaining = data.length - index;
            int block = (data[index] & 0xFF) << 16;
            if (remaining > 1) block |= (data[index + 1] & 0xFF) << 8;
            if (remaining > 2) block |= data[index + 2] & 0xFF;
            out.append(ALPHABET.charAt((block >>> 18) & 0x3F));
            out.append(ALPHABET.charAt((block >>> 12) & 0x3F));
            if (remaining > 1) out.append(ALPHABET.charAt((block >>> 6) & 0x3F));
            if (remaining > 2) out.append(ALPHABET.charAt(block & 0x3F));
        }
        return out.toString();
    }

    static String decode(String text) {
        if (text == null || text.isEmpty()) return "";
        try {
            String value = text.trim();
            int length = value.length();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(length);
            int block = 0;
            int bits = 0;
            for (int index = 0; index < length; index++) {
                int digit = ALPHABET.indexOf(value.charAt(index));
                if (digit < 0) return "";
                block = (block << 6) | digit;
                bits += 6;
                if (bits >= 8) {
                    bits -= 8;
                    out.write((block >>> bits) & 0xFF);
                }
            }
            return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
