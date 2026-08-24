package com.github.catvod.spider;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * YouTube catalog source with JAR-owned SABR playback.
 *
 * <p>Browsing runs on InnerTube JSON. Playback returns a local DASH URL and all media requests
 * are handled by this JAR's {@link #proxy(Map)} implementation.
 */
public class YouTube extends Spider {

    private static final String CHROME_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final String DEFAULT_CLIENT_VERSION = "2.20240310.01.00";
    private static final long PLAYLIST_CACHE_MS = 1200 * 1000L;

    private JsonObject ext = new JsonObject();
    private Map<String, String> header = new HashMap<>();
    private YTHttp http;
    private YouTubeLite yt;
    private YTPlay play;
    private YoutubeSession session;
    private YoutubeProxy youtubeProxy;
    private String proxyStr;
    /** Whether {@code vod_pic} is rewritten through this spider; see {@link YTImage}. */
    private boolean proxyImage;
    private JsonObject externalCatalog;
    /** True once the catalog has been loaded; a failed attempt leaves it false so it is retried. */
    private volatile boolean catalogLoaded;

    private final Map<String, SearchSession> searchCache = new HashMap<>();
    private final Map<String, YTParse.Playlist> playlistCache = new HashMap<>();
    private final Map<String, String> titleCache = new HashMap<>();
    private final AtomicLong playbackGeneration = new AtomicLong();

    /** Paged search state: pages are appended as continuations are followed. */
    private static class SearchSession {
        String apiKey = "";
        JsonObject context = new JsonObject();
        String clientName = "WEB";
        String clientVersion = DEFAULT_CLIENT_VERSION;
        String referer = "https://www.youtube.com/";
        List<List<YTParse.Item>> pages = new ArrayList<>();
        String next = "";
    }

