package com.github.catvod.spider;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.github.catvod.crawler.SpiderDebug;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

import java.util.EnumMap;
import java.util.Map;

/**
 * 二维码出图。
 *
 * <p>只用 zxing 的 {@link QRCodeWriter}，不走 {@code MultiFormatWriter} —— 后者会把条形码、
 * PDF417、Aztec 一堆编码器全拖进 jar，白占体积。
 */
final class ApiQr {

    private ApiQr() {
    }

    /**
     * 生成二维码位图。
     *
     * @param content 待编码内容
     * @param size    边长像素
     * @return 失败返回 null，由调用方降级处理
     */
    static Bitmap bitmap(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? Color.BLACK : Color.WHITE;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            return bitmap;
        } catch (Throwable e) {
            SpiderDebug.log("二维码生成失败 " + e);
            return null;
        }
    }
}
