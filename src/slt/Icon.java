package slt;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
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
 * Draws the Cable Club logo (a link cable shaped like a C, a plug at each end,
 * on an onyx tile; same drawing as web/img/logo.svg) and
 * can write it as .ico/.png for packaging: {@code java slt.Icon out.ico out.png}.
 */
public final class Icon {

    private static final Color ONYX = new Color(0x16, 0x17, 0x1B);
    private static final Color LIGHT = new Color(0xEC, 0xEC, 0xE9);
    private static final Color BOOT = new Color(0xC9, 0xCD, 0xE0);
    private static final Color METAL = new Color(0xA6, 0xAB, 0xB5);
    private static final Color PIN = new Color(0x6B, 0x71, 0x84);
    private static final Color BLUE = new Color(0x8A, 0xA4, 0xFF);
    private static final Color VIOLET = new Color(0xB3, 0x8C, 0xFF);

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
        g.fill(new RoundRectangle2D.Double(2, 2, 60, 60, 28, 28));

        // the cable: a C that runs from one plug to the other
        Path2D cable = new Path2D.Double();
        cable.moveTo(38, 15.5);
        cable.curveTo(28, 15.5, 14.5, 18.5, 14.5, 32);
        cable.curveTo(14.5, 45.5, 28, 48.5, 38, 48.5);
        g.setPaint(new GradientPaint(14.5f, 15.5f, BLUE, 30.95f, 48.5f, VIOLET));
        g.setStroke(new BasicStroke(7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.draw(cable);
        if (size >= 24) {
            // a light along the cable
            Path2D light = new Path2D.Double();
            light.moveTo(15.6, 26);
            light.curveTo(16.6, 20, 22, 16.6, 30, 16.2);
            g.setColor(new Color(255, 255, 255, 71));
            g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(light);
        }

        // the two plugs: a flared boot, a body and a metal tip with its two contacts
        plug(g, size, 9.6, 1);
        plug(g, size, 42.8, -1);
        g.dispose();
        return img;
    }

    /** One plug, its body from y {@code top}; {@code dir} 1: the boot flares towards the bottom edge, -1: towards the top. */
    private static void plug(Graphics2D g, int size, double top, int dir) {
        Path2D boot = new Path2D.Double();
        if (dir > 0) {
            boot.moveTo(37, 12);
            boot.lineTo(42.4, 10.2);
            boot.lineTo(42.4, 20.8);
            boot.lineTo(37, 19);
        } else {
            boot.moveTo(37, 52);
            boot.lineTo(42.4, 53.8);
            boot.lineTo(42.4, 43.2);
            boot.lineTo(37, 45);
        }
        boot.closePath();
        g.setColor(BOOT);
        g.fill(boot);
        g.setColor(LIGHT);
        g.fill(new RoundRectangle2D.Double(41.4, top, 11.4, 11.6, 6, 6));
        double tip = top + 2.6;
        g.setColor(METAL);
        g.fill(new RoundRectangle2D.Double(51.6, tip, 7, 6.4, 2.8, 2.8));
        if (size >= 24) {
            g.setColor(PIN);
            g.setStroke(new BasicStroke(1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.draw(new Line2D.Double(54.4, tip + 1.4, 54.4, tip + 5));
            g.draw(new Line2D.Double(56.6, tip + 1.4, 56.6, tip + 5));
        }
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
