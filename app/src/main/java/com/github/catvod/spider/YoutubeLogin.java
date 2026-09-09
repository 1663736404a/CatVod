package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.bean.Result;
import com.github.catvod.bean.Vod;

import java.util.ArrayList;
import java.util.List;

/**
 * The sign-in surface of the YouTube spider.
 *
 * <p>A Spider owns no UI, so the login flow is expressed with what every host already renders: one
 * catalog category whose cards carry {@code action} strings. Opening the category starts the device
 * flow and shows the QR code as the card's poster; tapping a card runs the matching action through
 * {@link com.github.catvod.crawler.Spider#action(String)}.
 *
 * <p>Playback never depends on anything here — {@link YoutubeOAuth#loggedIn()} is the only switch.
 */
final class YoutubeLogin {

    /** Category id reserved for this page. */
    static final String TID = "YT_LOGIN";

    static final String ACTION_LOGIN = "yt_oauth_login";
    static final String ACTION_STATUS = "yt_oauth_status";
    static final String ACTION_LOGOUT = "yt_oauth_logout";

    private static final String PIC_LOGGED_IN =
            "https://www.gstatic.com/youtube/img/tvfilm/clairvoyance/yt_ring_red_lightTheme.png";

    private YoutubeLogin() {
    }

    /** @return the category name, so the home screen shows the current state at a glance. */
    static String title() {
        return YoutubeOAuth.loggedIn() ? "已登录" : "登录";
    }

    static boolean handles(String tid) {
        return TID.equals(tid);
    }

    static boolean isAction(String action) {
        return ACTION_LOGIN.equals(action) || ACTION_STATUS.equals(action) || ACTION_LOGOUT.equals(action);
    }

    /* ------------------------------------------------------------------ */
    /* page                                                               */
    /* ------------------------------------------------------------------ */

    static String page() {
        List<Vod> list = new ArrayList<>();
        if (YoutubeOAuth.loggedIn()) {
            list.add(card("已登录 YouTube", "播放走登录线路，无需 poToken", PIC_LOGGED_IN, ACTION_STATUS));
            list.add(card("退出登录", "回到匿名线路（需要 poToken）", null, ACTION_LOGOUT));
            return Result.get().vod(list).page(1, 1, list.size(), list.size()).string();
        }
        // Start the flow on open rather than on tap: the card poster is the only place a QR code can
        // appear, so the code has to exist before the page is rendered.
        YoutubeOAuth.Device device = YoutubeOAuth.begin();
        if (device != null && "error".equals(device.state)) {
            list.add(card("取码失败，点此重试", device.error, null, ACTION_LOGIN));
            return Result.get().vod(list).page(1, 1, list.size(), list.size()).string();
        }
        String code = device == null ? "" : device.userCode;
        String pic = device == null ? null : YoutubeQr.dataUrl(device.qrTarget());
        list.add(card(TextUtils.isEmpty(code) ? "扫码登录" : "扫码登录  " + code,
                "手机扫码，或访问 youtube.com/activate 输入上面的验证码", pic, ACTION_STATUS));
        list.add(card("我已授权，检查状态", "授权后点此确认", null, ACTION_STATUS));
        list.add(card("重新获取验证码", "二维码过期时使用", null, ACTION_LOGIN));
        return Result.get().vod(list).page(1, 1, list.size(), list.size()).string();
    }

    /* ------------------------------------------------------------------ */
    /* actions                                                            */
    /* ------------------------------------------------------------------ */

    static String action(String action) {
        if (ACTION_LOGOUT.equals(action)) {
            YoutubeOAuth.logout();
            return Result.notify("已退出 YouTube 登录，返回上一页刷新");
        }
        if (ACTION_LOGIN.equals(action)) {
            YoutubeOAuth.reset();
            YoutubeOAuth.Device device = YoutubeOAuth.begin();
            if (device == null || "error".equals(device.state)) {
                return Result.notify("取码失败: " + (device == null ? "unknown" : device.error));
            }
            return Result.notify("验证码 " + device.userCode + "，返回上一页刷新二维码");
        }
        return Result.notify(status());
    }

    private static String status() {
        if (YoutubeOAuth.loggedIn()) return "已登录，播放将使用登录线路";
        YoutubeOAuth.Device device = YoutubeOAuth.pending();
        if (device == null) return "尚未登录，返回上一页重新获取验证码";
        if ("error".equals(device.state)) return "授权失败: " + device.error;
        long left = Math.max(0L, (device.expiresAt - System.currentTimeMillis()) / 1000L);
        return "等待授权中，验证码 " + device.userCode + "，剩余 " + left + " 秒";
    }

    private static Vod card(String name, String remark, String pic, String action) {
        Vod vod = new Vod();
        vod.setVodId("");
        vod.setVodName(name);
        vod.setVodPic(pic == null ? "" : pic);
        vod.setVodRemarks(remark);
        vod.setStyle(Vod.Style.rect(1.0f));
        vod.setAction(action);
        return vod;
    }
}
