package com.github.catvod.spider;

import android.graphics.Bitmap;

import com.github.catvod.crawler.SpiderDebug;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Renders the login QR code as a PNG served through the spider's own proxy route.
 *
 * <p>A card poster is the only place a Spider can show something scannable, since it owns no UI.
 * The image is delivered as a proxy URL rather than a {@code data:} URL because hosts feed posters
 * to an image loader that generally only understands http(s): with a data URL the card rendered
 * blank even though the PNG was generated correctly (observed in webhtv, where the emitted
 * {@code vod_pic} held a valid base64 PNG and nothing appeared).
 */
final class YoutubeQr {

    private static final int SIZE = 480;
    private static final int MARGIN = 2;

    private YoutubeQr() {
    }

    /** Proxy URL of the QR image for {@code text}. The {@code v} param busts the poster cache. */
    static String url(String siteKey, String text, String version) {
        if (text == null || text.isEmpty()) return "";
        return Proxy.getUrl(siteKey, "&type=yt_qr&v=" + android.net.Uri.encode(version == null ? "0" : version));
    }

    /** Answers the {@code yt_qr} proxy route with a PNG, or {@code null} when nothing is pending. */
    static Object[] proxy() {
        YoutubeOAuth.Device device = YoutubeOAuth.pending();
        if (device == null) return null;
        byte[] png = png(device.qrTarget());
        if (png == null) return null;
        Map<String, String> headers = new HashMap<>();
        // The code changes between authorisations, so a cached poster must not be reused.
        headers.put("Cache-Control", "no-store");
        return new Object[]{200, "image/png", new ByteArrayInputStream(png), headers};
    }

    /** @return PNG bytes, or {@code null} when encoding fails. */
    static byte[] png(String text) {
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
            return out.toByteArray();
        } catch (Throwable e) {
            SpiderDebug.log("YouTube 二维码生成失败: " + e);
            return null;
        }
    }
}
