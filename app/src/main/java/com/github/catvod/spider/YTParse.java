package com.github.catvod.spider;

import android.text.TextUtils;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.SpiderDebug;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns InnerTube renderer JSON into {@link Vod} items.
 *
 * <p>YouTube ships two generations of card renderers side by side: the classic
 * {@code *VideoRenderer}/{@code playlistRenderer} objects and the newer {@code lockupViewModel}.
 * Both are handled, because a single search response can mix them.
 */
final class YTParse {

    private static final Pattern NEVER = Pattern.compile("(?!x)x");

    /**
     * Compiles a pattern without letting a bad one abort class initialization.
     *
     * <p>Android's ICU regex engine is stricter than the desktop JDK engine, so a pattern that
     * compiles during the build can still fail on device. A {@code PatternSyntaxException} thrown
     * from a static field initializer surfaces as {@code ExceptionInInitializerError} and makes the
     * whole class unloadable, which takes the spider down. Degrading to a never-matching pattern
     * keeps the rest of the parser usable.
     */
    private static Pattern safePattern(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (Throwable e) {
            SpiderDebug.log("YouTube 正则编译失败，该规则已停用: " + regex + " " + e);
            return NEVER;
        }
    }

    private static final Pattern PLAYLIST_ID = safePattern("[A-Za-z0-9_-]{10,}");
    private static final Pattern BARE_PLAYLIST = safePattern("(?:PL|UU|OLAK5uy|FL|LL|RD)[A-Za-z0-9_-]{8,}");
    private static final Pattern DIGITS = safePattern("[\\d,]+");
    // The ampersand is escaped because ICU reads a bare `&&` inside a character class as an
    // intersection operator.
    private static final Pattern UNSAFE_TITLE = safePattern("[#$@%\\&!?*|\\\\/:<>]");

    private YTParse() {
    }

    /** High-resolution 16:9 fallback; the 4:3 endpoint bakes in black bars. */
    static String thumbnail(String videoId) {
        return "https://i.ytimg.com/vi/" + videoId + "/hq720.jpg";
    }

    /** One parsed video/playlist card. */
    static class Item {
        String vodId;
        String name;
        String pic;
        String remarks;
        boolean live;

        Vod toVod() {
            Vod vod = new Vod();
            vod.setVodId(vodId);
            vod.setVodName(name);
            vod.setVodPic(pic);
            vod.setVodRemarks(remarks);
            vod.setStyle(Vod.Style.rect(16.0f / 9.0f));
            return vod;
        }
    }

    /** One playlist entry, keeping its 1-based position for episode numbering. */
    static class Entry {
        String videoId;
        String title;
        int index;
        String duration = "";
        String pic = "";
        boolean live;
    }

    /** Playlist metadata plus the entries loaded so far. */
    static class Playlist {
        String playlistId;
        String title = "";
        String description = "";
        String owner = "";
        String pic = "";
        int count;
        List<Entry> videos = new ArrayList<>();
        boolean complete;
        boolean truncated;
        long cachedAt;
    }

    /* ------------------------------------------------------------------ */
    /* text helpers                                                       */
    /* ------------------------------------------------------------------ */

