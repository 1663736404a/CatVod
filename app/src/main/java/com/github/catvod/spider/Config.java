package com.github.catvod.spider;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 网盘设置中心。
 *
 * <p>本身不提供影片，只作为各网盘驱动的配置界面：首页列出所有已注册驱动，进入某个驱动后是它的
 * 操作项（扫码登录、填 Cookie、查看状态、清除登录）。
 *
 * <p>交互靠 {@link Vod#action(String)} 和 {@link #action(String)}：列表项带上 action 串，用户
 * 点击后宿主把该串回传过来，这里执行对应操作并用 {@link Result#notify(String)} 反馈。
 *
 * <p>凭据由 {@link ApiStore} 明文存在 SharedPreferences，取得 root 或 adb 的一方可以直接读到，
 * 不要在共用设备上登录。
 */
public class Config extends Spider {

    /** 已注册的网盘驱动。新增网盘时在这里加一行即可。 */
    private static final List<ApiPan> PANS = Arrays.asList(
            ApiQuark.get()
    );

    /** action 串前缀，避免和其他 spider 的 action 混淆。 */
    private static final String ACT = "pan://";

    @Override
    public void init(Context context, String extend) {
        // 无需配置，所有状态都在 ApiStore 里
    }

    private static ApiPan find(String key) {
        for (ApiPan pan : PANS) if (pan.key().equals(key)) return pan;
        return null;
    }

    /* ------------------------------------------------------------------ */
    /* 列表                                                                */
    /* ------------------------------------------------------------------ */

    /** 首页即设置中心，每个分类是一个网盘。 */
    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        for (ApiPan pan : PANS) classes.add(new Class(pan.key(), pan.name()));
        return Result.string(classes, new LinkedHashMap<>());
    }

    /** 某个网盘的操作项。 */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        ApiPan pan = find(tid);
        if (pan == null) return Result.get().vod(new ArrayList<>()).page().string();
        List<Vod> list = new ArrayList<>();
        boolean logged = pan.logged();

        list.add(item("当前状态", logged ? pan.status() : "未登录",
                ACT + pan.key() + "/status"));
        list.add(item("扫码登录", logged ? "已登录，可重新扫码更换账号" : "使用" + pan.name() + "App 扫码",
                ACT + pan.key() + "/qrcode"));
        list.add(item("手动填入 Cookie", logged ? "已填写" : "从浏览器复制完整 Cookie",
                ACT + pan.key() + "/cookie"));
        list.add(item("清除本地登录", "保留手填凭据，只清运行期刷新的部分",
                ACT + pan.key() + "/clearLocal"));
        list.add(item("清除账号", "彻底删除本地保存的凭据",
                ACT + pan.key() + "/logout"));
        return Result.get().vod(list).page().string();
    }

    private static Vod item(String name, String remark, String action) {
        Vod vod = new Vod();
        vod.setVodId(action);
        vod.setVodName(name);
        vod.setVodRemarks(remark);
        vod.setAction(action);
        return vod;
    }

    /**
     * 详情页兜底。
     *
     * <p>宿主对带 action 的条目通常直接回调 {@link #action(String)} 而不进详情页，但不同版本行为
     * 不一致，这里保证进来了也不会白屏。
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0);
        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName("设置中心");
        vod.setVodContent("请返回列表点击对应项目进行操作");
        return Result.string(vod);
    }

    /* ------------------------------------------------------------------ */
    /* 操作                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 执行设置项。
     *
     * <p>action 格式 {@code pan://<驱动key>/<操作>}，可带 {@code ?value=} 用于回填输入。
     */
    @Override
    public String action(String action) {
        try {
            if (TextUtils.isEmpty(action) || !action.startsWith(ACT)) return Result.notify("未知操作");
            String path = action.substring(ACT.length());
            String query = "";
            int mark = path.indexOf('?');
            if (mark > 0) {
                query = path.substring(mark + 1);
                path = path.substring(0, mark);
            }
            int slash = path.indexOf('/');
            if (slash <= 0) return Result.notify("未知操作");
            ApiPan pan = find(path.substring(0, slash));
            if (pan == null) return Result.notify("未知网盘");
            String op = path.substring(slash + 1);

            switch (op) {
                case "status":
                    return Result.notify(pan.name() + "：" + (pan.logged() ? pan.status() : "未登录"));
                case "qrcode":
                    return qrcode(pan);
                case "check":
                    return check(pan);
                case "cookie":
                    return cookie(pan, query);
                case "clearLocal":
                    if (pan instanceof ApiQuark) ((ApiQuark) pan).clearLocal();
                    else pan.logout();
                    return Result.notify("已清除本地登录");
                case "logout":
                    pan.logout();
                    return Result.notify("已清除" + pan.name() + "账号");
                default:
                    return Result.notify("未知操作");
            }
        } catch (Throwable e) {
            SpiderDebug.log("设置中心操作失败 " + e);
            return Result.notify(TextUtils.isEmpty(e.getMessage()) ? "操作失败" : e.getMessage());
        }
    }

    /**
     * 出二维码。
     *
     * <p>宿主的 action 回调只能回文本，没有直接弹图的通道，所以把二维码图片地址复制到剪贴板并提示
     * 用户。扫完之后点「当前状态」或再点一次本项会触发轮询。
     */
    private String qrcode(ApiPan pan) throws Exception {
        String url = pan.qrcode();
        Util.copy(url);
        return Result.notify("二维码地址已复制，用浏览器打开后用 " + pan.name()
                + " App 扫码，完成后点「当前状态」确认");
    }

    /** 轮询扫码结果。 */
    private String check(ApiPan pan) throws Exception {
        return pan.checkQrcode()
                ? Result.notify("登录成功：" + pan.status())
                : Result.notify("尚未扫码或未确认，请稍后再试");
    }

    /**
     * 写入 Cookie。
     *
     * <p>宿主不提供输入框，所以从剪贴板读 —— 用户先复制好 Cookie，再点这一项。
     */
    private String cookie(ApiPan pan, String query) {
        String value = value(query, "value");
        if (TextUtils.isEmpty(value)) value = paste();
        if (TextUtils.isEmpty(value)) return Result.notify("剪贴板为空，请先复制 Cookie 再点此项");
        pan.setCookie(value);
        return Result.notify(pan.logged() ? "已保存：" + pan.status() : "保存失败");
    }

    /** 读剪贴板首条文本。{@code Util} 只有 copy 没有 paste，这里自己取。 */
    private static String paste() {
        try {
            ClipboardManager manager = (ClipboardManager)
                    Init.context().getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null || !manager.hasPrimaryClip()) return "";
            ClipData data = manager.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) return "";
            CharSequence text = data.getItemAt(0).coerceToText(Init.context());
            return text == null ? "" : text.toString().trim();
        } catch (Throwable e) {
            SpiderDebug.log("读取剪贴板失败 " + e);
            return "";
        }
    }

    private static String value(String query, String key) {
        if (TextUtils.isEmpty(query)) return "";
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) continue;
            if (key.equals(part.substring(0, eq))) return Uri.decode(part.substring(eq + 1));
        }
        return "";
    }

    /* ------------------------------------------------------------------ */
    /* 供影视 spider 调用                                                  */
    /* ------------------------------------------------------------------ */

    /** 找出能处理这条分享链的驱动。 */
    static ApiPan match(String shareUrl) {
        for (ApiPan pan : PANS) if (pan.match(shareUrl)) return pan;
        return null;
    }
}