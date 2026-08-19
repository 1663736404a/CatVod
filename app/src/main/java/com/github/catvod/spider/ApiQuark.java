package com.github.catvod.spider;

import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Sub;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Json;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 夸克网盘驱动，移植自 CatVodOpen 的 quark.js。
 *
 * <p>夸克的分享文件不能直接出直链，必须先转存到自己网盘再取。流程：
 * <pre>
 *   分享链 → share token → 遍历文件树 → 转存到 CatVodOpen 目录 → download/play 换直链
 * </pre>
 *
 * <p>保留原实现的几处关键行为：
 * <ul>
 *   <li>{@code __puus} 会被服务端滚动刷新，每次响应都要检查 Set-Cookie 并回写，否则凭据几小时失效
 *   <li>429 退避重试 3 次，间隔 1 秒
 *   <li>小于 5MB 的视频文件跳过，多为预览片段
 *   <li>转存目录固定 {@code CatVodOpen}，播放前清空，避免网盘被写满
 * </ul>
 */
final class ApiQuark implements ApiPan {

    private static final ApiQuark INSTANCE = new ApiQuark();

    static ApiQuark get() {
        return INSTANCE;
    }

    private ApiQuark() {
    }

    private static final String PAN = "quark";
    private static final String API = "https://drive.quark.cn/1/clouddrive/";
    private static final String PR = "pr=ucpro&fr=pc";
    private static final String SAVE_DIR = "CatVodOpen";

