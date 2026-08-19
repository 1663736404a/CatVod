package com.github.catvod.spider;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.catvod.crawler.SpiderDebug;
import com.github.catvod.utils.Notify;
import com.github.catvod.utils.Util;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * 网盘驱动的交互层：Cookie 输入框和扫码二维码。
 *
 * <p>jar 里没有自己的 Activity，所以靠反射拿宿主当前前台的 Activity 来弹 {@link AlertDialog}。
 * 这是 spider jar 里出弹窗的通行做法，宿主没有为此提供接口。拿不到 Activity 时降级为剪贴板 +
 * Toast，不会静默失败。
 *
 * <p>所有 UI 操作都会被切到主线程，调用方不需要关心自己在哪个线程。
 */
final class ApiUi {

    private ApiUi() {
    }

    /** 输入框回调。 */
    interface OnText {
        void onText(String text);
    }

    /**
     * 反射取当前前台 Activity。
     *
     * <p>遍历 ActivityThread 的 mActivities，挑没有 paused 的那个。字段名从 Android 4 到 16 都
     * 没变过，取不到就返回 null，由调用方降级。
     */
    static Activity activity() {
        try {
            Class<?> clazz = Class.forName("android.app.ActivityThread");
            Object thread = clazz.getMethod("currentActivityThread").invoke(null);
            Field field = clazz.getDeclaredField("mActivities");
            field.setAccessible(true);
            Map<?, ?> records = (Map<?, ?>) field.get(thread);
            if (records == null) return null;
            for (Object record : records.values()) {
                Class<?> recordClass = record.getClass();
                Field paused = recordClass.getDeclaredField("paused");
                paused.setAccessible(true);
                if (paused.getBoolean(record)) continue;
                Field activity = recordClass.getDeclaredField("activity");
                activity.setAccessible(true);
                return (Activity) activity.get(record);
            }
        } catch (Throwable e) {
            SpiderDebug.log("获取前台 Activity 失败 " + e);
        }
        return null;
    }

    private static int dp(int value) {
        try {
            return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                    Init.context().getResources().getDisplayMetrics());
        } catch (Throwable e) {
            return value * 2;
        }
    }

    /**
     * 弹一个单行输入框。
     *
     * <p>三个按钮：确定回填文本、中间键走扫码、取消什么都不做。文本回调在主线程触发，里面别做
     * 网络请求。
     */
    static void input(String title, String hint, String neutral, OnText positive, Runnable onNeutral) {
        Init.post(() -> {
            Activity activity = activity();
            if (activity == null) {
                Notify.show("请把应用切到前台再操作");
                return;
            }
            try {
                int margin = dp(16);
                EditText edit = new EditText(activity);
                edit.setHint(hint);
                edit.setSingleLine(false);
                edit.setMaxLines(4);
                FrameLayout frame = new FrameLayout(activity);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.setMargins(margin, margin, margin, margin);
                frame.addView(edit, params);
                AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                        .setTitle(title)
                        .setView(frame)
                        .setNegativeButton(android.R.string.cancel, null)
                        .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                            String text = edit.getText() == null ? "" : edit.getText().toString().trim();
                            if (TextUtils.isEmpty(text)) Notify.show("没有输入内容");
                            else positive.onText(text);
                        });
                if (!TextUtils.isEmpty(neutral) && onNeutral != null) {
                    builder.setNeutralButton(neutral, (dialog, which) -> onNeutral.run());
                }
                builder.show();
            } catch (Throwable e) {
                SpiderDebug.log("弹出输入框失败 " + e);
                Notify.show("弹窗失败：" + e);
            }
        });
    }

    /**
     * 二维码面板。
     *
     * <p>轮询线程通过 {@link #closed()} 知道用户是否已经关掉弹窗，从而及时停止，不留后台空转。
     */
    static final class Panel {

        private volatile AlertDialog dialog;
        private volatile boolean dismissed;

        /**
         * 用户已主动关闭弹窗。
         *
         * <p>只在用户关闭时为 true。走剪贴板降级时不算关闭 —— 用户手上还有扫码地址，轮询得继续。
         */
        boolean closed() {
            return dismissed;
        }

        void close() {
            dismissed = true;
            AlertDialog current = dialog;
            if (current == null) return;
            Init.post(() -> {
                try {
                    current.dismiss();
                } catch (Throwable ignored) {
                    // Activity 可能已经销毁，忽略
                }
            });
        }

        private void onDismiss(DialogInterface unused) {
            dismissed = true;
        }
    }

    /**
     * 显示二维码。
     *
     * <p>本地生成位图，不经过任何在线出图服务 —— 二维码内容里带的是可换取登录态的 token，交给第
     * 三方等于把账号交出去。
     *
     * <p>拿不到 Activity 或生成失败时，把内容复制到剪贴板兜底，用户可以自己找个工具出码，轮询照
     * 常进行。
     */
    static Panel qrcode(String content, String tip) {
        Panel panel = new Panel();
        Init.post(() -> {
            Activity activity = activity();
            Bitmap bitmap = ApiQr.bitmap(content, dp(240));
            if (activity == null || bitmap == null) {
                copy(content);
                return;
            }
            try {
                int size = dp(240);
                int margin = dp(16);
                LinearLayout box = new LinearLayout(activity);
                box.setOrientation(LinearLayout.VERTICAL);
                box.setGravity(Gravity.CENTER);
                box.setPadding(margin, margin, margin, margin);
                box.setBackgroundColor(Color.WHITE);

                ImageView image = new ImageView(activity);
                image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                image.setImageBitmap(bitmap);
                box.addView(image, new LinearLayout.LayoutParams(size, size));

                if (!TextUtils.isEmpty(tip)) {
                    TextView text = new TextView(activity);
                    text.setText(tip);
                    text.setTextColor(Color.BLACK);
                    text.setGravity(Gravity.CENTER);
                    box.addView(text, new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }

                FrameLayout frame = new FrameLayout(activity);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                params.gravity = Gravity.CENTER;
                frame.addView(box, params);

                AlertDialog dialog = new AlertDialog.Builder(activity)
                        .setView(frame)
                        .setOnCancelListener(panel::onDismiss)
                        .show();
                // Builder.setOnDismissListener 要 API 17，直接设在 dialog 上兼容更早的版本
                dialog.setOnDismissListener(panel::onDismiss);
                if (dialog.getWindow() != null) {
                    dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
                }
                panel.dialog = dialog;
            } catch (Throwable e) {
                SpiderDebug.log("显示二维码失败 " + e);
                copy(content);
            }
        });
        return panel;
    }

    private static void copy(String content) {
        try {
            Util.copy(content);
            Notify.show("无法弹窗，扫码地址已复制到剪贴板");
        } catch (Throwable e) {
            Notify.show("扫码地址：" + content);
        }
    }
}
