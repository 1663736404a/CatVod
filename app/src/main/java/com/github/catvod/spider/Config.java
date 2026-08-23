package com.github.catvod.spider;

import android.content.Context;
import android.text.TextUtils;

import com.github.catvod.bean.Class;
import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;
import com.github.catvod.crawler.Spider;
import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Image;
import com.github.catvod.utils.Notify;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 网盘设置中心。
 *
 * <p>本身不提供影片，只作为各网盘驱动的配置界面：首页每个分类是一家网盘，进去是它的操作项。
 *
 * <p>交互方式和 lushunming 版的 {@code Introduce} 一致 —— 列表项的 vodId 就是操作码，宿主点击
 * 后走 {@link #detailContent}，在那里执行对应操作。之所以不用 {@link Vod#setAction}，是因为
 * action 只能回文本，弹不出二维码和输入框；detailContent 里可以直接调驱动的 UI 流程。
 *
 * <p>凭据由 {@link ApiStore} 明文存在 SharedPreferences，取得 root 或 adb 的一方可以直接读到，
 * 不要在共用设备上登录。
 */
public class Config extends Spider {

    /** 已注册的网盘驱动。新增网盘时在这里加一行即可。 */
    static final List<ApiPan> PANS = Arrays.asList(
            ApiQuark.get(),
            new ApiStub("uc", "UC网盘", "drive.uc.cn"),
            new ApiStub("baidu", "百度网盘", "pan.baidu.com"),
            new ApiStub("115", "115网盘", "115.com")
    );

    /** 操作码分隔符：{@code <驱动key>/<操作>}。 */
    private static final String SEP = "/";

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

    /** 首页即设置中心，每个分类是一家网盘。 */
    @Override
    public String homeContent(boolean filter) {
        List<Class> classes = new ArrayList<>();
        for (ApiPan pan : PANS) classes.add(new Class(pan.key(), pan.name()));
        return Result.string(classes, new LinkedHashMap<>());
    }

    /**
     * 某家网盘的操作项。
     *
     * <p>「当前状态」放第一位，会实时打一次接口取昵称和会员等级，所以进这一页会有一次网络请求。
     */
    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) {
        ApiPan pan = find(tid);
        if (pan == null) return Result.get().vod(new ArrayList<>()).page().string();
        boolean logged = pan.logged();
        List<Vod> list = new ArrayList<>();
        list.add(item(pan, "status", "当前状态", logged ? pan.status() : "未登录"));
        list.add(item(pan, "login", "点击登录",
                logged ? "已登录，可重新填 Cookie 或扫码换号" : "填 Cookie 或扫码"));
        list.add(item(pan, "scan", "点击扫码", "直接出二维码，用 " + pan.name() + " App 扫"));
        list.add(item(pan, "clearLocal", "清除本地登录", "只清运行期刷新的部分，保留手填凭据"));
        list.add(item(pan, "logout", "点击删除", "彻底删除本地保存的凭据"));
        return Result.get().vod(list).page().string();
    }

    private static Vod item(ApiPan pan, String op, String name, String remark) {
        Vod vod = new Vod();
        vod.setVodId(pan.key() + SEP + op);
        vod.setVodName(name);
        vod.setVodRemarks(remark);
        vod.setVodPic(Image.FOLDER);
        return vod;
    }

    /* ------------------------------------------------------------------ */
    /* 操作                                                                */
    /* ------------------------------------------------------------------ */

    /**
     * 执行设置项。
     *
     * <p>宿主点条目就会走到这里，vodId 就是操作码。登录类操作都是异步的：方法立刻返回一个占位
     * 详情页，弹窗和轮询在后台继续，结果用 Toast 提示。
     */
    @Override
    public String detailContent(List<String> ids) {
        String id = ids.get(0);
        try {
            int slash = id.indexOf(SEP);
            if (slash <= 0) return placeholder(id, "设置中心", "请返回列表点击具体项目");
            ApiPan pan = find(id.substring(0, slash));
            if (pan == null) return placeholder(id, "设置中心", "未知网盘");
            String op = id.substring(slash + SEP.length());
            switch (op) {
                case "status":
                    String status = pan.logged() ? pan.status() : "未登录";
                    Notify.show(pan.name() + "：" + status);
                    return placeholder(id, pan.name(), status);
                case "login":
                    pan.startFlow();
                    return placeholder(id, pan.name(), "请在弹出的窗口里填 Cookie 或点「扫码登录」");
                case "scan":
                    pan.startScan();
                    return placeholder(id, pan.name(), "正在获取二维码");
                case "clearLocal":
                    if (pan instanceof ApiQuark) ((ApiQuark) pan).clearLocal();
                    else pan.logout();
                    Notify.show("已清除本地登录");
                    return placeholder(id, pan.name(), "已清除本地登录");
                case "logout":
                    pan.logout();
                    Notify.show("已删除" + pan.name() + "账号");
                    return placeholder(id, pan.name(), "已删除账号");
                default:
                    return placeholder(id, pan.name(), "未知操作");
            }
        } catch (Throwable e) {
            SpiderDebug.log("设置中心操作失败 " + e);
            String message = TextUtils.isEmpty(e.getMessage()) ? e.toString() : e.getMessage();
            Notify.show(message);
            return placeholder(id, "设置中心", message);
        }
    }

    /**
     * 占位详情页。
     *
     * <p>不给 vod_play_from/vod_play_url，宿主就不会显示播放按钮，避免用户在设置项上点播放。
     */
    private static String placeholder(String id, String name, String content) {
        Vod vod = new Vod();
        vod.setVodId(id);
        vod.setVodName(name);
        vod.setVodPic(Image.FOLDER);
        vod.setVodContent(content);
        return Result.string(vod);
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