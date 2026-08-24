package com.github.catvod.spider;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Filter;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
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
    private JsonObject externalCatalog;

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
        this.externalCatalog = readCatalog();
        this.yt = new YouTubeLite(context, http, header, ext);
        this.play = new YTPlay(yt, header, ext, siteKey);
        this.session = new YoutubeSession(context, ext);
        this.youtubeProxy = new YoutubeProxy(play);
    }

    /** Loads a standard CatVod class/filter JSON from ext.json. */
    private JsonObject readCatalog() {
        try {
            JsonElement value = ext.get("json");
            if (value == null || value.isJsonNull()) return null;
            String source = value.isJsonPrimitive() ? value.getAsString() : value.toString();
            String json = source.trim();
            if (json.startsWith("http://") || json.startsWith("https://")) json = http.string(json);
            JsonObject root = Json.safeObject(json);
            return YTExternalCatalog.valid(root) ? root : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** Reads the {@code proxy} extend value, accepting either a bare host:port or an object. */
    private String readProxy() {
        JsonElement value = ext.get("proxy");
        if (value == null || value.isJsonNull()) return null;
        String text = "";
        if (value.isJsonPrimitive()) {
            text = value.getAsString();
        } else if (value.isJsonObject()) {
            JsonObject obj = value.getAsJsonObject();
            text = YouTubeLite.optString(obj, "http", YouTubeLite.optString(obj, "https", ""));
        }
        text = text.replace("http://", "").replace("https://", "").trim();
        return text.isEmpty() ? null : text;
    }

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = externalCatalog == null ? YTCatalog.classes() : YTExternalCatalog.classes(externalCatalog);
        if (!filter) return Result.string(classes, new ArrayList<>());
        LinkedHashMap<String, List<Filter>> filters = externalCatalog == null
                ? YTCatalog.filters() : YTExternalCatalog.filters(externalCatalog);
        return Result.string(classes, filters);
    }

    @Override
    public String homeVideoContent() {
        return Result.string(new ArrayList<Vod>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        int page = parsePage(pg);
        String query = externalCatalog == null
                ? YTCatalog.keyword(tid, extend)
                : YTExternalCatalog.keyword(externalCatalog, tid, extend);
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
        vod.setVodPic(YTParse.thumbnail(videoId));
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
        return youtubeProxy == null ? null : youtubeProxy.handle(params);
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
        for (YTParse.Item item : items) list.add(item.toVod());
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
        vod.setVodPic(playlist.pic);
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
        vod.setVodPic(playlist.pic.isEmpty() ? videos.get(0).pic : playlist.pic);
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