package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.Prefers;
import com.google.gson.JsonObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube TV OAuth device flow: sign-in, token lifetime and the authenticated request identity.
 *
 * <p>Why this exists: an anonymous SABR session is only accepted by the server when the request
 * carries a visitor-bound BotGuard poToken, because its playback cookie is locally synthesised and
 * the server wants an integrity signal (without it the stream is cut at roughly 60 seconds). An
 * OAuth session is different: the player response is authenticated with a {@code Bearer} token and
 * the playback cookie handed back with it is server-issued, so it is the credential the SABR
 * endpoint trusts. That removes the WebView/BotGuard step from the playback path entirely.
 *
 * <p>State is global on purpose. The host rebuilds the Spider on every catalog rebuild, and tokens
 * must survive that, so they live in {@link Prefers} with an in-memory fallback for the case where
 * the host never called {@code Init.init}.
 *
 * <p>Anonymous playback is untouched: every method here is inert until {@link #loggedIn()} is true.
 */
final class YoutubeOAuth {

    private static final String TV_PAGE = "https://www.youtube.com/tv";
    private static final String DEVICE_URL = "https://www.youtube.com/o/oauth2/device/code";
    private static final String TOKEN_URL = "https://www.youtube.com/o/oauth2/token";
    private static final String TV_BOOTSTRAP =
            "https://www.youtube.com/youtubei/v1/tv?prettyPrint=false";
    private static final String ACCOUNT_LIST =
            "https://www.youtube.com/youtubei/v1/account/accounts_list?prettyPrint=false";
    private static final String SCOPE = "https://www.googleapis.com/auth/youtube";
    /** The device flow uses this URL-shaped grant type rather than the usual {@code urn:} form. */
    private static final String GRANT_DEVICE = "http://oauth.net/grant_type/device/1.0";
    private static final String GRANT_REFRESH = "refresh_token";

    /**
     * Credentials of YouTube's own TV client, used as a fallback when they cannot be read from the
     * live {@code /tv} bundle. They are public values shipped in that bundle, not user secrets.
     */
    private static final String FALLBACK_CLIENT_ID =
            "861556708454-d6dlm3lh05idd8npek18k6be8ba3oc68.apps.googleusercontent.com";
    private static final String FALLBACK_CLIENT_SECRET = "SboVhoG9s0rNafixCSGGKXAT";

    private static final String KEY_ACCESS = "yt_oauth_access_token";
    private static final String KEY_REFRESH = "yt_oauth_refresh_token";
    private static final String KEY_EXPIRES = "yt_oauth_expires_at";
    private static final String KEY_VISITOR = "yt_oauth_visitor_data";
    private static final String KEY_VISITOR_AT = "yt_oauth_visitor_at";
    /** pg.jar persists the TV account page id and sends it as X-Goog-Pageid. */
    private static final String KEY_PAGE_ID = "yt_oauth_page_id";
    /** Cookies from the authenticated TV session must follow subsequent player calls. */
    private static final String KEY_COOKIES = "yt_oauth_cookies";

    /** Refresh this far before the server-declared expiry so no request races the boundary. */
    private static final long REFRESH_MARGIN_MS = 300000L;
    private static final long VISITOR_TTL_MS = 43200000L;

    private static final Pattern RE_CLIENT_ID =
            Pattern.compile("clientId\\s*:\\s*\"([\\w-]+\\.apps\\.googleusercontent\\.com)\"");
    private static final Pattern RE_CLIENT_SECRET = Pattern.compile("clientSecret\\s*:\\s*\"([\\w-]+)\"");
    private static final Pattern RE_JS = Pattern.compile("\"(/s/(?:tv|player)/[^\"]+?\\.js)\"");
    private static final Pattern RE_VISITOR_COOKIE = Pattern.compile("_____([\\w%\\-=]{16,})");

    /** In-memory mirror, and the only store when {@code Init.context()} is unavailable. */
    private static final Map<String, String> MEMORY = new HashMap<>();

    private static volatile YTHttp http;
    private static volatile String clientId;
    private static volatile String clientSecret;
    private static volatile Device pending;
    private static volatile Thread poller;
    private static final Object TOKEN_LOCK = new Object();

    private YoutubeOAuth() {
    }

    /** One in-flight device authorisation. */
    static final class Device {
        String deviceCode = "";
        String userCode = "";
        String verificationUrl = "https://www.youtube.com/activate";
        long intervalMs = 5000L;
        long expiresAt;
        volatile String state = "pending";
        volatile String error = "";

        boolean alive() {
            return "pending".equals(state) && System.currentTimeMillis() < expiresAt;
        }

        /** The URL encoded into the QR code; scanning it opens the activation page for this code. */
        String qrTarget() {
            if (userCode.isEmpty()) return verificationUrl;
            return "https://youtube.com/qr/activate/" + userCode.trim().replace(' ', '-');
        }
    }

    /* ------------------------------------------------------------------ */
    /* wiring                                                             */
    /* ------------------------------------------------------------------ */

    /**
     * Adopts the spider's HTTP client so OAuth traffic follows the site's {@code proxy} setting.
     *
     * <p>Without this, sign-in would bypass the proxy every anonymous request already uses and fail
     * wherever youtube.com is not directly reachable.
     */
    static void attach(YTHttp client) {
        if (client != null) http = client;
    }

    private static YTHttp http() {
        YTHttp client = http;
        if (client != null) return client;
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", YoutubePlayer.DEFAULT_UA);
        client = new YTHttp(headers);
        http = client;
        return client;
    }

    /* ------------------------------------------------------------------ */
    /* state                                                              */
    /* ------------------------------------------------------------------ */

    /** @return true when a refresh token is stored, i.e. playback should use the OAuth identity. */
    static boolean loggedIn() {
        return !TextUtils.isEmpty(read(KEY_REFRESH));
    }

    /** Discards the in-flight authorisation so the next {@link #begin()} requests a fresh code. */
    static void reset() {
        Thread current = poller;
        if (current != null) current.interrupt();
        pending = null;
    }

    static void logout() {
        synchronized (TOKEN_LOCK) {
            write(KEY_ACCESS, "");
            write(KEY_REFRESH, "");
            write(KEY_EXPIRES, "");
            write(KEY_VISITOR, "");
            write(KEY_VISITOR_AT, "");
            write(KEY_PAGE_ID, "");
            write(KEY_COOKIES, "");
        }
        pending = null;
        SpiderDebug.log("YouTube OAuth 已退出登录");
    }

    /**
     * @return a valid access token, refreshing it when close to expiry, or {@code null} when the
     *         user is not signed in or the refresh token has been revoked.
     */
    static String token() {
        synchronized (TOKEN_LOCK) {
            String refresh = read(KEY_REFRESH);
            if (TextUtils.isEmpty(refresh)) return null;
            String access = read(KEY_ACCESS);
            long expires = readLong(KEY_EXPIRES);
            if (!TextUtils.isEmpty(access) && System.currentTimeMillis() < expires - REFRESH_MARGIN_MS) {
                return access;
            }
            return refresh(refresh);
        }
    }

    /**
     * Adds the authenticated TV identity to one InnerTube request.
     *
     * @return {@code true} only when this request actually received a valid Bearer credential.
     *         A stored refresh token alone is not proof that the current request is authenticated.
     */
    static boolean apply(Map<String, String> headers) {
        if (headers == null) return false;
        String access = token();
        if (TextUtils.isEmpty(access)) return false;
        headers.put("Authorization", "Bearer " + access);
        headers.put("X-Goog-AuthUser", "0");
        String pageId = read(KEY_PAGE_ID);
        if (!TextUtils.isEmpty(pageId)) headers.put("X-Goog-Pageid", pageId);
        String cookies = read(KEY_COOKIES);
        if (!TextUtils.isEmpty(cookies)) headers.put("Cookie", cookies);
        headers.put("Referer", TV_PAGE);
        headers.put("Origin", "https://www.youtube.com");
        return true;
    }

    /**
     * @return the visitor id bound to the signed-in account, or {@code null} when unavailable.
     *
     * <p>Obtained from an authenticated TV bootstrap call, so the SABR session, the player response
     * and the account all share one identity. Cached for 12 hours.
     */
    static String visitorData() {
        if (!loggedIn()) return null;
        String cached = read(KEY_VISITOR);
        long fetchedAt = readLong(KEY_VISITOR_AT);
        if (YoutubeVisitor.usable(cached) && !TextUtils.isEmpty(read(KEY_PAGE_ID))
                && System.currentTimeMillis() - fetchedAt < VISITOR_TTL_MS) {
            return cached;
        }
        String fresh = bootstrap();
        if (YoutubeVisitor.usable(fresh)) {
            write(KEY_VISITOR, fresh);
            write(KEY_VISITOR_AT, String.valueOf(System.currentTimeMillis()));
            return fresh;
        }
        // A failed bootstrap must not invalidate a still-usable cached id; playback keeps working
        // with the previous one and the anonymous resolver covers the cold-start case.
        return YoutubeVisitor.usable(cached) ? cached : null;
    }

    /** Records the visitor id the server issued for an authenticated player response. */
    static void rememberVisitor(String value) {
        if (!loggedIn() || !YoutubeVisitor.usable(value)) return;
        if (value.equals(read(KEY_VISITOR))) return;
        write(KEY_VISITOR, value);
        write(KEY_VISITOR_AT, String.valueOf(System.currentTimeMillis()));
    }

    /* ------------------------------------------------------------------ */
    /* device flow                                                        */
    /* ------------------------------------------------------------------ */

    /** @return the in-flight authorisation, or {@code null} when none is waiting. */
    static Device pending() {
        Device device = pending;
        return device != null && device.alive() ? device : null;
    }

    /**
     * Starts a device authorisation and polls for the grant in the background.
     *
     * <p>Reuses the current code while it is still valid, so re-entering the login page does not
     * invalidate the code already shown on screen.
     */
    static synchronized Device begin() {
        Device current = pending();
        if (current != null) return current;
        Device device = new Device();
        String[] credentials = credentials();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", credentials[0]);
        form.put("scope", SCOPE);
        form.put("device_id", deviceId());
        form.put("device_model", "ytlr::");
        String body = http().postForm(DEVICE_URL, form, tvHeaders());
        JsonObject json = Json.safeObject(body);
        device.deviceCode = YouTubeLite.optString(json, "device_code", "");
        device.userCode = YouTubeLite.optString(json, "user_code", "");
        String url = YouTubeLite.optString(json, "verification_url", "");
        if (!TextUtils.isEmpty(url)) device.verificationUrl = url;
        long interval = YouTubeLite.optLong(json, "interval", 5);
        device.intervalMs = Math.max(2000L, interval * 1000L);
        long expires = YouTubeLite.optLong(json, "expires_in", 1800);
        device.expiresAt = System.currentTimeMillis() + Math.max(60L, expires) * 1000L;
        if (device.deviceCode.isEmpty() || device.userCode.isEmpty()) {
            device.state = "error";
            device.error = YouTubeLite.optString(json, "error_description",
                    YouTubeLite.optString(json, "error", "device-code-failed"));
            SpiderDebug.log("YouTube OAuth 取码失败: " + device.error);
            pending = null;
            return device;
        }
        pending = device;
        startPolling(device, credentials);
        SpiderDebug.log("YouTube OAuth 设备码已获取: code=" + device.userCode);
        return device;
    }

    private static void startPolling(Device device, String[] credentials) {
        Thread previous = poller;
        if (previous != null) previous.interrupt();
        Thread thread = new Thread(() -> pollLoop(device, credentials), "youtube-oauth-poll");
        thread.setDaemon(true);
        poller = thread;
        thread.start();
    }

    private static void pollLoop(Device device, String[] credentials) {
        while (device.alive() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(device.intervalMs);
            } catch (InterruptedException e) {
                return;
            }
            String result = exchange(device, credentials);
            if ("granted".equals(result)) {
                device.state = "granted";
                toast("YouTube 登录成功");
                SpiderDebug.log("YouTube OAuth 登录成功");
                return;
            }
            if ("pending".equals(result)) continue;
            if ("slow_down".equals(result)) {
                device.intervalMs += 2000L;
                continue;
            }
            device.state = "error";
            device.error = result;
            SpiderDebug.log("YouTube OAuth 授权失败: " + result);
            toast("YouTube 登录失败: " + result);
            return;
        }
        if (device.alive()) return;
        if ("pending".equals(device.state)) {
            device.state = "expired";
            SpiderDebug.log("YouTube OAuth 设备码已过期");
        }
    }

    /**
     * Exchanges the device code once.
     *
     * @return {@code granted}, {@code pending}, {@code slow_down}, or the server's error code.
     */
    private static String exchange(Device device, String[] credentials) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", credentials[0]);
        form.put("client_secret", credentials[1]);
        form.put("code", device.deviceCode);
        form.put("grant_type", GRANT_DEVICE);
        String body = http().postForm(TOKEN_URL, form, tvHeaders());
        JsonObject json = Json.safeObject(body);
        String access = YouTubeLite.optString(json, "access_token", "");
        String refresh = YouTubeLite.optString(json, "refresh_token", "");
        if (!access.isEmpty() && !refresh.isEmpty()) {
            store(access, refresh, YouTubeLite.optLong(json, "expires_in", 3600));
            return "granted";
        }
        String error = YouTubeLite.optString(json, "error", "");
        if (error.isEmpty()) return "empty-token-response";
        if ("authorization_pending".equals(error)) return "pending";
        if ("slow_down".equals(error)) return "slow_down";
        return error;
    }

    /** @return the refreshed access token, or {@code null} when the grant is gone. */
    private static String refresh(String refreshToken) {
        String[] credentials = credentials();
        Map<String, String> form = new LinkedHashMap<>();
        form.put("client_id", credentials[0]);
        form.put("client_secret", credentials[1]);
        form.put("refresh_token", refreshToken);
        form.put("grant_type", GRANT_REFRESH);
        String body = http().postForm(TOKEN_URL, form, tvHeaders());
        JsonObject json = Json.safeObject(body);
        String access = YouTubeLite.optString(json, "access_token", "");
        if (!access.isEmpty()) {
            String rotated = YouTubeLite.optString(json, "refresh_token", refreshToken);
            store(access, rotated, YouTubeLite.optLong(json, "expires_in", 3600));
            return access;
        }
        String error = YouTubeLite.optString(json, "error", "refresh-failed");
        SpiderDebug.log("YouTube OAuth 刷新令牌失败: " + error);
        // Only a revoked or invalid grant is terminal. A transient network failure must not sign the
        // user out, otherwise a brief outage silently drops playback back to the BotGuard path.
        if ("invalid_grant".equals(error) || "unauthorized_client".equals(error)
                || "invalid_client".equals(error)) {
            logout();
            toast("YouTube 登录已失效，请重新扫码登录");
        }
        return null;
    }

    private static void store(String access, String refresh, long expiresIn) {
        write(KEY_ACCESS, access);
        write(KEY_REFRESH, refresh);
        write(KEY_EXPIRES, String.valueOf(System.currentTimeMillis() + Math.max(60L, expiresIn) * 1000L));
    }

    /* ------------------------------------------------------------------ */
    /* TV bootstrap                                                       */
    /* ------------------------------------------------------------------ */

    /**
     * Asks the TV endpoint for a session as the signed-in account.
     *
     * <p>The visitor id arrives either in {@code responseContext} or inside the {@code _____} block
     * of a {@code Set-Cookie} value, depending on the endpoint's mood; both are accepted.
     */
    private static String bootstrap() {
        String access = token();
        if (TextUtils.isEmpty(access)) return null;
        JsonObject client = new JsonObject();
        client.addProperty("clientName", YoutubePlayer.CLIENT);
        client.addProperty("clientVersion", YoutubePlayer.DEFAULT_VERSION);
        client.addProperty("userAgent", YoutubePlayer.DEFAULT_UA);
        client.addProperty("hl", "en");
        client.addProperty("gl", "US");
        JsonObject context = new JsonObject();
        context.add("client", client);
        JsonObject payload = new JsonObject();
        payload.add("context", context);
        Map<String, String> headers = tvHeaders();
        // 7 is the Cobalt streamer id used inside SABR protobufs; the InnerTube
        // TVHTML5 HTTP header is client id 85.
        headers.put("X-YouTube-Client-Name", "85");
        headers.put("X-YouTube-Client-Version", YoutubePlayer.DEFAULT_VERSION);
        headers.put("User-Agent", YTSabr.cobaltUserAgent());
        apply(headers);
        YTHttp.Text response = http().postJsonText(TV_BOOTSTRAP, payload.toString(), headers);
        if (response == null) return null;
        JsonObject body = Json.safeObject(response.body);
        String fromJson = YouTubeLite.traverseString(body, "responseContext", "visitorData");
        // pg.jar extracts pageId/account metadata from accounts_list and persists it for
        // X-Goog-Pageid on every authenticated player request.
        String pageId = YouTubeLite.traverseString(body, "pageId");
        if (TextUtils.isEmpty(pageId)) pageId = findJsonString(response.body, "pageId");
        if (!TextUtils.isEmpty(pageId)) write(KEY_PAGE_ID, pageId);
        if (!response.cookies.isEmpty()) {
            String joined = joinCookies(response.cookies);
            if (!TextUtils.isEmpty(joined)) write(KEY_COOKIES, joined);
        }
        // pg.jar performs an accounts_list probe as part of the OAuth TV session health check.
        // Its response is where pageId is normally returned; do this even when /tv already gave
        // visitorData so the subsequent player request has the complete authentication context.
        fetchAccountMeta();
        if (YoutubeVisitor.usable(fromJson)) return fromJson;
        for (String cookie : response.cookies) {
            Matcher matcher = RE_VISITOR_COOKIE.matcher(cookie == null ? "" : cookie);
            if (!matcher.find()) continue;
            String value = decode(matcher.group(1));
            if (YoutubeVisitor.usable(value)) return value;
        }
        return null;
    }

    private static void fetchAccountMeta() {
        try {
            JsonObject payload = new JsonObject();
            JsonObject context = new JsonObject();
            JsonObject client = new JsonObject();
            client.addProperty("clientName", "WEB");
            client.addProperty("clientVersion", YoutubePlayer.DEFAULT_VERSION);
            context.add("client", client);
            Map<String, String> headers = tvHeaders();
            // 7 belongs to the Cobalt SABR streamer context, not this HTTP request.
            headers.put("X-YouTube-Client-Name", "85");
            headers.put("X-YouTube-Client-Version", YTSabr.cobaltVersion());
            headers.put("User-Agent", "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version");
            if (!apply(headers)) return;
            YTHttp.Text result = http().postJsonText(ACCOUNT_LIST, payload.toString(), headers);
            if (result == null || result.code < 200 || result.code >= 300) return;
            if (!result.cookies.isEmpty()) {
                String joined = joinCookies(result.cookies);
                if (!TextUtils.isEmpty(joined)) write(KEY_COOKIES, joined);
            }
            String pageId = findJsonString(result.body, "pageId");
            if (!TextUtils.isEmpty(pageId)) write(KEY_PAGE_ID, pageId);
            String visitor = YouTubeLite.traverseString(Json.safeObject(result.body),
                    "responseContext", "visitorData");
            if (YoutubeVisitor.usable(visitor)) {
                write(KEY_VISITOR, visitor);
                write(KEY_VISITOR_AT, String.valueOf(System.currentTimeMillis()));
            }
            SpiderDebug.log("YouTube OAuth TV accounts_list 会话探测成功: pageId="
                    + (!TextUtils.isEmpty(pageId)) + ", visitor=" + (YoutubeVisitor.usable(visitor)));
        } catch (Throwable e) {
            SpiderDebug.log("YouTube OAuth TV accounts_list 探测失败: " + e.getClass().getSimpleName());
        }
    }

    private static String joinCookies(List<String> cookies) {
        StringBuilder out = new StringBuilder();
        for (String raw : cookies) {
            if (TextUtils.isEmpty(raw)) continue;
            String first = raw;
            int semi = first.indexOf(';');
            if (semi >= 0) first = first.substring(0, semi);
            if (out.length() > 0) out.append("; ");
            out.append(first);
        }
        return out.toString();
    }

    private static String findJsonString(String body, String key) {
        if (TextUtils.isEmpty(body)) return "";
        try {
            String needle = "\"" + key + "\"";
            int at = body.indexOf(needle);
            if (at < 0) return "";
            int colon = body.indexOf(':', at + needle.length());
            int q1 = body.indexOf('\"', colon + 1);
            int q2 = body.indexOf('\"', q1 + 1);
            return q1 >= 0 && q2 > q1 ? body.substring(q1 + 1, q2) : "";
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static String decode(String value) {
        try {
            return URLDecoder.decode(value, "UTF-8");
        } catch (Throwable e) {
            return value;
        }
    }

    /* ------------------------------------------------------------------ */
    /* credentials                                                        */
    /* ------------------------------------------------------------------ */

    /**
     * @return {@code {clientId, clientSecret}}, read from the live TV bundle when possible.
     *
     * <p>Scraping keeps working if YouTube rotates the TV client; the constants are the fallback.
     */
    private static String[] credentials() {
        String id = clientId;
        String secret = clientSecret;
        if (!TextUtils.isEmpty(id) && !TextUtils.isEmpty(secret)) return new String[]{id, secret};
        try {
            String page = http().string(TV_PAGE, tvHeaders());
            String[] found = scan(page);
            if (found == null) {
                List<String> scripts = scripts(page);
                for (String script : scripts) {
                    found = scan(http().string("https://www.youtube.com" + script, tvHeaders()));
                    if (found != null) break;
                }
            }
            if (found != null) {
                clientId = found[0];
                clientSecret = found[1];
                SpiderDebug.log("YouTube OAuth 凭据已从 TV 页面提取");
                return found;
            }
        } catch (Throwable e) {
            SpiderDebug.log("YouTube OAuth 凭据提取失败: " + e);
        }
        clientId = FALLBACK_CLIENT_ID;
        clientSecret = FALLBACK_CLIENT_SECRET;
        return new String[]{FALLBACK_CLIENT_ID, FALLBACK_CLIENT_SECRET};
    }

    private static String[] scan(String code) {
        if (TextUtils.isEmpty(code)) return null;
        Matcher id = RE_CLIENT_ID.matcher(code);
        Matcher secret = RE_CLIENT_SECRET.matcher(code);
        if (!id.find() || !secret.find()) return null;
        return new String[]{id.group(1), secret.group(1)};
    }

    /** @return at most three script paths from the TV page, newest-looking first. */
    private static List<String> scripts(String page) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(page)) return out;
        Matcher matcher = RE_JS.matcher(page);
        while (matcher.find() && out.size() < 3) {
            String path = matcher.group(1);
            if (!out.contains(path)) out.add(path);
        }
        return out;
    }

    private static Map<String, String> tvHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", YoutubePlayer.DEFAULT_UA);
        headers.put("Referer", TV_PAGE);
        headers.put("Origin", "https://www.youtube.com");
        headers.put("Accept-Language", "en-US,en;q=0.9");
        return headers;
    }

    /** A stable pseudo device id; the endpoint only requires the field to be present. */
    private static String deviceId() {
        String stored = read("yt_oauth_device_id");
        if (!TextUtils.isEmpty(stored)) return stored;
        StringBuilder sb = new StringBuilder();
        String alphabet = "0123456789abcdef";
        java.util.Random random = new java.util.Random();
        for (int i = 0; i < 32; i++) sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        write("yt_oauth_device_id", sb.toString());
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* storage                                                            */
    /* ------------------------------------------------------------------ */

    private static String read(String key) {
        try {
            String value = Prefers.getString(key, "");
            if (!TextUtils.isEmpty(value)) return value;
        } catch (Throwable ignored) {
            // The host has not called Init.init; the in-memory mirror is the only store.
        }
        String value = MEMORY.get(key);
        return value == null ? "" : value;
    }

    private static long readLong(String key) {
        try {
            return Long.parseLong(read(key).trim());
        } catch (Throwable e) {
            return 0L;
        }
    }

    /**
     * Toast that cannot take the flow down.
     *
     * <p>The poller runs on a background thread, and {@link Notify} needs the host to have called
     * {@code Init.init}; if it has not, showing a message must not throw away a granted token.
     */
    private static void toast(String message) {
        try {
            Notify.show(message);
        } catch (Throwable ignored) {
            // No host context; the log line above is the only feedback available.
        }
    }

    private static void write(String key, String value) {
        if (value == null) value = "";
        MEMORY.put(key, value);
        try {
            Prefers.put(key, value);
        } catch (Throwable ignored) {
            // Kept in memory only; the user stays signed in until the process dies.
        }
    }
}