    /** Reads a {@code simpleText}/{@code runs} text node. */
    static String text(JsonElement element, String fallback) {
        if (element == null || element.isJsonNull()) return fallback;
        if (element.isJsonPrimitive()) return element.getAsString();
        if (!element.isJsonObject()) return fallback;
        JsonObject obj = element.getAsJsonObject();
        if (obj.has("simpleText") && !obj.get("simpleText").isJsonNull()) return obj.get("simpleText").getAsString();
        JsonElement runs = obj.get("runs");
        if (runs != null && runs.isJsonArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonElement run : runs.getAsJsonArray()) {
                if (!run.isJsonObject()) continue;
                JsonElement part = run.getAsJsonObject().get("text");
                if (part != null && part.isJsonPrimitive()) sb.append(part.getAsString());
            }
            if (sb.length() > 0) return sb.toString();
        }
        return fallback;
    }

    /** Minimal HTML entity unescaping; renderer text is already JSON-decoded. */
    static String unescape(String text) {
        if (text == null) return "";
        return text.replace("\\u0026", "&")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&" + "quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
    }

    /** Strips characters FongMi uses as episode/line separators. */
    static String safeTitle(String title) {
        if (TextUtils.isEmpty(title)) return "video";
        String text = UNSAFE_TITLE.matcher(title).replaceAll(" ");
        return text.length() > 60 ? text.substring(0, 60) : text;
    }

    /** @return the largest thumbnail URL inside a {@code thumbnails} container. */
    static String bestThumbnail(JsonObject root) {
        if (root == null) return "";
        JsonElement list = root.get("thumbnails");
        if (list == null || !list.isJsonArray()) return "";
        long bestArea = -1;
        String best = "";
        for (JsonElement element : list.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            String url = unescape(YouTubeLite.optString(item, "url", ""));
            if (url.isEmpty()) continue;
            long area = YouTubeLite.optLong(item, "width", 0) * YouTubeLite.optLong(item, "height", 0);
            if (area > bestArea) {
                bestArea = area;
                best = url;
            }
        }
        return best;
    }

    /**
     * Prefers the renderer's own 16:9 thumbnail.
     *
     * <p>True 16:9 images rank first, then the largest available, so a 4:3 variant with black bars
     * is never chosen over a correctly framed one.
     */
    static String rendererThumbnail(JsonObject renderer, String videoId) {
        List<JsonObject> roots = new ArrayList<>();
        JsonObject thumb = YouTubeLite.traverseObject(renderer, "thumbnail");
        if (thumb != null) roots.add(thumb);
        JsonObject moving = YouTubeLite.traverseObject(renderer, "richThumbnail",
                "movingThumbnailRenderer", "movingThumbnailDetails");
        if (moving != null) roots.add(moving);
        boolean bestRatio = false;
        long bestArea = -1;
        String best = "";
        for (JsonObject root : roots) {
            JsonElement list = root.get("thumbnails");
            if (list == null || !list.isJsonArray()) continue;
            for (JsonElement element : list.getAsJsonArray()) {
                if (!element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                String url = unescape(YouTubeLite.optString(item, "url", ""));
                if (url.isEmpty()) continue;
                long width = YouTubeLite.optLong(item, "width", 0);
                long height = YouTubeLite.optLong(item, "height", 0);
                double ratioError = width > 0 && height > 0
                        ? Math.abs((double) width / height - 16.0 / 9.0) : 9.0;
                boolean wide = ratioError <= 0.08;
                long area = width * height;
                if (wide && !bestRatio || wide == bestRatio && area > bestArea) {
                    bestRatio = wide;
                    bestArea = area;
                    best = url;
                }
            }
        }
        return best.isEmpty() ? thumbnail(videoId) : best;
    }

    /* ------------------------------------------------------------------ */
    /* ids                                                                */
    /* ------------------------------------------------------------------ */

    /** @return a playlist id only for an explicit playlist URL, {@code pl:} id, or bare list id. */
    static String playlistId(String value) {
        String text = unescape(value == null ? "" : value.trim()).trim();
        if (text.startsWith("pl:")) {
            String id = text.substring(3);
            int amp = id.indexOf('&');
            if (amp >= 0) id = id.substring(0, amp);
            id = id.trim();
            return PLAYLIST_ID.matcher(id).matches() ? id : "";
        }
        int listAt = text.indexOf("list=");
        if (listAt >= 0) {
            String id = text.substring(listAt + 5);
            int amp = id.indexOf('&');
            if (amp >= 0) id = id.substring(0, amp);
            id = id.trim();
            if (PLAYLIST_ID.matcher(id).matches()) return id;
        }
        return BARE_PLAYLIST.matcher(text).matches() ? text : "";
    }

    /* ------------------------------------------------------------------ */
    /* renderer walking                                                   */
    /* ------------------------------------------------------------------ */

    /** Depth-first search for the first occurrence of a named renderer. */
    static JsonObject findRenderer(JsonElement root, String name) {
        if (root == null) return new JsonObject();
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            JsonElement direct = obj.get(name);
            if (direct != null && direct.isJsonObject()) return direct.getAsJsonObject();
            for (String key : obj.keySet()) {
                JsonObject found = findRenderer(obj.get(key), name);
                if (found != null && found.size() > 0) return found;
            }
        } else if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) {
                JsonObject found = findRenderer(element, name);
                if (found != null && found.size() > 0) return found;
            }
        }
        return new JsonObject();
    }

    /** Detects the live badge without an extra player request. */
    static String liveLabel(JsonObject renderer) {
        JsonElement badges = renderer.get("badges");
        if (badges == null || !badges.isJsonArray()) return "";
        for (JsonElement element : badges.getAsJsonArray()) {
            if (!element.isJsonObject()) continue;
            JsonObject meta = YouTubeLite.traverseObject(element.getAsJsonObject(), "metadataBadgeRenderer");
            if (meta == null) continue;
            String iconType = YouTubeLite.traverseString(meta, "icon", "iconType");
            String style = YouTubeLite.optString(meta, "style", "");
            boolean live = "LIVE".equalsIgnoreCase(iconType)
                    || style.toUpperCase(Locale.US).contains("LIVE_NOW");
            if (!live) continue;
            String viewers = text(renderer.get("viewCountText"), "").trim();
            return viewers.isEmpty() ? "正在直播" : "正在直播 · " + viewers;
        }
        return "";
    }

    /** Best-effort live marker for a playlist card, taken from its inline metadata text. */
    private static String playlistLive(JsonElement lockup) {
        StringBuilder joined = new StringBuilder();
        collectText(lockup, joined);
        String text = joined.toString().toLowerCase(Locale.US);
        for (String marker : new String[]{"正在直播", "直播", "live now", "watching now"}) {
            if (text.contains(marker)) return "正在直播";
        }
        return "";
    }

    private static void collectText(JsonElement root, StringBuilder out) {
        if (root == null) return;
        if (root.isJsonObject()) {
            JsonObject obj = root.getAsJsonObject();
            for (String key : obj.keySet()) {
                JsonElement value = obj.get(key);
                if (("content".equals(key) || "label".equals(key)) && value.isJsonPrimitive()) {
                    out.append(value.getAsString()).append(' ');
                } else {
                    collectText(value, out);
                }
            }
        } else if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) collectText(element, out);
        }
    }

    private static Item parseVideoRenderer(JsonObject renderer) {
        String vid = YouTubeLite.optString(renderer, "videoId", null);
        if (vid == null) vid = YouTubeLite.traverseString(renderer, "navigationEndpoint", "watchEndpoint", "videoId");
        if (TextUtils.isEmpty(vid)) return null;
        JsonElement titleObj = renderer.has("title") ? renderer.get("title") : renderer.get("headline");
        String title = text(titleObj, "YouTube Video");
        String duration = text(YouTubeLite.traverseObject(renderer, "lengthText"), "YouTube");
        String live = liveLabel(renderer);
        Item item = new Item();
        item.vodId = vid;
        item.name = unescape(title);
        item.pic = rendererThumbnail(renderer, vid);
        item.remarks = live.isEmpty() ? duration : live;
        item.live = !live.isEmpty();
        return item;
    }

    private static Item parsePlaylistCard(JsonObject renderer) {
        try {
            // New search UI: playlist cards are lockupViewModel objects.
            if ("LOCKUP_CONTENT_TYPE_PLAYLIST".equals(YouTubeLite.optString(renderer, "contentType", ""))) {
                String id = YouTubeLite.optString(renderer, "contentId", "").trim();
                if (id.isEmpty()) return null;
                String title = unescape(YouTubeLite.traverseString(renderer, "metadata",
                        "lockupMetadataViewModel", "title", "content"));
                if (title.isEmpty()) title = id;
                JsonObject primary = YouTubeLite.traverseObject(renderer, "contentImage",
                        "collectionThumbnailViewModel", "primaryThumbnail", "thumbnailViewModel");
                String pic = "";
                String countText = "";
                if (primary != null) {
                    JsonObject image = YouTubeLite.traverseObject(primary, "image");
                    if (image != null) {
                        JsonObject sources = new JsonObject();
                        JsonElement list = image.get("sources");
                        if (list != null && list.isJsonArray()) sources.add("thumbnails", list);
                        pic = bestThumbnail(sources);
                    }
                    countText = badgeText(primary, "thumbnailOverlayBadgeViewModel", "thumbnailBadges");
                }
                String live = playlistLive(renderer);
                String remark = countText.isEmpty() ? "YouTube播放列表" : countText;
                Item item = new Item();
                item.vodId = "pl:" + id;
                item.name = title;
                item.pic = pic;
                item.remarks = live.isEmpty() ? remark : live + " · " + remark;
                return item;
            }
            // Older search UI.
            String id = YouTubeLite.optString(renderer, "playlistId", "").trim();
            if (id.isEmpty()) {
                id = YouTubeLite.traverseString(renderer, "navigationEndpoint", "browseEndpoint", "browseId");
                if (TextUtils.isEmpty(id)) {
                    id = YouTubeLite.traverseString(renderer, "navigationEndpoint", "watchEndpoint", "playlistId");
                }
            }
            if (TextUtils.isEmpty(id)) return null;
            String title = unescape(text(renderer.get("title"), id));
            JsonObject thumbs = renderer.has("thumbnails")
                    ? YouTubeLite.traverseObject(renderer, "thumbnails")
                    : YouTubeLite.traverseObject(renderer, "thumbnail");
            String countText = text(renderer.get("videoCountText"), YouTubeLite.optString(renderer, "videoCount", ""));
            Item item = new Item();
            item.vodId = "pl:" + id;
            item.name = title;
            item.pic = bestThumbnail(thumbs);
            item.remarks = countText.matches("\\d+") ? countText + " videos"
                    : countText.isEmpty() ? "YouTube播放列表" : countText;
            return item;
        } catch (Throwable e) {
            return null;
        }
    }

    private static String badgeText(JsonObject root, String overlayKey, String badgeListKey) {
        JsonElement overlays = root.get("overlays");
        if (overlays == null || !overlays.isJsonArray()) return "";
        for (JsonElement overlay : overlays.getAsJsonArray()) {
            if (!overlay.isJsonObject()) continue;
            JsonObject holder = YouTubeLite.traverseObject(overlay.getAsJsonObject(), overlayKey);
            if (holder == null) continue;
            JsonElement badges = holder.get(badgeListKey);
            if (badges == null || !badges.isJsonArray()) continue;
            for (JsonElement badge : badges.getAsJsonArray()) {
                if (!badge.isJsonObject()) continue;
                String value = YouTubeLite.traverseString(badge.getAsJsonObject(),
                        "thumbnailBadgeViewModel", "text");
                if (!TextUtils.isEmpty(value)) return value;
            }
        }
        return "";
    }

    /** Collects up to {@code limit} video/playlist cards from any InnerTube response. */
    static List<Item> items(JsonElement root, int limit) {
        List<Item> videos = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        scanItems(root, limit, videos, seen);
        return videos;
    }

    private static void scanItems(JsonElement root, int limit, List<Item> videos, Set<String> seen) {
        if (root == null || videos.size() >= limit) return;
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) scanItems(element, limit, videos, seen);
            return;
        }
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        JsonElement lockup = obj.get("lockupViewModel");
        if (lockup != null && lockup.isJsonObject()
                && "LOCKUP_CONTENT_TYPE_PLAYLIST".equals(
                YouTubeLite.optString(lockup.getAsJsonObject(), "contentType", ""))) {
            add(parsePlaylistCard(lockup.getAsJsonObject()), videos, seen);
            return;
        }
        for (String key : new String[]{"playlistRenderer", "gridPlaylistRenderer"}) {
            JsonElement value = obj.get(key);
            if (value != null && value.isJsonObject()) {
                add(parsePlaylistCard(value.getAsJsonObject()), videos, seen);
                return;
            }
        }
        for (String key : new String[]{"videoRenderer", "compactVideoRenderer", "gridVideoRenderer", "reelItemRenderer"}) {
            JsonElement value = obj.get(key);
            if (value != null && value.isJsonObject()) {
                add(parseVideoRenderer(value.getAsJsonObject()), videos, seen);
                return;
            }
        }
        for (String key : obj.keySet()) scanItems(obj.get(key), limit, videos, seen);
    }

    private static void add(Item item, List<Item> videos, Set<String> seen) {
        if (item == null || item.vodId == null || seen.contains(item.vodId)) return;
        seen.add(item.vodId);
        videos.add(item);
    }

    /** @return the first continuation token in a response, or an empty string. */
    static String continuation(JsonElement root) {
        List<String> tokens = new ArrayList<>();
        scanContinuation(root, tokens);
        return tokens.isEmpty() ? "" : tokens.get(0);
    }

    private static void scanContinuation(JsonElement root, List<String> tokens) {
        if (root == null || !tokens.isEmpty()) return;
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) scanContinuation(element, tokens);
            return;
        }
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        String token = YouTubeLite.traverseString(obj, "continuationEndpoint", "continuationCommand", "token");
        if (!TextUtils.isEmpty(token)) {
            tokens.add(token);
            return;
        }
        token = YouTubeLite.traverseString(obj, "continuationItemRenderer",
                "continuationEndpoint", "continuationCommand", "token");
        if (!TextUtils.isEmpty(token)) {
            tokens.add(token);
            return;
        }
        for (String key : obj.keySet()) scanContinuation(obj.get(key), tokens);
    }

    /* ------------------------------------------------------------------ */
    /* playlists                                                          */
    /* ------------------------------------------------------------------ */

    private static Entry parsePlaylistVideo(JsonObject renderer, int fallbackIndex) {
        String videoId = YouTubeLite.optString(renderer, "videoId", "").trim();
        if (videoId.isEmpty()) return null;
        String title = unescape(text(renderer.get("title"), "YouTube Video")).trim();
        if (title.isEmpty() || "private video".equalsIgnoreCase(title) || "deleted video".equalsIgnoreCase(title)) {
            return null;
        }
        Entry entry = new Entry();
        entry.videoId = videoId;
        entry.title = title;
        entry.index = fallbackIndex;
        String indexText = text(renderer.get("index"), "").replaceAll("\\D+", "");
        if (!indexText.isEmpty()) {
            try {
                entry.index = Integer.parseInt(indexText);
            } catch (Throwable ignored) {
                entry.index = fallbackIndex;
            }
        }
        entry.duration = text(renderer.get("lengthText"), "");
        entry.live = !liveLabel(renderer).isEmpty();
        JsonObject thumb = YouTubeLite.traverseObject(renderer, "thumbnail");
        String pic = bestThumbnail(thumb);
        entry.pic = pic.isEmpty() ? thumbnail(videoId) : pic;
        return entry;
    }

    private static Entry parsePlaylistLockup(JsonObject lockup, int fallbackIndex) {
        if (!"LOCKUP_CONTENT_TYPE_VIDEO".equals(YouTubeLite.optString(lockup, "contentType", ""))) return null;
        String videoId = YouTubeLite.optString(lockup, "contentId", "").trim();
        if (videoId.isEmpty()) return null;
        String title = unescape(YouTubeLite.traverseString(lockup, "metadata",
                "lockupMetadataViewModel", "title", "content")).trim();
        if (title.isEmpty()) title = "YouTube Video";
        if ("private video".equalsIgnoreCase(title) || "deleted video".equalsIgnoreCase(title)) return null;
        Entry entry = new Entry();
        entry.videoId = videoId;
        entry.title = title;
        entry.index = fallbackIndex;
        JsonObject endpoint = YouTubeLite.traverseObject(lockup, "rendererContext", "commandContext",
                "onTap", "innertubeCommand", "watchEndpoint");
        if (endpoint != null && endpoint.has("index")) {
            try {
                entry.index = endpoint.get("index").getAsInt() + 1;
            } catch (Throwable ignored) {
                entry.index = fallbackIndex;
            }
        }
        JsonObject thumbView = YouTubeLite.traverseObject(lockup, "contentImage", "thumbnailViewModel");
        if (thumbView != null) {
            JsonObject image = YouTubeLite.traverseObject(thumbView, "image");
            if (image != null) {
                JsonObject sources = new JsonObject();
                JsonElement list = image.get("sources");
                if (list != null && list.isJsonArray()) sources.add("thumbnails", list);
                entry.pic = bestThumbnail(sources);
            }
            entry.duration = badgeText(thumbView, "thumbnailBottomOverlayViewModel", "badges");
        }
        if (entry.pic.isEmpty()) entry.pic = thumbnail(videoId);
        return entry;
    }

    /** Collects playlist entries, numbering them from {@code startIndex} when the renderer omits it. */
    static List<Entry> playlistVideos(JsonElement root, int startIndex) {
        List<Entry> videos = new ArrayList<>();
        scanPlaylist(root, startIndex, videos);
        return videos;
    }

    private static void scanPlaylist(JsonElement root, int startIndex, List<Entry> videos) {
        if (root == null) return;
        if (root.isJsonArray()) {
            for (JsonElement element : root.getAsJsonArray()) scanPlaylist(element, startIndex, videos);
            return;
        }
        if (!root.isJsonObject()) return;
        JsonObject obj = root.getAsJsonObject();
        JsonElement renderer = obj.get("playlistVideoRenderer");
        if (renderer != null && renderer.isJsonObject()) {
            Entry entry = parsePlaylistVideo(renderer.getAsJsonObject(), startIndex + videos.size());
            if (entry != null) videos.add(entry);
            return;
        }
        JsonElement lockup = obj.get("lockupViewModel");
        if (lockup != null && lockup.isJsonObject()) {
            Entry entry = parsePlaylistLockup(lockup.getAsJsonObject(), startIndex + videos.size());
            if (entry != null) videos.add(entry);
            return;
        }
        for (String key : obj.keySet()) scanPlaylist(obj.get(key), startIndex, videos);
    }

    static Playlist playlistMeta(JsonElement data, String playlistId) {
        JsonObject metadata = findRenderer(data, "playlistMetadataRenderer");
        JsonObject header = findRenderer(data, "playlistHeaderRenderer");
        JsonObject sidebar = findRenderer(data, "playlistSidebarPrimaryInfoRenderer");
        String title = YouTubeLite.optString(metadata, "title", "");
        if (title.isEmpty()) title = text(header.get("title"), "");
        if (title.isEmpty()) title = text(sidebar.get("title"), "");
        if (title.isEmpty()) title = playlistId;
        String description = YouTubeLite.optString(metadata, "description", "");
        if (description.isEmpty()) description = text(header.get("descriptionText"), "");
        String owner = text(header.get("ownerText"), "");
        if (owner.isEmpty()) owner = text(header.get("ownerEndpoint"), "");
        Playlist playlist = new Playlist();
        playlist.playlistId = playlistId;
        playlist.title = unescape(title);
        playlist.description = unescape(description);
        playlist.owner = unescape(owner);
        playlist.pic = bestThumbnail(YouTubeLite.traverseObject(header, "playlistHeaderBanner",
                "heroPlaylistThumbnailRenderer", "thumbnail"));
        playlist.videos = playlistVideos(data, 1);
        if (playlist.pic.isEmpty() && !playlist.videos.isEmpty()) playlist.pic = playlist.videos.get(0).pic;
        String countText = text(header.get("numVideosText"), "");
        if (countText.isEmpty()) countText = text(sidebar.get("stats"), "");
        Matcher matcher = DIGITS.matcher(countText);
        if (matcher.find()) {
            try {
                playlist.count = Integer.parseInt(matcher.group().replace(",", ""));
            } catch (Throwable ignored) {
                playlist.count = 0;
            }
        }
        return playlist;
    }

}