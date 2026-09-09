package com.github.catvod.spider;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.github.catvod.crawler.SpiderDebug;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.Map;

/**
 * Shows the login QR code in a dialog the JAR builds itself.
 *
 * <p>Why not a card poster: the poster route works end to end (the host fetched the PNG and logged
 * {@code status=200 mime=image/png bytes=2940}) yet nothing appeared on screen, because this host
 * does not render posters for action cards. A dialog does not depend on how the host chooses to draw
 * a catalog item, which is what makes it the reliable surface. This mirrors what pg.jar does.
 *
 * <p>An {@link AlertDialog} needs a window, so the current activity is located through
 * {@code ActivityThread}. When no activity is resumed (headless call, host in the background) the
 * dialog is skipped and the caller's toast plus the copy-code action remain as the fallback.
 */
final class YoutubeDialog {

    private YoutubeDialog() {
    }

    /** Posts the QR dialog to the main thread. Safe to call from any thread; never throws. */
    static void showQr(String target, String code) {
        byte[] png = YoutubeQr.png(target);
        if (png == null) return;
        try {
            Init.post(() -> build(png, code));
        } catch (Throwable e) {
            SpiderDebug.log("YouTube 二维码弹窗调度失败: " + e);
        }
    }

    private static void build(byte[] png, String code) {
        try {
            Activity activity = activity();
            if (activity == null) {
                SpiderDebug.log("YouTube 二维码弹窗跳过: 未找到前台 Activity，请用「复制验证码」");
                return;
            }
            Bitmap bitmap = BitmapFactory.decodeByteArray(png, 0, png.length);
            if (bitmap == null) return;
            int pad = dp(activity, 20);
            int size = dp(activity, 260);

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            root.setGravity(Gravity.CENTER_HORIZONTAL);
            root.setPadding(pad, pad, pad, pad);
            root.setBackgroundColor(Color.WHITE);

            ImageView image = new ImageView(activity);
            image.setImageBitmap(bitmap);
            image.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            root.addView(image);

            TextView text = new TextView(activity);
            text.setText("手机扫码授权\n或访问 youtube.com/activate 输入验证码\n" + code);
            text.setTextColor(Color.BLACK);
            text.setGravity(Gravity.CENTER);
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            textParams.topMargin = dp(activity, 12);
            text.setLayoutParams(textParams);
            root.addView(text);

            new AlertDialog.Builder(activity)
                    .setView(root)
                    .setCancelable(true)
                    .setPositiveButton("授权完成", null)
                    .show();
        } catch (Throwable e) {
            SpiderDebug.log("YouTube 二维码弹窗失败: " + e);
        }
    }

    private static int dp(Context context, int value) {
        float density = context.getResources().getDisplayMetrics().density;
        return (int) (value * density + 0.5f);
    }

    /**
     * @return the activity currently resumed, or {@code null} when none is.
     *
     * <p>Reads {@code ActivityThread.mActivities}, whose records expose {@code activity} and
     * {@code paused}. Every field here belongs to the framework, so R8 does not rename them.
     */
    private static Activity activity() {
        try {
            Class<?> thread = Class.forName("android.app.ActivityThread");
            Method current = thread.getDeclaredMethod("currentActivityThread");
            current.setAccessible(true);
            Object instance = current.invoke(null);
            Field field = thread.getDeclaredField("mActivities");
            field.setAccessible(true);
            Object activities = field.get(instance);
            Collection<?> records;
            if (activities instanceof Map) {
                records = ((Map<?, ?>) activities).values();
            } else {
                return null;
            }
            for (Object record : records) {
                if (record == null) continue;
                Class<?> type = record.getClass();
                Field paused = type.getDeclaredField("paused");
                paused.setAccessible(true);
                if (paused.getBoolean(record)) continue;
                Field target = type.getDeclaredField("activity");
                target.setAccessible(true);
                Object value = target.get(record);
                if (value instanceof Activity) return (Activity) value;
            }
        } catch (Throwable e) {
            SpiderDebug.log("YouTube 前台 Activity 获取失败: " + e);
        }
        return null;
    }
}
