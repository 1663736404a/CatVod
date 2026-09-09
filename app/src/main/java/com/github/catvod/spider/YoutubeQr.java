package com.github.catvod.spider;

import android.graphics.Bitmap;
import android.util.Base64;

import com.github.catvod.crawler.SpiderDebug;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders a QR code as a {@code data:image/png;base64} URL.
 *
 * <p>Used as a poster image on the login card, which is the only way to show the user something
 * scannable: a Spider has no UI of its own, and a card poster is rendered by every host.
 */
final class YoutubeQr {

    private static final int SIZE = 480;
    private static final int MARGIN = 2;

    private YoutubeQr() {
    }

    /** @return a data URL, or {@code null} when encoding fails. */
    static String dataUrl(String text) {
        if (text == null || text.isEmpty()) return null;
        try {
            Map<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, MARGIN);
            BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, SIZE, SIZE, hints);
            int width = matrix.getWidth();
            int height = matrix.getHeight();
            int[] pixels = new int[width * height];
            for (int y = 0; y < height; y++) {
                int offset = y * width;
                for (int x = 0; x < width; x++) {
                    pixels[offset + x] = matrix.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
                }
            }
            Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
            bitmap.recycle();
            return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Throwable e) {
            SpiderDebug.log("YouTube 二维码生成失败: " + e);
            return null;
        }
    }
}
