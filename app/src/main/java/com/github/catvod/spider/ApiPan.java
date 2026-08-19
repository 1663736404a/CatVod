package com.github.catvod.spider;

import java.util.List;

/**
 * 网盘驱动的公共接口。
 *
 * <p>一个驱动负责一家网盘：持有登录态、解析分享链、换取播放直链。它不是 {@link
 * com.github.catvod.crawler.Spider}，而是被影视站 spider 和 {@link Config} 共同调用的组件。
 *
 * <p>驱动实现放在同包下，以 {@code Api} 前缀命名（如 {@link ApiQuark}）。之所以不单独建
 * {@code catvod/api} 包，是因为 {@code prepareSpiderJar} 只把 {@code spider} 和 {@code js}
 * 两个目录打进 jar，其他目录的类在设备上会 NoClassDefFoundError。
 */
interface ApiPan {

    /** 驱动标识，用于配置存储的 key 前缀，例如 {@code quark}。 */
    String key();

    /** 展示名，出现在设置中心列表里。 */
    String name();

    /** 该驱动能否处理这条分享链。 */
    boolean match(String shareUrl);

    /**
     * 列出分享链里的视频文件。
     *
     * @return 供详情页展开成选集的条目，顺序即选集顺序
     */
    List<Item> parse(String shareUrl) throws Exception;

    /** 分享链里的一个可播文件。 */
    class Item {

        /** 选集显示名。 */
        final String name;
        /** 驱动自定义的定位串，会原样回到 {@link #play}。 */
        final String id;

        Item(String name, String id) {
            this.name = name;
            this.id = id;
        }
    }

    /**
     * 换取播放地址。
     *
     * @param flag 线路名，用来区分原画/转码档位
     * @param id   {@link #parse} 产出的定位串
     * @return 直接可用的 Result JSON
     */
    String play(String flag, String id) throws Exception;

    /* ---------------- 登录态，供设置中心调用 ---------------- */

    /** 当前是否已登录。 */
    boolean logged();

    /** 登录态描述，例如账号昵称或容量，用于设置中心的副标题。 */
    String status();

    /** 手动写入凭据。 */
    void setCookie(String cookie);

    /** 读取当前凭据。 */
    String getCookie();

    /** 清除本地登录态。 */
    void logout();

    /**
     * 开始一次扫码登录，返回二维码图片地址。
     *
     * @return 可直接给 ImageView 加载的 URL 或 data URI
     */
    String qrcode() throws Exception;

    /**
     * 轮询扫码结果。
     *
     * @return 登录成功返回 true；仍在等待扫码返回 false；失败抛异常
     */
    boolean checkQrcode() throws Exception;
}