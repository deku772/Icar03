package com.icarme.lyrics.phone;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.util.EnumMap;
import java.util.Map;

/** ZXing 二维码 */
public final class QrEncoder {
    private QrEncoder() {}

    public static Bitmap encode(String text, int scale) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 1, 1, hints);
            int n = m.getWidth();
            int size = n * scale;
            Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            bmp.eraseColor(Color.WHITE);
            for (int y = 0; y < n; y++)
                for (int x = 0; x < n; x++) {
                    if (!m.get(x, y)) continue;
                    int x0 = x * scale, y0 = y * scale;
                    for (int dy = 0; dy < scale; dy++)
                        for (int dx = 0; dx < scale; dx++)
                            bmp.setPixel(x0 + dx, y0 + dy, Color.BLACK);
                }
            return bmp;
        } catch (Exception e) {
            throw new IllegalStateException("QR encode failed: " + e, e);
        }
    }
}
