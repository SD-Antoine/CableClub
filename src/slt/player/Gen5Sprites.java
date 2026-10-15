package slt.player;

import slt.Images;
import slt.Log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The player's real sprites in generations 4 and 5, read from the game's own ROM (the DS
 * emulator gives no access to the video memory). The field characters are NSBTX
 * textures, one 32x32 image per animation frame, gathered in a NARC archive of the
 * cartridge's file system:
 *
 * <pre>
 * Black 2 / White 2: a/0/4/8, Nate 209 (walk + run: 24 images), Rosa 218
 * Black / White:     a/0/4/9, Hilbert 6 (32 images), Hilda 9
 * Images of a walking set: up, down, left, right, 3 each (standing, step, other step), then running.
 * Generation 4 (see Gen4Presence): textures named "name.1" to "name.N", stored in the order of their
 * names ("1", "10", "11"… "2"): put back in the order of their numbers ({@code byNumber}).
 * </pre>
 *
 * Only the archive is read from the ROM file (a few megabytes), when first needed.
 */
final class Gen5Sprites {

    /** Archive and walking sets (boy, girl) of a game. */
    record Set(String narc, int boy, int girl) {
    }

    static Set forGame(String game) {
        return switch (game) {
            case "black2", "white2" -> new Set("a/0/4/8", 209, 218);
            case "black", "white" -> new Set("a/0/4/9", 6, 9);
            default -> null;
        };
    }

    private final Path rom;
    private final String narc;
    private final boolean byNumber;
    private List<byte[]> members;
    private boolean failed;
    private final Map<String, String> cache = new HashMap<>();
    private final Map<Integer, Integer> counts = new HashMap<>();
    private final Map<String, int[]> sizes = new HashMap<>();

    Gen5Sprites(Path rom, String narc) {
        this(rom, narc, false);
    }

    Gen5Sprites(Path rom, String narc, boolean byNumber) {
        this.rom = rom;
        this.narc = narc;
        this.byNumber = byNumber;
    }

    /** Number of images of texture file {@code file}, 0 if unknown. */
    int frames(int file) {
        return counts.computeIfAbsent(file, f -> {
            byte[] btx = member(f);
            return btx == null ? 0 : textures(btx, byNumber).size();
        });
    }

    /** Image {@code frame} of texture file {@code file} as a base64 PNG, null if absent. */
    String png(int file, int frame) {
        String key = file + ":" + frame;
        if (cache.containsKey(key)) {
            return cache.get(key);
        }
        String png = null;
        byte[] btx = member(file);
        if (btx != null) {
            List<Texture> t = textures(btx, byNumber);
            if (frame >= 0 && frame < t.size()) {
                png = Images.pngBase64(t.get(frame).argb, t.get(frame).w, t.get(frame).h);
            }
        }
        cache.put(key, png);
        return png;
    }

    /** Width and height of image {@code frame} of texture file {@code file} (32 x 32 when unknown). */
    int[] size(int file, int frame) {
        return sizes.computeIfAbsent(file + ":" + frame, k -> {
            byte[] btx = member(file);
            if (btx != null) {
                List<Texture> t = textures(btx, byNumber);
                if (frame >= 0 && frame < t.size()) {
                    return new int[]{t.get(frame).w, t.get(frame).h};
                }
            }
            return new int[]{32, 32};
        });
    }

    private byte[] member(int i) {
        if (members == null && !failed) {
            try {
                members = readNarc();
            } catch (IOException | RuntimeException e) {
                failed = true;
                Log.warn("Présence : sprites introuvables dans la ROM (" + narc + ") : " + e.getMessage());
            }
        }
        return members == null || i < 0 || i >= members.size() ? null : members.get(i);
    }

    // ------------------------------------------------------------- NDS file system

