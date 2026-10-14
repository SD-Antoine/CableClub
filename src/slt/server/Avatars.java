package slt.server;

import slt.Http;
import slt.Log;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;

/**
 * Profile pictures: whatever image the player sends is decoded, cut to a
 * square and redrawn as a 128 x 128 PNG (nothing of the original file is
 * kept), one file per user.
 */
final class Avatars {

    static final int SIZE = 128;
    /** What the interface may send (it already makes the picture small). */
    static final int MAX_INPUT = 600_000;
    private static final int MAX_SIDE = 4096;

    private final Path dir;

    Avatars(Path base) throws IOException {
        this.dir = base.resolve("avatars");
        Files.createDirectories(dir);
    }

    private Path file(String userId) {
        if (userId == null || !userId.matches("[A-Za-z0-9]{4,40}")) {
            throw new Http.Fail(404, "Joueur introuvable.");
        }
        return dir.resolve(userId + ".png");
    }

    void put(String userId, byte[] image) {
        if (image == null || image.length == 0 || image.length > MAX_INPUT) {
            throw new Http.Fail(413, "Image trop grande (600 Ko au plus).");
        }
        BufferedImage src = decode(image);
        // the middle square, scaled down (or up) to the size of the pictures
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        BufferedImage out = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        // pixel art (sprites) stays sharp when it is enlarged
        boolean small = side <= SIZE / 2;
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, small ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                : RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, SIZE, SIZE, x, y, x + side, y + side, null);
        g.dispose();
        try {
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(out, "png", png);
            Path f = file(userId);
            Path tmp = f.resolveSibling(userId + ".tmp");
            Files.write(tmp, png.toByteArray());
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.warn("Serveur : photo de profil non enregistrée : " + e.getMessage());
            throw new Http.Fail(500, "Photo non enregistrée.");
        }
    }

    /** Reads PNG, JPEG, GIF or BMP; the size is checked before the pixels are read. */
    private static BufferedImage decode(byte[] image) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(image))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) {
                throw new Http.Fail(400, "Format d'image non reconnu (PNG, JPEG, GIF ou BMP).");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0);
                int h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE) {
                    throw new Http.Fail(400, "Image trop grande (" + MAX_SIDE + " pixels de côté au plus).");
                }
                BufferedImage img = reader.read(0);
                if (img == null) {
                    throw new Http.Fail(400, "Image illisible.");
                }
                return img;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof Http.Fail f) {
                throw f;
            }
            throw new Http.Fail(400, "Image illisible.");
        }
    }

    byte[] get(String userId) {
        Path f = file(userId);
        try {
            return Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
        } catch (IOException e) {
            return null;
        }
    }

    void delete(String userId) {
        try {
            Files.deleteIfExists(file(userId));
        } catch (IOException e) {
            Log.warn("Serveur : photo de profil non supprimée : " + e.getMessage());
        }
    }
}
