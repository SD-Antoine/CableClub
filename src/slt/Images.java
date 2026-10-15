package slt;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * Pictures made from pixels (players' sprites, live images, screenshots). The Android version of Cable Club has
 * its own class of the same name, drawn with Android's bitmaps.
 */
public final class Images {

    private Images() {
    }

    /** PNG of ARGB pixels (alpha 0 = transparent). */
    public static byte[] png(int[] argb, int w, int h) {
        return png(argb, w, h, BufferedImage.TYPE_INT_ARGB);
    }

    /** PNG of opaque RGB pixels (the top byte is ignored). */
    public static byte[] pngOpaque(int[] rgb, int w, int h) {
        return png(rgb, w, h, BufferedImage.TYPE_INT_RGB);
    }

    /** PNG of ARGB pixels in base 64 (how sprites travel). */
    public static String pngBase64(int[] argb, int w, int h) {
        return Base64.getEncoder().encodeToString(png(argb, w, h));
    }

    private static byte[] png(int[] pixels, int w, int h, int type) {
        BufferedImage img = new BufferedImage(w, h, type);
        img.setRGB(0, 0, w, h, pixels, 0, w);
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(256, w * h / 2));
        try {
            ImageIO.write(img, "png", out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** JPEG of opaque RGB pixels, quality 0 to 1. */
    public static byte[] jpeg(int[] rgb, int w, int h, float quality) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, rgb, 0, w);
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(16384);
        try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(quality);
            writer.write(null, new IIOImage(img, null, null), p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }
}
