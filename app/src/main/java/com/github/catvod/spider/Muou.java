package com.github.catvod.spider;

import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.net.OkHttp;
import com.github.catvod.utils.Json;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 木偶，移植自 CatVodOpen 的 mogg.js。
 *
 * <p>站点本身只提供影片信息和网盘分享链，播放交给 {@link ApiPan} 驱动：详情页把页面里的分享链
 * 交给能识别它的驱动展开成选集，{@link #playerContent} 再回调同一个驱动换直链。
 *
 * <p>域名必须由 ext 提供，原实现同样是从配置读取而非硬编码 —— 这类站点换域名很频繁。ext 支持两
 * 种写法：直接给一个 URL 字符串，或给 {@code {"sites": ["https://a", "https://b"]}} 让它按顺序
 * 探测可用的那个。
 */
public class Muou extends Spider {

    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    /** 分享链和驱动条目的分隔符，两侧都不会出现在 URL 或 fid 里。 */
    private static final String SEP = "@@@";

    private final List<String> hosts = new ArrayList<>();
    private volatile String host = "";

    @Override
    public void init(Context context, String extend) {
        hosts.clear();
        host = "";
        if (TextUtils.isEmpty(extend)) return;
        String text = extend.trim();
        if (text.startsWith("http")) {
            // ext 直接就是一个域名
            hosts.add(text);
            return;
        }
        JsonObject ext = Json.safeObject(text);
        JsonElement sites = ext.get("sites");
        if (sites == null) return;
        if (sites.isJsonPrimitive()) {
            hosts.add(sites.getAsString());
        } else if (sites.isJsonArray()) {
            for (JsonElement item : sites.getAsJsonArray()) {
                if (item != null && item.isJsonPrimitive()) hosts.add(item.getAsString());
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* 域名探测                                                            */
    /* ------------------------------------------------------------------ */

    /**
     * 取一个可用域名。
     *
     * <p>逐个试，拿到含站点模板特征的页面就认定可用并缓存。串行而非并发：宿主已在后台线程，候选
     * 数量很少，并发的复杂度换不来明显收益。
     */
    private String host() throws Exception {
        if (!TextUtils.isEmpty(host)) return host;
        synchronized (this) {
            if (!TextUtils.isEmpty(host)) return host;
            if (hosts.isEmpty()) {
                throw new Exception("请在站点 ext 里填写木偶域名，例如 {\"sites\":[\"https://xxx.com\"]}");
            }
            List<String> tried = new ArrayList<>();
            for (String candidate : hosts) {
                String url = candidate.trim();
                while (url.endsWith("/")) url = url.substring(0, url.length() - 1);
                if (url.isEmpty()) continue;
                tried.add(url);
                try {
                    String html = OkHttp.string(url, null, header(), 8000);
                    // module-item 是该模板的列表容器，用它确认拿到的是站点本体而不是跳转页
                    if (!TextUtils.isEmpty(html) && html.contains("module-item")) {
                        host = url;
                        return host;
                    }
                    SpiderDebug.log("木偶域名响应不含站点特征 " + url);
                } catch (Throwable e) {
                    SpiderDebug.log("木偶域名不可用 " + url + " " + e);
                }
            }
            throw new Exception("木偶域名均不可用: " + TextUtils.join(", ", tried));
        }
    }

    private Map<String, String> header() {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", UA);
        return header;
    }

    private Document doc(String url) throws Exception {
        String html = OkHttp.string(url, header());
        if (TextUtils.isEmpty(html)) throw new Exception("木偶页面为空: " + url);
        return Jsoup.parse(html);
    }

    /* ------------------------------------------------------------------ */
    /* 列表                                                                */
    /* ------------------------------------------------------------------ */

    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        classes.add(new Class("1", "电影"));
        classes.add(new Class("2", "剧集"));
        classes.add(new Class("3", "动漫"));
        classes.add(new Class("25", "天翼专区"));
        classes.add(new Class("27", "短剧"));
        classes.add(new Class("4", "纪录片"));
        return Result.string(classes, new LinkedHashMap<>());
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend)
            throws Exception {
        int page = page(pg);
        Document doc = doc(host() + "/index.php/vod/show/id/" + tid + "/page/" + page + ".html");
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select("#main .module-item")) {
            Element link = item.selectFirst(".module-item-pic a");
            Element image = item.selectFirst(".module-item-pic img");
            if (link == null || image == null) continue;
            Vod vod = new Vod();
            vod.setVodId(link.attr("href"));
            vod.setVodName(image.attr("alt"));
            vod.setVodPic(image.attr("data-src"));
            Element text = item.selectFirst(".module-item-text");
            vod.setVodRemarks(text == null ? "" : text.text().trim());
            list.add(vod);
        }
        // 有「下一页」就还有下一页，没有就到底了
        boolean more = !doc.select("#page a:contains(下一页)").isEmpty();
        int count = more ? page + 1 : page;
        return Result.get().vod(list).page(page, count, 72, 72 * count).string();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        // 站点的搜索路径不带页码，多页请求会拿到同一批结果，所以只认第一页
        if (page(pg) > 1) return Result.get().vod(new ArrayList<>()).string();
        Document doc = doc(host() + "/index.php/vod/search/wd/" + Uri.encode(key) + ".html");
        List<Vod> list = new ArrayList<>();
        for (Element item : doc.select(".module-search-item")) {
            Element serial = item.selectFirst(".video-serial");
            if (serial == null) continue;
            Vod vod = new Vod();
            vod.setVodId(serial.attr("href"));
            vod.setVodName(serial.attr("title"));
            Element image = item.selectFirst(".module-item-pic > img");
            vod.setVodPic(image == null ? "" : image.attr("data-src"));
            vod.setVodRemarks(serial.text().trim());
            list.add(vod);
        }
        return Result.string(list);
    }

    /* ------------------------------------------------------------------ */
    /* 详情                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 详情页。
     *
     * <p>页面里的每条分享链交给能识别它的驱动展开成选集，一条链一条线路。展开失败的链跳过，但会
     * 记日志 —— 未实现的网盘由 {@link ApiStub} 顶上，会显示成一条「暂未支持」的线路。
     */
    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        Document doc = doc(host() + id);
        Vod vod = new Vod();
        vod.setVodId(id);

        Element title = doc.selectFirst(".page-title");
        vod.setVodName(title == null ? "" : title.ownText().trim());
        Element pic = doc.selectFirst(".mobile-play .lazyload");
        if (pic != null) vod.setVodPic(pic.attr("data-src"));

        // 信息区是「标题 + 兄弟节点内容」的结构，按标题文字分派
        for (Element item : doc.select(".video-info-itemtitle")) {
            Element value = item.nextElementSibling();
            if (value == null) continue;
            String label = item.text();
            if (label.contains("剧情")) {
                Element p = value.selectFirst("p");
                vod.setVodContent(p == null ? value.text().trim() : p.text().trim());
            } else if (label.contains("导演")) {
                vod.setVodDirector(join(value.select("a")));
            } else if (label.contains("主演")) {
                vod.setVodActor(join(value.select("a")));
            }
        }

        List<String> froms = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (Element p : doc.select("div.module-row-info p")) {
            String share = p.ownText().trim();
            if (TextUtils.isEmpty(share)) share = p.text().trim();
            if (!share.startsWith("http")) continue;
            ApiPan pan = Config.match(share);
            if (pan == null) continue;
            try {
                List<ApiPan.Item> files = pan.parse(share);
                if (files.isEmpty()) continue;
                List<String> episodes = new ArrayList<>();
                for (ApiPan.Item file : files) {
                    // 播放时要同时知道用哪个驱动和哪个文件，所以把分享链一起带上
                    episodes.add(clean(file.name) + "$" + share + SEP + file.id);
                }
                froms.add(pan.name());
                urls.add(TextUtils.join("#", episodes));
            } catch (Throwable e) {
                SpiderDebug.log("木偶分享链展开失败 " + share + " " + e);
            }
        }
        vod.setVodPlayFrom(TextUtils.join("$$$", froms));
        vod.setVodPlayUrl(TextUtils.join("$$$", urls));
        return Result.string(vod);
    }

    /** 集名里的分隔符会破坏 vod_play_url 的结构，先清掉。 */
    private static String clean(String name) {
        if (TextUtils.isEmpty(name)) return "播放";
        return name.replace("$", " ").replace("#", " ").trim();
    }

    private static String join(Elements elements) {
        List<String> parts = new ArrayList<>();
        for (Element element : elements) {
            String text = element.text().trim();
            if (!text.isEmpty()) parts.add(text);
        }
        return TextUtils.join(", ", parts);
    }

    /* ------------------------------------------------------------------ */
    /* 播放                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 播放。
     *
     * <p>id 形如 {@code <分享链>@@@<驱动内部定位串>}，据此找回驱动并让它换直链。直链有时效，所以
     * 一定是在这一步才取。
     */
    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        int mark = id.indexOf(SEP);
        if (mark <= 0) throw new Exception("木偶播放参数不完整");
        String share = id.substring(0, mark);
        String fileId = id.substring(mark + SEP.length());
        ApiPan pan = Config.match(share);
        if (pan == null) throw new Exception("没有可处理该分享的网盘驱动");
        return pan.play(flag, fileId);
    }

    private static int page(String pg) {
        try {
            return Math.max(1, Integer.parseInt(pg));
        } catch (Throwable e) {
            return 1;
        }
    }
}