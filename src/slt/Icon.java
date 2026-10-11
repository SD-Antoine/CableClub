package slt;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
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
 * Draws the SLT logo (two linked rings on an onyx tile) and can write it as
 * .ico/.png for packaging: {@code java slt.Icon out.ico out.png}.
 */
public final class Icon {

    private static final Color ONYX = new Color(0x1A, 0x1A, 0x1C);
    private static final Color LIGHT = new Color(0xEC, 0xEC, 0xEA);
    private static final Color ACCENT = new Color(0x7C, 0x9C, 0xFF);

    private Icon() {
    }

    public static BufferedImage render(int size) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        double pad = size * 0.03;
        double d = size - 2 * pad;
        g.setColor(ONYX);
        g.fill(new RoundRectangle2D.Double(pad, pad, d, d, d * 0.42, d * 0.42));

        double r = d * 0.235;
        double cy = size / 2.0;
        double left = size / 2.0 - r * 0.62;
        double right = size / 2.0 + r * 0.62;
        float stroke = (float) Math.max(1.6, d * 0.085);
        g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND));
        Ellipse2D a = new Ellipse2D.Double(left - r, cy - r, 2 * r, 2 * r);
        Ellipse2D b = new Ellipse2D.Double(right - r, cy - r, 2 * r, 2 * r);
        // interlocked: left ring, right ring, then the left ring's upper arc drawn again on top
        g.setColor(LIGHT);
        g.draw(a);
        g.setColor(ACCENT);
        g.draw(b);
        g.setColor(LIGHT);
        g.draw(new Arc2D.Double(left - r, cy - r, 2 * r, 2 * r, -10, 70, Arc2D.OPEN));
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
