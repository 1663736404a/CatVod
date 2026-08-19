package com.github.catvod.spider;

import android.text.TextUtils;

import com.github.catvod.utils.Prefers;

/**
 * 网盘凭据的本地存储。
 *
 * <p>按用户要求以明文存入 SharedPreferences。这意味着取得 root 或 adb 权限的一方可以直接读到
 * 完整的网盘账号凭据，请勿在共用设备上登录。
 *
 * <p>每个驱动两个槽位：
 * <ul>
 *   <li>{@code pan_<key>} —— 用户手填或扫码得到的原始凭据，作为基准
 *   <li>{@code pan_<key>_live} —— 运行期被服务端刷新过的凭据（夸克的 {@code __puus} 会滚动）
 * </ul>
 * 读取时优先用 live，回落到基准。清除本地登录只清 live，清除账号才清基准 —— 对应设置中心里的
 * 两个不同操作。
 */
final class ApiStore {

    private ApiStore() {
    }

    private static String baseKey(String pan) {
        return "pan_" + pan;
    }

    private static String liveKey(String pan) {
        return "pan_" + pan + "_live";
    }

    /** 取当前有效凭据，live 优先。 */
    static String get(String pan) {
        String live = Prefers.getString(liveKey(pan), "");
        if (!TextUtils.isEmpty(live)) return live;
        return Prefers.getString(baseKey(pan), "");
    }

    /** 写入用户提供的凭据，同时清掉旧的 live，避免新旧混用。 */
    static void put(String pan, String cookie) {
        Prefers.put(baseKey(pan), cookie == null ? "" : cookie.trim());
        Prefers.put(liveKey(pan), "");
    }

    /** 记录服务端刷新后的凭据，不动基准。 */
    static void putLive(String pan, String cookie) {
        Prefers.put(liveKey(pan), cookie == null ? "" : cookie);
    }

    /** 清除本地登录态，保留用户填的基准凭据。 */
    static void clearLive(String pan) {
        Prefers.put(liveKey(pan), "");
    }

    /** 彻底清除该网盘的所有凭据。 */
    static void clear(String pan) {
        Prefers.put(baseKey(pan), "");
        Prefers.put(liveKey(pan), "");
    }
}