    @Override
    public void init(Context context, String extend) {
        this.ext = Json.safeObject(extend);
        this.header = new HashMap<>();
        header.put("User-Agent", CHROME_UA);
        header.put("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        header.put("Referer", "https://www.youtube.com/");
        this.proxyStr = readProxy();
        this.http = new YTHttp(header, proxyStr);
        this.proxyImage = readProxyImage();
        // Deliberately not loaded here; see catalog(). init() can run before the host's local file
        // service is listening, and a failure at that instant must not become permanent.
        this.externalCatalog = null;
        this.catalogLoaded = false;
        this.yt = new YouTubeLite(context, http, header, ext);
        this.play = new YTPlay(yt, header, ext, siteKey);
        this.session = new YoutubeSession(context, ext);
        this.youtubeProxy = new YoutubeProxy(play);
    }

    /**
     * The external catalog, loaded on first use and retried until it succeeds.
     *
     * <p>Loading in {@code init()} was wrong. The host constructs the Spider as part of rebuilding
     * the catalog, and when {@code ext.json} points at a local file the host serves it from its own
     * {@code 127.0.0.1:9978/file/...} endpoint — which may not be listening yet at that instant, and
     * for a remote URL the proxy may not be up either. A miss then stuck: {@code externalCatalog}
     * stayed null for the Spider's whole lifetime, so the built-in list was used until the user
     * refreshed or switched sites to force a rebuild. Configuring {@code ext.json} should simply
     * mean the external catalog is used.
     *
     * <p>So the attempt is deferred to the first request that needs it and repeated while it keeps
     * failing. Success is latched, so the normal case is still exactly one load.
     */
    private JsonObject catalog() {
        if (catalogLoaded) return externalCatalog;
        synchronized (this) {
            if (catalogLoaded) return externalCatalog;
            // Nothing configured: latch immediately, the built-in catalog is the intended answer.
            JsonElement value = ext == null ? null : ext.get("json");
            if (value == null || value.isJsonNull()) {
                catalogLoaded = true;
                return null;
            }
            JsonObject loaded = readCatalog();
            if (loaded != null) {
                externalCatalog = loaded;
                catalogLoaded = true;
                return externalCatalog;
            }
            // Leave catalogLoaded false so the next request tries again.
            SpiderDebug.log("YouTube 外部分类: 本次未取到，下次请求将重试（暂用内置分类）");
            return null;
        }
    }

    /**
     * Loads a standard CatVod class/filter JSON from {@code ext.json}.
     *
     * <p>Accepts three source kinds, so a site that filters requests can be bypassed entirely by
     * downloading the file once and pointing at it locally:
     * <pre>
     * "json": "https://host/youtube.json"     // remote, fetched through ext.proxy
     * "json": "/sdcard/TV/youtube.json"       // local file (also file:// and ./relative)
     * "json": {"class": [...]}                // inline, no I/O at all
     * </pre>
     *
     * <p>Every failure is logged with its reason. This used to return {@code null} silently, so a
     * bad path, an unreadable file or a rejected download all looked identical to "not configured"
     * — the catalog quietly fell back to the built-in list with nothing in the log to explain why.
     *
     * @return the parsed catalog, or {@code null} to use the built-in one.
     */
    private JsonObject readCatalog() {
        JsonElement value = ext.get("json");
        if (value == null || value.isJsonNull()) return null;
        // An inline object needs no loading.
        if (value.isJsonObject()) {
            JsonObject root = value.getAsJsonObject();
            if (YTExternalCatalog.valid(root)) {
                SpiderDebug.log("YouTube 外部分类: 使用内联 JSON, 分类数=" + YTExternalCatalog.classes(root).size());
                return root;
            }
            SpiderDebug.log("YouTube 外部分类失败: 内联 JSON 缺少 class 数组");
            return null;
        }
        String source = value.isJsonPrimitive() ? value.getAsString().trim() : "";
        if (source.isEmpty()) {
            SpiderDebug.log("YouTube 外部分类失败: ext.json 为空");
            return null;
        }
        String json;
        try {
            json = catalogSource(source);
        } catch (Throwable error) {
            SpiderDebug.log("YouTube 外部分类失败: 读取异常 " + error);
            return null;
        }
        if (json == null || json.trim().isEmpty()) {
            SpiderDebug.log("YouTube 外部分类失败: 内容为空 " + source);
            return null;
        }
        JsonObject root = Json.safeObject(json);
        if (!YTExternalCatalog.valid(root)) {
            // Report what actually arrived: an HTML error page or a WAF challenge is the common
            // case, and truncating keeps a 39KB body out of the log.
            String head = json.trim();
            if (head.length() > 120) head = head.substring(0, 120);
            SpiderDebug.log("YouTube 外部分类失败: 不是合法分类 JSON(缺少 class 数组), 开头=" + head);
            return null;
        }
        SpiderDebug.log("YouTube 外部分类已加载: " + source
                + ", 分类数=" + YTExternalCatalog.classes(root).size());
        return root;
    }

    /** Resolves {@code ext.json} to raw text, from the network or the filesystem. */
    private String catalogSource(String source) throws Exception {
        if (source.startsWith("http://") || source.startsWith("https://")) {
            // A plain UA is enough for a static file host, but some reject it; the spider's normal
            // browser headers are already applied by YTHttp.
            String body = http.string(source, null, 20000L);
            // Choosing a local directory makes the host serve the file from its own loopback
            // endpoint (127.0.0.1:9978/file/...), which can lose a race against this request during
            // a catalog rebuild. That is a startup race, not a rejection, so retry briefly rather
            // than falling back to the built-in catalog for a file that is sitting on disk.
            if ((body == null || body.isEmpty()) && loopback(source)) {
                for (int attempt = 0; attempt < 3 && (body == null || body.isEmpty()); attempt++) {
                    try {
                        Thread.sleep(300L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    body = http.string(source, null, 20000L);
                }
                if (body != null && !body.isEmpty()) {
                    SpiderDebug.log("YouTube 外部分类: 本机服务重试后取到 " + source);
                }
            }
            if (body == null || body.isEmpty()) {
                SpiderDebug.log("YouTube 外部分类: 远程下载失败或被拒绝 " + source
                        + "（可下载到本机后改用本地路径）");
            }
            return body;
        }
        // Inline JSON pasted directly into the value.
        if (source.startsWith("{")) return source;
        return readFile(source);
    }

    /** True for a URL served by this device, where a failure is likely a startup race. */
    private static boolean loopback(String url) {
        return url.contains("127.0.0.1") || url.contains("localhost") || url.contains("[::1]");
    }

    /**
     * Reads a local catalog file.
     *
     * <p>{@code file://} URIs, absolute paths and {@code ./relative} paths are all accepted, since
     * the value is typed by hand. Kept deliberately simple: a JSON catalog is tens of KB.
     */
    private static String readFile(String source) throws Exception {
        String path = source;
        if (path.startsWith("file://")) path = path.substring(7);
        // Decode %20 and friends from a path copied out of a file manager.
        if (path.indexOf('%') >= 0) {
            try {
                path = Uri.decode(path);
            } catch (Throwable ignored) {
                // Keep the raw path if it was not percent-encoded after all.
            }
        }
        java.io.File file = new java.io.File(path);
        if (!file.exists()) {
            SpiderDebug.log("YouTube 外部分类失败: 文件不存在 " + path
                    + "（确认路径与读取权限，Android 11+ 建议放应用可访问目录）");
            return null;
        }
        if (!file.canRead()) {
            SpiderDebug.log("YouTube 外部分类失败: 文件无法读取(权限) " + path);
            return null;
        }
        if (file.length() <= 0) {
            SpiderDebug.log("YouTube 外部分类失败: 文件为空 " + path);
            return null;
        }
        byte[] data = new byte[(int) Math.min(file.length(), 8 * 1024 * 1024L)];
        java.io.FileInputStream in = new java.io.FileInputStream(file);
        try {
            int offset = 0;
            while (offset < data.length) {
                int read = in.read(data, offset, data.length - offset);
                if (read < 0) break;
                offset += read;
            }
            // Strip a UTF-8 BOM, which a Windows-saved file carries and JsonParser rejects.
            int start = data.length >= 3 && (data[0] & 0xFF) == 0xEF
                    && (data[1] & 0xFF) == 0xBB && (data[2] & 0xFF) == 0xBF ? 3 : 0;
            return new String(data, start, offset - start, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            try {
                in.close();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Reads the proxy from {@code ext.proxy}. Nothing is guessed.
     *
     * <p>Port probing was removed deliberately. Guessing from a candidate list picked whichever
     * port happened to be listening, which is not necessarily the one the user selected: a device
     * can have several local proxies up at once (a client's mixed port, a per-node port, another
     * JAR's downloader) and the probe cannot tell which one carries the node the user chose. It
     * also made the effective route invisible — nothing in the config said where traffic went.
     * The proxy is now configuration, so it is explicit, stable across restarts, and reviewable.
     *
     * <p>Accepted forms, all equivalent:
     * <pre>
     * "proxy": "127.0.0.1:10172"
     * "proxy": "http://127.0.0.1:10172"
     * "proxy": {"http": "127.0.0.1:10172"}
     * "proxy": {"host": "127.0.0.1", "port": 10172}
     * </pre>
     *
     * @return {@code host:port}, or {@code null} for a direct connection.
     */
    private String readProxy() {
        String text = normalizeProxy(rawProxy());
        if (text.isEmpty()) {
            SpiderDebug.log("YouTube 未配置代理: 直连。需要代理请在 ext 写 \"proxy\": \"127.0.0.1:端口\"");
            return null;
        }
        if (!validProxy(text)) {
            SpiderDebug.log("YouTube 代理配置无效，已按直连处理: " + text + "（应为 host:port）");
            return null;
        }
        SpiderDebug.log("YouTube 使用配置代理: " + text);
        return text;
    }

    /** Pulls the raw {@code ext.proxy} value in any of its accepted shapes. */
    private String rawProxy() {
        JsonElement value = ext.get("proxy");
        if (value == null || value.isJsonNull()) return "";
        if (value.isJsonPrimitive()) return value.getAsString();
        if (!value.isJsonObject()) return "";
        JsonObject obj = value.getAsJsonObject();
        String text = YouTubeLite.optString(obj, "http", YouTubeLite.optString(obj, "https", ""));
        if (!text.isEmpty()) return text;
        String host = YouTubeLite.optString(obj, "host", "");
        String port = YouTubeLite.optString(obj, "port", "");
        return host.isEmpty() || port.isEmpty() ? "" : host + ":" + port;
    }

    private static String normalizeProxy(String text) {
        if (text == null) return "";
        String value = text.trim();
        if (value.startsWith("http://")) value = value.substring(7);
        else if (value.startsWith("https://")) value = value.substring(8);
        // Tolerate a trailing slash from a pasted URL.
        while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
        return value.trim();
    }

    /**
     * Whether posters should be fetched through this spider instead of by the host directly.
     *
     * <p>Defaults to on whenever a proxy is configured: if YouTube needs a proxy to reach, its
     * image CDN almost always does too, and the host's image loader has no proxy of its own.
     * Override with {@code "proxy_image": false} (or {@code true} to force it while direct).
     */
    private boolean readProxyImage() {
        JsonElement value = ext.get("proxy_image");
        if (value != null && !value.isJsonNull() && value.isJsonPrimitive()) {
            String text = value.getAsString().trim().toLowerCase();
            boolean enabled = !("false".equals(text) || "0".equals(text) || "off".equals(text) || "no".equals(text));
            SpiderDebug.log("YouTube 图片代理: " + (enabled ? "开启" : "关闭") + "(配置)");
            return enabled;
        }
        boolean enabled = proxyStr != null;
        SpiderDebug.log("YouTube 图片代理: " + (enabled ? "开启" : "关闭") + "(跟随代理设置)");
        return enabled;
    }

    /** Rewrites one poster URL for delivery through this spider. */
    private String pic(String url) {
        return YTImage.wrap(siteKey, url, proxyImage);
    }

    /** Rejects a malformed value up front so it is reported once instead of failing every call. */
    private static boolean validProxy(String text) {
        int colon = text.lastIndexOf(':');
        if (colon <= 0 || colon == text.length() - 1) return false;
        try {
            int port = Integer.parseInt(text.substring(colon + 1).trim());
            return port > 0 && port <= 65535;
        } catch (Throwable ignored) {
            return false;
        }
    }

    @Override
    public String homeContent(boolean filter) {
        JsonObject external = catalog();
        List<Class> classes = external == null ? YTCatalog.classes() : YTExternalCatalog.classes(external);
        if (!filter) return Result.string(classes, new ArrayList<>());
        LinkedHashMap<String, List<Filter>> filters = external == null
                ? YTCatalog.filters() : YTExternalCatalog.filters(external);
        return Result.string(classes, filters);
    }

    @Override
    public String homeVideoContent() {
        return Result.string(new ArrayList<Vod>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        int page = parsePage(pg);
        JsonObject external = catalog();
        String query = external == null
                ? YTCatalog.keyword(tid, extend)
                : YTExternalCatalog.keyword(external, tid, extend);
        List<YTParse.Item> items = searchPage(query, page);
        boolean hasMore = hasMore(query, page);
        return list(items, page, hasMore);
    }

    @Override
    public String searchContent(String key, boolean quick) {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) {
        String playlistId = YTParse.playlistId(key);
        if (!playlistId.isEmpty()) {
            try {
                YTParse.Playlist playlist = playlist(playlistId, false);
                return Result.get().vod(playlistVod(playlist)).page(1, 1, 1, 1).string();
            } catch (Throwable ignored) {
                // Not a reachable playlist; fall through to a normal keyword search.
            }
        }
        int page = parsePage(pg);
        List<YTParse.Item> items = searchPage(key, page);
        return list(items, page, hasMore(key, page));
    }

    @Override
    public String detailContent(List<String> ids) {
        String rawId = ids.get(0);
        String playlistId = YTParse.playlistId(rawId);
        if (!playlistId.isEmpty()) return playlistDetail(playlistId);
        String videoId = rawId.startsWith("v:") ? rawId.substring(2) : rawId;
        // Detail is metadata-only. Avoid player/BotGuard/related-video work here so a
        // slow YouTube response cannot consume the host detail timeout.
        String title;
        synchronized (titleCache) {
            title = titleCache.get(videoId);
        }
        if (TextUtils.isEmpty(title)) {
            // History/detail must remain metadata-only. An oEmbed request here can block the
            // host's detail timeout and make opening a history item look like playback failed.
            title = videoId;
            synchronized (titleCache) {
                titleCache.put(videoId, title);
            }
        }
        String safeTitle = YTParse.safeTitle(title);
        List<String> playFrom = new ArrayList<>();
        List<String> playUrl = new ArrayList<>();
        playFrom.add("Youtube");
        playUrl.add(safeTitle + "$" + videoId);
        Vod vod = new Vod();
        vod.setVodId(videoId);
        vod.setVodName(title);
        vod.setVodPic(pic(YTParse.thumbnail(videoId)));
        vod.setVodPlayFrom(TextUtils.join("$$$", playFrom));
        vod.setVodPlayUrl(TextUtils.join("$$$", playUrl));
        return Result.string(vod);
    }

    /**
     * Starts playback through the JAR-owned SABR bridge.
     *
     * <p>The player receives one local DASH manifest whose segment requests return to
     * {@link #proxy(Map)}, where {@link YTPlay} answers them from a SABR session using the
     * micro-window scheme.
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) {
        String[] parts = id.split("\\$");
        String rawPid = parts[parts.length - 1];
        String videoId = rawPid;
        int at = rawPid.lastIndexOf('@');
        if (at > 0) videoId = rawPid.substring(0, at);
        String quality = YouTubeLite.optString(ext, "quality", "best");
        String sid = String.valueOf(playbackGeneration.incrementAndGet());
        // The top-level manifest URL deliberately stays on the host's proxy:// scheme. Handing the
        // host a raw http://127.0.0.1 URL makes it classify playback as EXTERNAL_LOOPBACK_PROXY
        // ("evidence=unregistered-loopback-port") and run a blocking readiness probe before every
        // prepare, which cannot succeed for a port the host does not own: observed 25 attempts /
        // 5067ms of dead time on each attempt. proxy:// is classified as route=OTHER with no gate.
        //
        // Segment URLs inside the manifest are a different matter and do use the JAR-owned server
        // (see YTPlay.localUrl): they are what must survive the host's jar-loader clear, and they
        // are fetched by the player directly without going through that readiness gate.
        String params = "&type=sabr_mpd&vid=" + Uri.encode(videoId)
                + "&quality=" + Uri.encode(quality) + "&sid=" + sid;
        return Result.get().url(Proxy.getUrl(siteKey, params)).dash().string();
    }

    @Override
    public Object[] proxy(Map<String, String> params) {
        // Thumbnails are answered here rather than in YTPlay: they are unrelated to a playback
        // session, so a poster must still load when no video is playing.
        Object[] result = params != null && "img".equals(params.get("type"))
                ? YTImage.serve(http, params)
                : (youtubeProxy == null ? null : youtubeProxy.handle(params));
        // Single choke point for the status code. The host maps it onto NanoHTTPD's Status enum and
        // an unrepresentable value (502/504 among others) becomes null, which does not fail the
        // request — it throws java.lang.Error inside the host's response writer and takes the app
        // down. Sanitising here means no future route can reintroduce that crash.
        if (result != null && result.length > 0 && result[0] instanceof Integer) {
            int code = (Integer) result[0];
            int safe = YTImage.safeCode(code);
            if (safe != code) {
                SpiderDebug.log("YouTube proxy 状态码 " + code + " 宿主不支持, 已改为 " + safe);
                result[0] = safe;
            }
        }
        return result;
    }

    @Override
    public void destroy() {
        // The host may destroy the catalog loader while the player still owns this Spider.
        // YTPlay keeps the proxy/session alive during its grace period; do not null the proxy or
        // close OkHttp here, otherwise the player's in-flight URL is killed immediately.
        if (play != null) play.destroy();
    }

    @Override
    public boolean manualVideoCheck() {
        return false;
    }

    @Override
    public boolean isVideoFormat(String url) {
        return url.contains("googlevideo.com/videoplayback") || url.contains(".m3u8") || url.contains(".mpd");
    }

    /* ------------------------------------------------------------------ */
    /* episodes                                                           */
    /* ------------------------------------------------------------------ */

    /**
     * Converts cards into {@code 名称$video_id} episode strings.
     *
     * <p>Playlist cards ({@code pl:} ids) are not playable on their own and are skipped.
     */
    private List<String> episodes(List<YTParse.Item> items, String excludeId) {
        List<String> episodes = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (YTParse.Item item : items) {
            String vid = item.vodId == null ? "" : item.vodId.trim();
            if (vid.isEmpty() || vid.startsWith("pl:") || vid.equals(excludeId) || seen.contains(vid)) continue;
            seen.add(vid);
            episodes.add(YTParse.safeTitle(item.name == null ? vid : item.name) + "$" + vid);
        }
        return episodes;
    }

    /* ------------------------------------------------------------------ */
    /* search                                                             */
    /* ------------------------------------------------------------------ */

    private static int parsePage(String pg) {
        try {
            return Math.max(1, Integer.parseInt(pg));
        } catch (Throwable e) {
            return 1;
        }
    }

    private static String cacheKey(String key) {
        return (key == null ? "" : key).replaceAll("\\s+", " ").trim().toLowerCase();
    }

    /** Walks continuations until the requested page exists, then returns it. */
    private List<YTParse.Item> searchPage(String key, int page) {
        String cacheKey = cacheKey(key);
        SearchSession session = searchCache.get(cacheKey);
        if (page == 1 || session == null) {
            session = firstPage(key);
            searchCache.put(cacheKey, session);
        }
        while (session.pages.size() < page && !session.next.isEmpty()) {
            JsonObject data = continuation(session);
            session.pages.add(YTParse.items(data, 30));
            session.next = YTParse.continuation(data);
        }
        return session.pages.size() >= page ? session.pages.get(page - 1) : new ArrayList<>();
    }

    private boolean hasMore(String key, int page) {
        SearchSession session = searchCache.get(cacheKey(key));
        if (session == null) return false;
        return !session.next.isEmpty() || session.pages.size() > page;
    }

    private SearchSession firstPage(String key) {
        String url = "https://www.youtube.com/results?search_query=" + Uri.encode(key == null ? "" : key);
        String page = http.string(url);
        JsonObject data = yt.extractJsonAfter(page, "ytInitialData");
        JsonObject ytcfg = yt.extractYtcfg(page);
        SearchSession session = new SearchSession();
        session.apiKey = apiKey(ytcfg, page);
        session.context = context(ytcfg);
        JsonObject client = YouTubeLite.traverseObject(session.context, "client");
        session.clientName = client == null ? "WEB" : YouTubeLite.optString(client, "clientName", "WEB");
        session.clientVersion = client == null ? DEFAULT_CLIENT_VERSION
                : YouTubeLite.optString(client, "clientVersion", DEFAULT_CLIENT_VERSION);
        session.referer = url;
        session.pages.add(YTParse.items(data, 30));
        session.next = YTParse.continuation(data);
        return session;
    }

    private JsonObject continuation(SearchSession session) {
        if (session.next.isEmpty() || session.apiKey.isEmpty()) return new JsonObject();
        String url = "https://www.youtube.com/youtubei/v1/search?key=" + Uri.encode(session.apiKey);
        JsonObject payload = new JsonObject();
        payload.add("context", session.context);
        payload.addProperty("continuation", session.next);
        try {
            String json = http.postJson(url, new Gson().toJson(payload),
                    innerTubeHeaders(session.clientName, session.clientVersion, session.referer));
            return Json.safeObject(json);
        } catch (Throwable e) {
            return new JsonObject();
        }
    }

    private Map<String, String> innerTubeHeaders(String clientName, String clientVersion, String referer) {
        Map<String, String> headers = new HashMap<>(header);
        headers.put("Content-Type", "application/json");
        headers.put("Origin", "https://www.youtube.com");
        headers.put("Referer", referer);
        headers.put("X-YouTube-Client-Name", String.valueOf(yt.clientNameId(clientName)));
        headers.put("X-YouTube-Client-Version", clientVersion);
        return headers;
    }

    private String apiKey(JsonObject ytcfg, String page) {
        String key = ytcfg == null ? "" : YouTubeLite.optString(ytcfg, "INNERTUBE_API_KEY", "");
        if (!key.isEmpty()) return key;
        String found = YouTubeLite.search(java.util.regex.Pattern
                .compile("\"INNERTUBE_API_KEY\":\"([^\"]+)\""), page);
        return found == null ? "" : found;
    }

    private JsonObject context(JsonObject ytcfg) {
        JsonObject context = ytcfg == null ? null : YouTubeLite.traverseObject(ytcfg, "INNERTUBE_CONTEXT");
        if (context != null && context.size() > 0) return context;
        JsonObject client = new JsonObject();
        client.addProperty("clientName", "WEB");
        client.addProperty("clientVersion", DEFAULT_CLIENT_VERSION);
        client.addProperty("hl", "zh-CN");
        client.addProperty("gl", "US");
        JsonObject fallback = new JsonObject();
        fallback.add("client", client);
        return fallback;
    }

    private String list(List<YTParse.Item> items, int page, boolean hasMore) {
        List<Vod> list = new ArrayList<>();
        // Rewrite on the item, not the Vod: Vod exposes no pic getter, so the value has to be
        // wrapped before conversion.
        for (YTParse.Item item : items) {
            item.pic = pic(item.pic);
            list.add(item.toVod());
        }
        int count = hasMore ? page + 1 : page;
        return Result.get().vod(list).page(page, count, list.size(), list.size()).string();
    }

    /* ------------------------------------------------------------------ */
    /* playlists                                                          */
    /* ------------------------------------------------------------------ */

    private Vod playlistVod(YTParse.Playlist playlist) {
        int count = playlist.count > 0 ? playlist.count : playlist.videos.size();
        Vod vod = new Vod();
        vod.setVodId("pl:" + playlist.playlistId);
        vod.setVodName(playlist.title.isEmpty() ? playlist.playlistId : playlist.title);
        vod.setVodPic(pic(playlist.pic));
        vod.setVodRemarks(count > 0 ? count + " videos" : "YouTube播放列表");
        vod.setStyle(Vod.Style.rect(16.0f / 9.0f));
        return vod;
    }

    private String playlistDetail(String playlistId) {
        YTParse.Playlist playlist;
        try {
            playlist = playlist(playlistId, true);
        } catch (Throwable e) {
            return Result.error("无法读取 YouTube 播放列表数据");
        }
        List<YTParse.Entry> videos = playlist.videos;
        if (videos.isEmpty()) return Result.error("播放列表中没有可用视频");
        int width = Math.max(2, String.valueOf(videos.size()).length());
        List<String> episodes = new ArrayList<>();
        for (int position = 1; position <= videos.size(); position++) {
            YTParse.Entry entry = videos.get(position - 1);
            int number = entry.index > 0 ? entry.index : position;
            String title = YTParse.safeTitle(entry.title == null || entry.title.isEmpty()
                    ? "视频 " + position : entry.title);
            String name = pad(number, width) + " " + title;
            if (entry.live) name = "【正在直播】" + name;
            episodes.add(name.trim() + "$" + entry.videoId + (entry.live ? "@live" : ""));
        }
        List<String> content = new ArrayList<>();
        if (!playlist.owner.isEmpty()) content.add("频道：" + playlist.owner);
        if (!playlist.description.isEmpty()) content.add(playlist.description);
        if (playlist.truncated) content.add("播放列表过长，当前显示前 " + videos.size() + " 个视频。");
        Vod vod = new Vod();
        vod.setVodId("pl:" + playlistId);
        vod.setVodName(playlist.title.isEmpty() ? playlistId : playlist.title);
        vod.setVodPic(pic(playlist.pic.isEmpty() ? videos.get(0).pic : playlist.pic));
        vod.setVodRemarks(videos.size() + " videos");
        vod.setVodContent(TextUtils.join("\n", content));
        vod.setVodPlayFrom("YouTube自动");
        vod.setVodPlayUrl(TextUtils.join("#", episodes));
        return Result.string(vod);
    }

    private static String pad(int number, int width) {
        String text = String.valueOf(number);
        StringBuilder sb = new StringBuilder();
        for (int i = text.length(); i < width; i++) sb.append('0');
        return sb + text;
    }

    /** Loads a playlist, following continuations up to {@code playlist_max_items} entries. */
    private YTParse.Playlist playlist(String playlistId, boolean includeVideos) throws Exception {
        YTParse.Playlist cached = playlistCache.get(playlistId);
        if (cached != null && System.currentTimeMillis() - cached.cachedAt < PLAYLIST_CACHE_MS
                && (!includeVideos || cached.complete)) {
            return cached;
        }
        String url = "https://www.youtube.com/playlist?list=" + Uri.encode(playlistId);
        String page = http.string(url);
        JsonObject data = yt.extractJsonAfter(page, "ytInitialData");
        if (data == null || data.size() == 0) throw new Exception("无法读取 YouTube 播放列表数据");
        YTParse.Playlist playlist = YTParse.playlistMeta(data, playlistId);
        JsonObject ytcfg = yt.extractYtcfg(page);
        JsonObject context = context(ytcfg);
        JsonObject client = YouTubeLite.traverseObject(context, "client");
        String clientName = client == null ? "WEB" : YouTubeLite.optString(client, "clientName", "WEB");
        String clientVersion = client == null ? DEFAULT_CLIENT_VERSION
                : YouTubeLite.optString(client, "clientVersion", DEFAULT_CLIENT_VERSION);
        String key = apiKey(ytcfg, page);
        String token = YTParse.continuation(data);
        int maxItems = (int) Math.max(1, YouTubeLite.optLong(ext, "playlist_max_items", 500));
        Set<String> seenTokens = new HashSet<>();
        while (includeVideos && !token.isEmpty() && !seenTokens.contains(token)
                && playlist.videos.size() < maxItems) {
            seenTokens.add(token);
            JsonObject more = browse(key, context, token, clientName, clientVersion, url);
            List<YTParse.Entry> items = YTParse.playlistVideos(more, playlist.videos.size() + 1);
            for (YTParse.Entry entry : items) {
                if (playlist.videos.size() >= maxItems) break;
                playlist.videos.add(entry);
            }
            token = YTParse.continuation(more);
        }
        playlist.complete = includeVideos && token.isEmpty();
        playlist.truncated = includeVideos && !token.isEmpty() && playlist.videos.size() >= maxItems;
        playlist.cachedAt = System.currentTimeMillis();
        if (playlist.count == 0) playlist.count = playlist.videos.size();
        playlistCache.put(playlistId, playlist);
        return playlist;
    }

    private JsonObject browse(String apiKey, JsonObject context, String token,
                              String clientName, String clientVersion, String referer) {
        if (apiKey.isEmpty() || token.isEmpty()) return new JsonObject();
        String url = "https://www.youtube.com/youtubei/v1/browse?key=" + Uri.encode(apiKey) + "&prettyPrint=false";
        JsonObject payload = new JsonObject();
        payload.add("context", context);
        payload.addProperty("continuation", token);
        try {
            String json = http.postJson(url, new Gson().toJson(payload),
                    innerTubeHeaders(clientName, clientVersion, referer));
            return Json.safeObject(json);
        } catch (Throwable e) {
            return new JsonObject();
        }
    }
}