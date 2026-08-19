package com.github.catvod.spider;

import com.github.catvod.utils.Notify;

import java.util.ArrayList;
import java.util.List;

/**
 * 尚未实现的网盘占位驱动。
 *
 * <p>让分享链能被识别并在详情页显示出来，点播放时给出明确提示，而不是静默丢弃。等某家网盘真正
 * 实现了，把 {@link Config#PANS} 里的这一项换成实际驱动即可。
 */
final class ApiStub implements ApiPan {

    private final String key;
    private final String name;
    private final String host;

    ApiStub(String key, String name, String host) {
        this.key = key;
        this.name = name;
        this.host = host;
    }

    @Override
    public String key() {
        return key;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public boolean match(String shareUrl) {
        return shareUrl != null && shareUrl.contains(host);
    }

    /** 返回单个占位条目，让线路在详情页可见。 */
    @Override
    public List<ApiPan.Item> parse(String shareUrl) {
        List<ApiPan.Item> list = new ArrayList<>();
        list.add(new ApiPan.Item(name + "（暂未支持）", "stub:" + key));
        return list;
    }

    @Override
    public String play(String flag, String id) throws Exception {
        throw new Exception(name + "暂未支持，敬请期待");
    }

    @Override
    public boolean logged() {
        return false;
    }

    @Override
    public String status() {
        return "暂未支持";
    }

    @Override
    public void setCookie(String cookie) {
    }

    @Override
    public String getCookie() {
        return "";
    }

    @Override
    public void logout() {
    }

    @Override
    public void startFlow() {
        Notify.show(name + "暂未支持，敬请期待");
    }

    @Override
    public void startScan() {
        Notify.show(name + "暂未支持，敬请期待");
    }
}