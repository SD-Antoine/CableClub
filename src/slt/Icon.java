package slt;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the Cable Club logo (a link cable shaped like a C, its two plugs
 * facing each other, on an onyx tile; same drawing as web/img/logo.svg) and
 * can write it as .ico/.png for packaging: {@code java slt.Icon out.ico out.png}.
 */
public final class Icon {

    private static final Color ONYX = new Color(0x1A, 0x1A, 0x1C);
    private static final Color LIGHT = new Color(0xEC, 0xEC, 0xE9);
    private static final Color METAL = new Color(0xA6, 0xAB, 0xB5);
    private static final Color BLUE = new Color(0x8A, 0xA4, 0xFF);
    private static final Color VIOLET = new Color(0xB3, 0x8C, 0xFF);
    private static final Color SPARK = new Color(0xFF, 0xD3, 0x6B);

    private Icon() {
    }

    public static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        // drawn in the 64 x 64 units of the SVG
        g.scale(size / 64.0, size / 64.0);
        g.setColor(ONYX);
        g.fill(new RoundRectangle2D.Double(2, 2, 60, 60, 26, 26));

        // the cable: from the upper plug, round the left, to the lower plug
        Path2D cable = new Path2D.Double();
        cable.moveTo(43.5, 18);
        cable.curveTo(43.5, 13, 38.5, 11.8, 31, 11.8);
        cable.append(new Arc2D.Double(31 - 20.2, 32 - 20.2, 40.4, 40.4, 90, 180, Arc2D.OPEN), true);
        cable.curveTo(38.5, 52.2, 43.5, 51, 43.5, 46);
        g.setPaint(new GradientPaint(10.8f, 11.8f, BLUE, 43.5f, 52.2f, VIOLET));
        g.setStroke(new BasicStroke(6.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(cable);

        // the plugs: body rounded on the cable side, metal tip towards the other plug
        g.setColor(LIGHT);
        g.fill(new RoundRectangle2D.Double(36.7, 18.6, 13.6, 7.4, 3.6, 3.6));
        g.fill(new Rectangle2D.Double(36.7, 22, 13.6, 4));
        g.fill(new RoundRectangle2D.Double(36.7, 38, 13.6, 7.4, 3.6, 3.6));
        g.fill(new Rectangle2D.Double(36.7, 38, 13.6, 4));
        g.setColor(METAL);
        g.fill(new RoundRectangle2D.Double(40.2, 26, 6.6, 3.4, 1.4, 1.4));
        g.fill(new RoundRectangle2D.Double(40.2, 34.6, 6.6, 3.4, 1.4, 1.4));

        // the spark of the link (too thin to be seen in the smallest icons)
        if (size >= 24) {
            Path2D spark = new Path2D.Double();
            spark.moveTo(44.6, 29.8);
            spark.lineTo(42.4, 32.4);
            spark.lineTo(44.6, 32.4);
            spark.lineTo(42.4, 35);
            g.setColor(SPARK);
            g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(spark);
        }
        g.dispose();
        return img;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    public static void writeIco(Path file, int... sizes) throws IOException {
        List<byte[]> images = new ArrayList<>();
        for (int s : sizes) {
            images.add(png(render(s)));
        }
        int headerSize = 6 + 16 * sizes.length;
        int total = headerSize;
        for (byte[] b : images) {
            total += b.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = headerSize;
        for (int i = 0; i < sizes.length; i++) {
            int s = sizes[i];
            buf.put((byte) (s >= 256 ? 0 : s)).put((byte) (s >= 256 ? 0 : s));
            buf.put((byte) 0).put((byte) 0);
            buf.putShort((short) 1).putShort((short) 32);
            buf.putInt(images.get(i).length).putInt(offset);
            offset += images.get(i).length;
        }
        for (byte[] b : images) {
            buf.put(b);
        }
        Files.write(file, buf.array());
    }

    public static void main(String[] args) throws IOException {
        Path ico = Path.of(args.length > 0 ? args[0] : "slt.ico");
        writeIco(ico, 16, 24, 32, 48, 64, 128, 256);
        if (args.length > 1) {
            ImageIO.write(render(256), "png", Path.of(args[1]).toFile());
        }
    }
}
