package slt;

import android.graphics.Bitmap;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/** Pictures made from pixels, with Android's bitmaps (the PC version uses ImageIO). */
public final class Images {

    private Images() {
    }

    /** PNG of ARGB pixels (alpha 0 = transparent). */
    public static byte[] png(int[] argb, int w, int h) {
        return encode(argb, w, h, Bitmap.CompressFormat.PNG, 100, false);
    }

    /** PNG of opaque RGB pixels (the top byte is ignored). */
    public static byte[] pngOpaque(int[] rgb, int w, int h) {
        return encode(rgb, w, h, Bitmap.CompressFormat.PNG, 100, true);
    }

    /** PNG of ARGB pixels in base 64 (how sprites travel). */
    public static String pngBase64(int[] argb, int w, int h) {
        return Base64.getEncoder().encodeToString(png(argb, w, h));
    }

    /** JPEG of opaque RGB pixels, quality 0 to 1. */
    public static byte[] jpeg(int[] rgb, int w, int h, float quality) {
        return encode(rgb, w, h, Bitmap.CompressFormat.JPEG, Math.round(quality * 100), true);
    }

    private static byte[] encode(int[] pixels, int w, int h, Bitmap.CompressFormat format, int quality, boolean opaque) {
        int[] colors = pixels;
        if (opaque) {
            colors = new int[w * h];
            for (int i = 0; i < colors.length; i++) {
                colors[i] = pixels[i] | 0xFF000000;
            }
        }
        // unpremultiplied ARGB, as given (the colours of half-transparent pixels are kept)
        Bitmap bmp = Bitmap.createBitmap(colors, 0, w, w, h, Bitmap.Config.ARGB_8888);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, w * h / 2));
        bmp.compress(format, quality, out);
        bmp.recycle();
        return out.toByteArray();
    }
}