    private static final String UA_PC = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) quark-cloud-drive/2.5.20 "
            + "Chrome/100.0.4896.160 Electron/18.3.5.4-b478491100 Safari/537.36 Channel/pckk_other_ch";
    private static final String UA_WEB = "Mozilla/5.0 (Windows NT 10.0; WOW64) "
            + "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/72.0.3626.81 Safari/537.36 SE 2.X MetaSr 1.0";

    private static final Pattern NEVER = Pattern.compile("(?!x)x");

    /** 编译正则，失败时降级成永不匹配，避免静态初始化异常导致整个类不可加载。 */
    private static Pattern safePattern(String regex) {
        try {
            return Pattern.compile(regex);
        } catch (Throwable e) {
            SpiderDebug.log("夸克正则编译失败，该规则已停用: " + regex + " " + e);
            return NEVER;
        }
    }

    private static final Pattern RE_SHARE = safePattern("https://pan\\.quark\\.cn/s/([^\\\\|#/?]+)");
    private static final Pattern RE_PUUS = safePattern("__puus=([^;]+)");

    private static final String[] SUB_EXTS = {".srt", ".ass", ".scc", ".stl", ".ttml"};

    /** 转码档位由高到低，与展示名一一对应。 */
    private static final String[] RESOLUTIONS = {"4k", "2k", "super", "high", "low", "normal"};
    private static final String[] RESOLUTION_NAMES = {"超清", "蓝光", "高清", "标清", "普画", "极速"};

    private String cookie = "";
    private String saveDirId = "";
    private final Map<String, String> shareTokens = new ConcurrentHashMap<>();
    private final Map<String, String> savedFids = new ConcurrentHashMap<>();

    /** 扫码登录的中间态。 */
    private String loginToken = "";
    private String loginCookie = "";

    /* ------------------------------------------------------------------ */
    /* ApiPan                                                             */
    /* ------------------------------------------------------------------ */

    @Override
    public String key() {
        return PAN;
    }

    @Override
    public String name() {
        return "夸克网盘";
    }

    @Override
    public boolean match(String shareUrl) {
        return shareUrl != null && shareUrl.contains("pan.quark.cn/s/");
    }

    @Override
    public boolean logged() {
        return !TextUtils.isEmpty(cookie());
    }

    @Override
    public String status() {
        if (!logged()) return "未登录";
        try {
            JsonObject info = api("member?" + PR + "&fetch_subscribe=true&_ch=home&fetch_identity=true",
                    null, false);
            JsonObject data = obj(info, "data");
            if (data == null) return "已登录";
            String nick = str(data, "nickname", "");
            boolean vip = "SVIP".equalsIgnoreCase(str(data, "member_type", ""))
                    || !"NORMAL".equalsIgnoreCase(str(data, "member_type", "NORMAL"));
            String tag = vip ? "会员" : "普通";
            return TextUtils.isEmpty(nick) ? tag : nick + " · " + tag;
        } catch (Throwable e) {
            return "已登录（状态获取失败）";
        }
    }

    @Override
    public void setCookie(String value) {
        ApiStore.put(PAN, value);
        this.cookie = ApiStore.get(PAN);
        reset();
    }

    @Override
    public String getCookie() {
        return cookie();
    }

    @Override
    public void logout() {
        ApiStore.clear(PAN);
        this.cookie = "";
        reset();
    }

    /** 只清运行期刷新的凭据，保留用户填入的基准值。 */
    void clearLocal() {
        ApiStore.clearLive(PAN);
        this.cookie = ApiStore.get(PAN);
        reset();
    }

    private void reset() {
        saveDirId = "";
        shareTokens.clear();
        savedFids.clear();
    }

    private String cookie() {
        if (TextUtils.isEmpty(cookie)) cookie = ApiStore.get(PAN);
        return cookie;
    }

    /* ------------------------------------------------------------------ */
    /* 扫码登录                                                            */
    /* ------------------------------------------------------------------ */

    @Override
    public String qrcode() throws Exception {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", UA_WEB);
        ApiHttp.Res result = ApiHttp.get(
                "https://uop.quark.cn/cas/ajax/getTokenForQrcodeLogin?client_id=532&v=1.2", header);
        JsonObject root = Json.safeObject(result.body);
        String token = deepString(root, "data", "members", "token");
        if (TextUtils.isEmpty(token)) throw new Exception("夸克二维码获取失败");
        loginToken = token;
        loginCookie = ApiHttp.cookies(result.headers);
        String target = "https://su.quark.cn/4_eMHBJ?token=" + token
                + "&client_id=532&ssb=weblogin&uc_param_str="
                + "&uc_biz_str=S%3Acustom%7COPT%3ASAREA%400%7COPT%3AIMMERSIVE%401%7COPT%3ABACK_BTN_STYLE%400";
        // jar 依赖白名单里没有二维码库，本地不生成位图，交给在线接口渲染成图片
        return "https://api.qrserver.com/v1/create-qr-code/?size=400x400&data=" + Uri.encode(target);
    }

    @Override
    public boolean checkQrcode() throws Exception {
        if (TextUtils.isEmpty(loginToken)) throw new Exception("请先获取二维码");
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", UA_WEB);
        ApiHttp.Res poll = ApiHttp.get(
                "https://uop.quark.cn/cas/ajax/getServiceTicketByQrcodeToken?client_id=532&v=1.2&token="
                        + loginToken, header);
        JsonObject root = Json.safeObject(poll.body);
        // 2000000 表示已扫码并确认，其他状态一律当作还在等待
        if (num(root, "status", 0) != 2000000L) return false;
        String ticket = deepString(root, "data", "members", "service_ticket");
        if (TextUtils.isEmpty(ticket)) return false;

        // service_ticket 换 __pus
        Map<String, String> h2 = new HashMap<>();
        h2.put("User-Agent", UA_WEB);
        h2.put("Cookie", loginCookie);
        ApiHttp.Res step2 = ApiHttp.get("https://pan.quark.cn/account/info?st=" + Uri.encode(ticket)
                + "&fr=pc&platform=pc", h2);
        String pus = ApiHttp.cookies(step2.headers);
        if (TextUtils.isEmpty(pus)) throw new Exception("夸克 __pus 获取失败");
        String merged = mergeCookie(loginCookie, pus);

        // 再打一次网盘接口拿 __puus，这一步拿不到的话后续所有请求都会 401
        Map<String, String> h3 = new HashMap<>();
        h3.put("User-Agent", UA_WEB);
        h3.put("Cookie", merged);
        ApiHttp.Res step3 = ApiHttp.get(
                "https://drive-pc.quark.cn/1/clouddrive/share/sharepage/dir?pr=ucpro&fr=pc&uc_param_str=&aver=1",
                h3);
        String puus = ApiHttp.cookies(step3.headers);
        if (TextUtils.isEmpty(puus)) throw new Exception("夸克 __puus 获取失败");

        setCookie(mergeCookie(merged, puus));
        loginToken = "";
        loginCookie = "";
        return true;
    }

    /** 后者覆盖前者的同名项。 */
    private static String mergeCookie(String base, String extra) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String part : (base + ";" + extra).split(";")) {
            String item = part.trim();
            if (item.isEmpty()) continue;
            int eq = item.indexOf('=');
            if (eq <= 0) continue;
            map.put(item.substring(0, eq).trim(), item.substring(eq + 1).trim());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : map.entrySet()) {
            sb.append(e.getKey()).append('=').append(e.getValue()).append(';');
        }
        return sb.toString();
    }

    /* ------------------------------------------------------------------ */
    /* 请求                                                                */
    /* ------------------------------------------------------------------ */

    private JsonObject api(String path, String json, boolean post) throws Exception {
        return api(path, json, post, 3);
    }

    /**
     * 调一次网盘接口。
     *
     * <p>每次响应都检查 {@code __puus} 是否被刷新，变了就立即回写本地，这是凭据能长期有效的关键。
     */
    private JsonObject api(String path, String json, boolean post, int retry) throws Exception {
        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", UA_PC);
        header.put("Referer", "https://pan.quark.cn");
        header.put("Content-Type", "application/json");
        header.put("Cookie", cookie());
        String url = API + path;
        // 用 ApiHttp 而不是宿主的 OkHttp，因为需要响应头来跟踪 __puus
        ApiHttp.Res result = post
                ? ApiHttp.post(url, json == null ? "{}" : json, header)
                : ApiHttp.get(url, header);
        refreshPuus(result.headers);
        if (result.code == 429 && retry > 0) {
            Thread.sleep(1000);
            return api(path, json, post, retry - 1);
        }
        if (result.code == 401) throw new Exception("夸克凭据已过期，请在设置中心重新登录");
        return Json.safeObject(result.body);
    }

    /**
     * 跟踪 {@code __puus} 的滚动刷新。
     *
     * <p>服务端会在响应里下发新的 {@code __puus}，不回写本地的话凭据几小时后失效。
     */
    private void refreshPuus(Map<String, List<String>> headers) {
        String fresh = ApiHttp.cookies(headers);
        if (TextUtils.isEmpty(fresh)) return;
        Matcher incoming = RE_PUUS.matcher(fresh);
        if (!incoming.find()) return;
        String value = incoming.group(1);
        String current = cookie();
        Matcher existing = RE_PUUS.matcher(current);
        String updated;
        if (existing.find()) {
            if (existing.group(1).equals(value)) return;
            // Matcher 已被 find 消耗，替换要用新的
            updated = RE_PUUS.matcher(current).replaceAll("__puus=" + value);
        } else {
            updated = mergeCookie(current, "__puus=" + value);
        }
        this.cookie = updated;
        ApiStore.putLive(PAN, updated);
    }

    /* ------------------------------------------------------------------ */
    /* 分享解析                                                            */
    /* ------------------------------------------------------------------ */

    /** @return {@code {shareId, folderId}}，不是夸克链接时返回 null */
    static String[] shareData(String url) {
        if (url == null) return null;
        Matcher m = RE_SHARE.matcher(url);
        if (!m.find()) return null;
        return new String[]{m.group(1), "0"};
    }

    private String shareToken(String shareId) throws Exception {
        String cached = shareTokens.get(shareId);
        if (!TextUtils.isEmpty(cached)) return cached;
        JsonObject body = new JsonObject();
        body.addProperty("pwd_id", shareId);
        body.addProperty("passcode", "");
        JsonObject resp = api("share/sharepage/token?" + PR, body.toString(), true);
        String token = deepString(resp, "data", "stoken");
        if (TextUtils.isEmpty(token)) throw new Exception("夸克分享已失效或需要密码");
        shareTokens.put(shareId, token);
        return token;
    }

    @Override
    public List<Vod> parse(String shareUrl) throws Exception {
        String[] share = shareData(shareUrl);
        if (share == null) return new ArrayList<>();
        if (!logged()) throw new Exception("请先在设置中心登录夸克网盘");
        String stoken = shareToken(share[0]);
        List<JsonObject> videos = new ArrayList<>();
        List<JsonObject> subs = new ArrayList<>();
        listFiles(share[0], stoken, share[1], 1, videos, subs, 0);
        matchSubtitles(videos, subs);

        List<Vod> list = new ArrayList<>();
        for (JsonObject item : videos) {
            JsonObject sub = item.has("_sub") ? item.getAsJsonObject("_sub") : null;
            // 定位串：shareId*stoken*fid*fidToken*subFid*subFidToken
            String id = TextUtils.join("*", new String[]{
                    share[0],
                    stoken,
                    str(item, "fid", ""),
                    str(item, "share_fid_token", ""),
                    sub == null ? "" : str(sub, "fid", ""),
                    sub == null ? "" : str(sub, "share_fid_token", ""),
            });
            Vod vod = new Vod();
            vod.setVodId(id);
            vod.setVodName(str(item, "file_name", ""));
            list.add(vod);
        }
        return list;
    }

    /** 递归遍历分享目录树。depth 限制防止构造出来的深层目录把线程卡死。 */
    private void listFiles(String shareId, String stoken, String folderId, int page,
                           List<JsonObject> videos, List<JsonObject> subs, int depth) throws Exception {
        if (depth > 8) return;
        int size = 100;
        String path = "share/sharepage/detail?" + PR + "&pwd_id=" + shareId
                + "&stoken=" + Uri.encode(stoken) + "&pdir_fid=" + folderId
                + "&force=0&_page=" + page + "&_size=" + size
                + "&_sort=file_type:asc,file_name:asc";
        JsonObject resp = api(path, null, false);
        JsonObject data = obj(resp, "data");
        if (data == null) return;
        JsonArray items = arr(data, "list");
        if (items == null) return;

        List<String> subDirs = new ArrayList<>();
        for (JsonElement element : items) {
            if (element == null || !element.isJsonObject()) continue;
            JsonObject item = element.getAsJsonObject();
            if (bool(item, "dir")) {
                subDirs.add(str(item, "fid", ""));
            } else if (bool(item, "file") && "video".equals(str(item, "obj_category", ""))) {
                // 小于 5MB 的多是预览片段，原实现同样跳过
                if (num(item, "size", 0) < 5L * 1024 * 1024) continue;
                videos.add(item);
            } else if (isSubtitle(str(item, "file_name", ""))) {
                subs.add(item);
            }
        }

        long total = num(obj(resp, "metadata"), "_total", 0);
        if (page * size < total) {
            listFiles(shareId, stoken, folderId, page + 1, videos, subs, depth);
        }
        for (String dir : subDirs) {
            if (TextUtils.isEmpty(dir)) continue;
            listFiles(shareId, stoken, dir, 1, videos, subs, depth + 1);
        }
    }

    private static boolean isSubtitle(String name) {
        if (TextUtils.isEmpty(name)) return false;
        String lower = name.toLowerCase(Locale.ROOT);
        for (String ext : SUB_EXTS) if (lower.endsWith(ext)) return true;
        return false;
    }

    /** 用最长公共子串给每个视频挑一个同名字幕。 */
    private static void matchSubtitles(List<JsonObject> videos, List<JsonObject> subs) {
        if (subs.isEmpty()) return;
        for (JsonObject video : videos) {
            String name = str(video, "file_name", "");
            JsonObject best = null;
            int bestScore = 0;
            for (JsonObject sub : subs) {
                int score = lcs(name, str(sub, "file_name", ""));
                if (score > bestScore) {
                    bestScore = score;
                    best = sub;
                }
            }
            if (best != null && bestScore > 0) video.add("_sub", best);
        }
    }

    private static int lcs(String a, String b) {
        if (TextUtils.isEmpty(a) || TextUtils.isEmpty(b)) return 0;
        int[] prev = new int[b.length() + 1];
        int best = 0;
        for (int i = 1; i <= a.length(); i++) {
            int[] cur = new int[b.length() + 1];
            for (int j = 1; j <= b.length(); j++) {
                if (a.charAt(i - 1) == b.charAt(j - 1)) {
                    cur[j] = prev[j - 1] + 1;
                    if (cur[j] > best) best = cur[j];
                }
            }
            prev = cur;
        }
        return best;
    }

    /* ------------------------------------------------------------------ */
    /* 转存与直链                                                          */
    /* ------------------------------------------------------------------ */

    /** 找到或建出 CatVodOpen 目录。 */
    private void ensureSaveDir(boolean clean) throws Exception {
        if (!TextUtils.isEmpty(saveDirId)) {
            if (clean) clearSaveDir();
            return;
        }
        JsonObject resp = api("file/sort?" + PR
                + "&pdir_fid=0&_page=1&_size=200&_sort=file_type:asc,updated_at:desc", null, false);
        JsonArray list = arr(obj(resp, "data"), "list");
        if (list != null) {
            for (JsonElement element : list) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if (SAVE_DIR.equals(str(item, "file_name", ""))) {
                    saveDirId = str(item, "fid", "");
                    clearSaveDir();
                    break;
                }
            }
        }
        if (TextUtils.isEmpty(saveDirId)) {
            JsonObject body = new JsonObject();
            body.addProperty("pdir_fid", "0");
            body.addProperty("file_name", SAVE_DIR);
            body.addProperty("dir_path", "");
            body.addProperty("dir_init_lock", false);
            JsonObject create = api("file?" + PR, body.toString(), true);
            saveDirId = deepString(create, "data", "fid");
        }
        if (TextUtils.isEmpty(saveDirId)) throw new Exception("夸克转存目录创建失败");
    }

    /**
     * 清空转存目录。
     *
     * <p>删除范围严格限制在 {@code CatVodOpen} 目录的直接子项：只用 pdir_fid 查出来的 fid，
     * 不做任何名字匹配，也不递归到其他目录，避免误删网盘里的其他文件。
     */
    private void clearSaveDir() throws Exception {
        if (TextUtils.isEmpty(saveDirId)) return;
        JsonObject resp = api("file/sort?" + PR + "&pdir_fid=" + saveDirId
                + "&_page=1&_size=200&_sort=file_type:asc,updated_at:desc", null, false);
        JsonArray list = arr(obj(resp, "data"), "list");
        if (list == null || list.size() == 0) return;
        JsonArray fids = new JsonArray();
        for (JsonElement element : list) {
            if (element == null || !element.isJsonObject()) continue;
            String fid = str(element.getAsJsonObject(), "fid", "");
            if (!TextUtils.isEmpty(fid)) fids.add(fid);
        }
        if (fids.size() == 0) return;
        JsonObject body = new JsonObject();
        body.addProperty("action_type", 2);
        body.add("filelist", fids);
        body.add("exclude_fids", new JsonArray());
        api("file/delete?" + PR, body.toString(), true);
    }

    /** 转存一个分享文件到自己网盘，返回新 fid。 */
    private String save(String shareId, String stoken, String fid, String fidToken, boolean clean)
            throws Exception {
        ensureSaveDir(clean);
        if (clean) savedFids.clear();
        JsonArray fidList = new JsonArray();
        fidList.add(fid);
        JsonArray tokenList = new JsonArray();
        tokenList.add(fidToken);
        JsonObject body = new JsonObject();
        body.add("fid_list", fidList);
        body.add("fid_token_list", tokenList);
        body.addProperty("to_pdir_fid", saveDirId);
        body.addProperty("pwd_id", shareId);
        body.addProperty("stoken", TextUtils.isEmpty(stoken) ? shareToken(shareId) : stoken);
        body.addProperty("pdir_fid", "0");
        body.addProperty("scene", "link");
        JsonObject resp = api("share/sharepage/save?" + PR, body.toString(), true);
        String taskId = deepString(resp, "data", "task_id");
        if (TextUtils.isEmpty(taskId)) throw new Exception("夸克转存失败");
        // 转存是异步任务，轮询到 save_as_top_fids 出现为止
        for (int retry = 0; retry <= 5; retry++) {
            JsonObject task = api("task?" + PR + "&task_id=" + taskId + "&retry_index=" + retry,
                    null, false);
            JsonArray tops = arr(obj(obj(task, "data"), "save_as"), "save_as_top_fids");
            if (tops != null && tops.size() > 0) return tops.get(0).getAsString();
            Thread.sleep(1000);
        }
        throw new Exception("夸克转存超时");
    }

    private String savedFid(String shareId, String stoken, String fid, String fidToken, boolean clean)
            throws Exception {
        String cached = savedFids.get(fid);
        if (!clean && !TextUtils.isEmpty(cached)) return cached;
        String saved = save(shareId, stoken, fid, fidToken, clean);
        savedFids.put(fid, saved);
        return saved;
    }

    /** 原画直链。 */
    private String downloadUrl(String shareId, String stoken, String fid, String fidToken)
            throws Exception {
        String saved = savedFid(shareId, stoken, fid, fidToken, true);
        JsonArray fids = new JsonArray();
        fids.add(saved);
        JsonObject body = new JsonObject();
        body.add("fids", fids);
        JsonObject resp = api("file/download?" + PR, body.toString(), true);
        JsonArray data = arr(resp, "data");
        if (data == null || data.size() == 0) throw new Exception("夸克直链获取失败");
        return str(data.get(0).getAsJsonObject(), "download_url", "");
    }

    /** 转码档位列表。 */
    private JsonArray transcoding(String shareId, String stoken, String fid, String fidToken)
            throws Exception {
        String saved = savedFid(shareId, stoken, fid, fidToken, false);
        JsonObject body = new JsonObject();
        body.addProperty("fid", saved);
        body.addProperty("resolutions", "normal,low,high,super,2k,4k");
        body.addProperty("supports", "fmp4");
        JsonObject resp = api("file/v2/play?" + PR, body.toString(), true);
        JsonArray list = arr(obj(resp, "data"), "video_list");
        return list == null ? new JsonArray() : list;
    }

    /* ------------------------------------------------------------------ */
    /* 播放                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 换取播放地址。
     *
     * <p>直链有时效且绑 UA/Cookie，所以只在这里取，不在列表阶段提前取。返回的 header 必须原样
     * 带给播放器，否则夸克会拒绝。
     */
    @Override
    public String play(String flag, String id) throws Exception {
        String[] ids = id.split("\\*");
        if (ids.length < 4) throw new Exception("夸克播放参数不完整");
        String shareId = ids[0];
        String stoken = ids[1];
        String fid = ids[2];
        String fidToken = ids[3];

        List<String> urls = new ArrayList<>();
        // 原画放第一位，转码档位按清晰度依次跟上
        urls.add("原画");
        urls.add(downloadUrl(shareId, stoken, fid, fidToken));
        try {
            for (JsonElement element : transcoding(shareId, stoken, fid, fidToken)) {
                if (element == null || !element.isJsonObject()) continue;
                JsonObject item = element.getAsJsonObject();
                if (!bool(item, "accessable")) continue;
                String resolution = str(item, "resolution", "").toLowerCase(Locale.ROOT);
                String url = deepString(item, "video_info", "url");
                if (TextUtils.isEmpty(url)) continue;
                urls.add(displayName(resolution));
                urls.add(url);
            }
        } catch (Throwable e) {
            // 转码是可选增强，失败不影响原画播放
            SpiderDebug.log("夸克转码档位获取失败 " + e);
        }

        Map<String, String> header = new HashMap<>();
        header.put("User-Agent", UA_PC);
        header.put("Referer", "https://pan.quark.cn");
        header.put("Cookie", cookie());

        Result result = Result.get().url(urls).header(header);
        List<Sub> subs = subtitle(ids, shareId, stoken);
        if (!subs.isEmpty()) result.subs(subs);
        return result.string();
    }

    private List<Sub> subtitle(String[] ids, String shareId, String stoken) {
        List<Sub> subs = new ArrayList<>();
        if (ids.length < 6 || TextUtils.isEmpty(ids[4]) || TextUtils.isEmpty(ids[5])) return subs;
        try {
            String url = downloadUrl(shareId, stoken, ids[4], ids[5]);
            if (TextUtils.isEmpty(url)) return subs;
            subs.add(Sub.create().name("字幕").ext("srt").url(url));
        } catch (Throwable e) {
            SpiderDebug.log("夸克字幕获取失败 " + e);
        }
        return subs;
    }

    private static String displayName(String resolution) {
        for (int i = 0; i < RESOLUTIONS.length; i++) {
            if (RESOLUTIONS[i].equals(resolution)) return RESOLUTION_NAMES[i];
        }
        return TextUtils.isEmpty(resolution) ? "转码" : resolution;
    }

    /* ------------------------------------------------------------------ */
    /* JSON 工具                                                           */
    /* ------------------------------------------------------------------ */

    private static JsonObject obj(JsonObject root, String key) {
        if (root == null || !root.has(key)) return null;
        JsonElement e = root.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    private static JsonArray arr(JsonObject root, String key) {
        if (root == null || !root.has(key)) return null;
        JsonElement e = root.get(key);
        return e != null && e.isJsonArray() ? e.getAsJsonArray() : null;
    }

    private static String str(JsonObject root, String key, String fallback) {
        if (root == null || !root.has(key)) return fallback;
        JsonElement e = root.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return fallback;
        return e.getAsString();
    }

    private static long num(JsonObject root, String key, long fallback) {
        if (root == null || !root.has(key)) return fallback;
        JsonElement e = root.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return fallback;
        try {
            return e.getAsLong();
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static boolean bool(JsonObject root, String key) {
        if (root == null || !root.has(key)) return false;
        JsonElement e = root.get(key);
        if (e == null || e.isJsonNull() || !e.isJsonPrimitive()) return false;
        try {
            return e.getAsBoolean();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 逐层下钻，最后一段按字符串取。 */
    private static String deepString(JsonObject root, String... path) {
        JsonObject cur = root;
        for (int i = 0; i < path.length - 1; i++) {
            cur = obj(cur, path[i]);
            if (cur == null) return "";
        }
        return str(cur, path[path.length - 1], "");
    }
}