    private List<byte[]> readNarc() throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(rom.toFile(), "r")) {
            ByteBuffer head = read(f, 0, 0x60);
            int fntOff = head.getInt(0x40);
            int fntLen = head.getInt(0x44);
            int fatOff = head.getInt(0x48);
            ByteBuffer fnt = read(f, fntOff, fntLen);
            int id = find(fnt, 0xF000, narc.split("/"), 0);
            if (id < 0) {
                throw new IOException("fichier absent");
            }
            ByteBuffer fat = read(f, fatOff + 8L * id, 8);
            int start = fat.getInt(0);
            int end = fat.getInt(4);
            ByteBuffer d = read(f, start, end - start);
            if (d.getInt(0) != 0x4352414E) { // "NARC"
                throw new IOException("pas une archive NARC");
            }
            int p = 0x10;
            int btafSize = d.getInt(p + 4);
            int n = d.getShort(p + 8) & 0xFFFF;
            int[][] entries = new int[n][2];
            for (int i = 0; i < n; i++) {
                entries[i][0] = d.getInt(p + 12 + 8 * i);
                entries[i][1] = d.getInt(p + 16 + 8 * i);
            }
            p += btafSize;
            p += d.getInt(p + 4); // BTNF
            int base = p + 8;     // GMIF data
            List<byte[]> out = new ArrayList<>(n);
            for (int[] e : entries) {
                byte[] b = new byte[e[1] - e[0]];
                copy(d, base + e[0], b);
                out.add(b);
            }
            return out;
        }
    }

    private static ByteBuffer read(RandomAccessFile f, long at, int len) throws IOException {
        byte[] b = new byte[len];
        f.seek(at);
        f.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * File id of a path in the file name table (directories walked from {@code dir}), -1 if absent. A path
     * of only digits ("a/0/8/1") also matches those numbered names.
     */
    private static int find(ByteBuffer fnt, int dir, String[] path, int depth) {
        int entry = (dir & 0xFFF) * 8;
        int p = fnt.getInt(entry);
        int fileId = fnt.getShort(entry + 4) & 0xFFFF;
        while (true) {
            int t = fnt.get(p++) & 0xFF;
            if (t == 0) {
                return -1;
            }
            int len = t & 0x7F;
            byte[] name = new byte[len];
            copy(fnt, p, name);
            p += len;
            boolean match = new String(name, StandardCharsets.ISO_8859_1).equals(path[depth]);
            if ((t & 0x80) != 0) {
                int sub = fnt.getShort(p) & 0xFFFF;
                p += 2;
                if (match && depth + 1 < path.length) {
                    return find(fnt, sub, path, depth + 1);
                }
            } else {
                if (match && depth + 1 == path.length) {
                    return fileId;
                }
                fileId++;
            }
        }
    }

    // ------------------------------------------------------------- NSBTX textures

    /** A texture drawn as ARGB pixels. */
    private record Texture(int[] argb, int w, int h, String name) {
    }

    /** Every texture of an NSBTX file, drawn with its first palette (in the order of their numbers if asked). */
    private static List<Texture> textures(byte[] btx, boolean byNumber) {
        ByteBuffer b = ByteBuffer.wrap(btx).order(ByteOrder.LITTLE_ENDIAN);
        List<Texture> out = new ArrayList<>();
        if (b.getInt(0) != 0x30585442) { // "BTX0"
            return out;
        }
        int tex0 = b.getInt(0x10);
        ByteBuffer d = ByteBuffer.wrap(btx, tex0, btx.length - tex0).slice().order(ByteOrder.LITTLE_ENDIAN);
        int texInfo = d.getShort(0x0E) & 0xFFFF;
        int texData = d.getInt(0x14);
        int palInfo = d.getInt(0x34);
        int palData = d.getInt(0x38);
        List<byte[]> pals = dictionary(d, palInfo);
        if (pals.isEmpty()) {
            return out;
        }
        int palette = palData + ((ByteBuffer.wrap(pals.get(0)).order(ByteOrder.LITTLE_ENDIAN).getShort(0) & 0xFFFF) << 3);
        List<byte[]> entries = dictionary(d, texInfo);
        List<String> names = names(d, texInfo);
        for (int i = 0; i < entries.size(); i++) {
            ByteBuffer eb = ByteBuffer.wrap(entries.get(i)).order(ByteOrder.LITTLE_ENDIAN);
            int offset = texData + ((eb.getShort(0) & 0xFFFF) << 3);
            int params = eb.getShort(2) & 0xFFFF;
            int w = 8 << ((params >> 4) & 7);
            int h = 8 << ((params >> 7) & 7);
            int format = (params >> 10) & 7;
            boolean color0 = ((params >> 13) & 1) != 0;
            out.add(new Texture(draw(d, offset, w, h, format, color0, palette), w, h, i < names.size() ? names.get(i) : ""));
        }
        if (byNumber && out.stream().allMatch(t -> number(t.name()) >= 0)) {
            out.sort(java.util.Comparator.comparingInt(t -> number(t.name())));
        }
        return out;
    }

    /** The number after the last dot of a texture name ("hero.12" = 12), -1 if none. */
    private static int number(String name) {
        int dot = name.lastIndexOf('.');
        try {
            return dot < 0 ? -1 : Integer.parseInt(name.substring(dot + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Names of the entries of an NNS dictionary (16 bytes each, after the entries). */
    private static List<String> names(ByteBuffer d, int p) {
        int count = d.get(p + 1) & 0xFF;
        int q = p + 12 + 4 * count;
        int size = d.getShort(q) & 0xFFFF;
        q += 4 + size * count;
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] n = new byte[16];
            copy(d, q + 16 * i, n);
            int len = 0;
            while (len < 16 && n[len] != 0) {
                len++;
            }
            out.add(new String(n, 0, len, StandardCharsets.ISO_8859_1));
        }
        return out;
    }

    /** Entries of an NNS dictionary (header, tree, entries, names). */
    private static List<byte[]> dictionary(ByteBuffer d, int p) {
        int count = d.get(p + 1) & 0xFF;
        int q = p + 12 + 4 * count;
        int size = d.getShort(q) & 0xFFFF;
        q += 4;
        List<byte[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            byte[] e = new byte[size];
            copy(d, q + i * size, e);
            out.add(e);
        }
        return out;
    }

    /** The bytes at an offset of a buffer (ByteBuffer.get(int, byte[]) is missing before Android 15). */
    private static void copy(ByteBuffer from, int at, byte[] to) {
        ByteBuffer d = from.duplicate();
        d.position(at);
        d.get(to);
    }

    private static int[] draw(ByteBuffer d, int o, int w, int h, int format, boolean color0, int palette) {
        int[] img = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int n = y * w + x;
                int index;
                int alpha = 255;
                switch (format) {
                    case 1 -> { // A3I5
                        int v = d.get(o + n) & 0xFF;
                        index = v & 31;
                        alpha = (v >> 5) * 255 / 7;
                    }
                    case 2 -> index = ((d.get(o + n / 4) & 0xFF) >> (2 * (n & 3))) & 3;
                    case 3 -> {
                        int v = d.get(o + n / 2) & 0xFF;
                        index = (n & 1) != 0 ? v >> 4 : v & 15;
                    }
                    case 4 -> index = d.get(o + n) & 0xFF;
                    case 6 -> { // A5I3
                        int v = d.get(o + n) & 0xFF;
                        index = v & 7;
                        alpha = (v >> 3) * 255 / 31;
                    }
                    default -> {
                        continue; // compressed or direct colour: not used by the field characters
                    }
                }
                if (alpha == 0 || (index == 0 && color0 && format >= 2 && format <= 4)) {
                    continue;
                }
                int c = d.getShort(palette + 2 * index) & 0xFFFF;
                int r = (c & 31) << 3;
                int g = ((c >> 5) & 31) << 3;
                int bl = ((c >> 10) & 31) << 3;
                img[y * w + x] = (alpha << 24) | (r << 16) | (g << 8) | bl;
            }
        }
        return img;
    }
}
