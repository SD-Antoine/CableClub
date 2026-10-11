package slt.player;

import slt.player.track.NdsReader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Where the player stands in generation 5 (Black / White, Black 2 / White 2), read on
 * every image from the main RAM, so the other players of the room can see them in their
 * own game. The field is in 3D: the window places the others in the world with the
 * camera of the very image it shows.
 *
 * <pre>
 * NNS_G3dGlb (state of Nintendo's 3D library), found by its signature (command words 0x17101610
 * at +0, matrix mode 2 at +0x48, light vectors 0x32323232 at +0x7C): projection matrix (4x4 fx32)
 * at +0x08, camera matrix (4x3 fx32, row vectors) at +0x4C, camera target at +0x258. The image
 * shown is drawn with the matrices of the image before (the DS draws the 3D one image late).
 * Player: movement model with obj id 0xFF at +0x08 (array of 0x100-byte models), appearance
 * +0x0C, facing +0x18 (0 up, 1 down, 2 left, 3 right), tile x / y / z (u16) +0x3C, position
 * (VecFx32) +0x44. Its billboard: position as drawn (VecFx32), then u32 0x200000NN, NN = image
 * of the texture (walking set: up, down, left, right, 3 images each, then running).
 * On the field while the camera aims at the player (a battle aims at its own scene, with another
 * projection; a cutscene elsewhere; another drawing pass sometimes leaves a camera at the origin in
 * the 3D state for one image: the field camera read before is kept). Dialogue: a message box covers the top or the bottom of the
 * top screen (measured: 78-100 % white with, under 7 % without). Map: the save's (NdsReader).
 * Measured on White 2 FR: Aspertia, Pokémon Centre, battles, dialogues, cutscene.
 * </pre>
 */
final class Gen5Presence implements Presence {

    private static final long RAM = 0x02000000L;
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int FX = 4096;
    /** Searches of a lost structure in the whole 4 MB (a few milliseconds) are spaced out. */
    private static final long SEARCH_MS = 250;

    private final NdsReader reader;
    private final Gen5Sprites sprites;
    private final Gen5Sprites.Set set;
    private MemorySegment ram;
    private int glb = -1;
    private int player = -1;
    private int billboard = -1;
    private long nextSearch;
    private float[] view;
    private int offField;
    private float fieldProjection = Float.NaN;
    private int walkCode = -1;
    // last position on the field: kept while in battle or in a cutscene
    private int fieldMap = -1;
    private int fieldX;
    private int fieldY;
    private int fieldZ;
    private String spriteId;
    private String spritePng;
    private volatile long tradeUntil;

    Gen5Presence(NdsReader reader, Gen5Sprites sprites, Gen5Sprites.Set set) {
        this.reader = reader;
        this.sprites = sprites;
        this.set = set;
    }

    @Override
    public String spritePng() {
        return spritePng;
    }

    @Override
    public int spriteW() {
        return 32;
    }

    @Override
    public int spriteH() {
        return 32;
    }

    @Override
    public void tradeHappened() {
        tradeUntil = System.currentTimeMillis() + 8000;
    }

    @Override
    public boolean world3d() {
        return true;
    }

    @Override
    public Sample sample(CoreHost core) {
        if (ram == null) {
            ram = core.systemRam();
            if (ram == null || ram.byteSize() < 4 << 20) {
                ram = null;
                return null;
            }
        }
        long now = System.currentTimeMillis();
        if (!located(now)) {
            view = null;
            return held("busy");
        }
        int bx = i32(billboard);
        int by = i32(billboard + 4);
        int bz = i32(billboard + 8);
        int image = ram.get(ValueLayout.JAVA_BYTE, billboard + 12) & 0xFF;
        float[] shown = view;
        int map = map();
        if (map < 0) {
            return null;
        }
        // the field camera follows the player (interiors: a little in front of them). Another camera for an
        // image or two was left by another drawing pass in the 3D state; longer, a battle or a cutscene
        float[] next = viewProjection();
        int tx = i32(glb + 0x258);
        int tz = i32(glb + 0x260);
        boolean aimed = next != null && Math.abs(tx - bx) <= 8 * FX && Math.abs(tz - bz) <= 48 * FX;
        if (aimed) {
            view = next;
            fieldProjection = next[0];
            offField = 0;
        } else {
            offField++;
        }
        boolean field = view != null && (aimed || offField <= 2);
        boolean battle = !field && next != null && !Float.isNaN(fieldProjection) && Math.abs(next[0] - fieldProjection) > 0.05f;
        boolean dark = darkScreen(core);
        boolean dialogue = field && !dark && dialogueBox(core);
        boolean free = field && !dark && !dialogue;
        if (field && !dark) {
            readSprite(image);
            fieldMap = map;
            fieldX = bx >> 8;
            fieldY = by >> 8;
            fieldZ = bz >> 8;
        }
        String status;
        if (battle) {
            status = "battle";
        } else if (now < tradeUntil) {
            status = "trade";
        } else if (dialogue) {
            status = "dialogue";
        } else if (!free) {
            status = "busy";
        } else {
            status = "free";
        }
        if (fieldMap < 0) {
            return null;
        }
        return new Sample(fieldMap, fieldX, fieldY, field && !dark, free, status, spriteId, 0, 0, fieldZ, shown);
    }

    /** The last position on the field, while the structures are not found (loading, battle…). */
    private Sample held(String status) {
        return fieldMap < 0 ? null : new Sample(fieldMap, fieldX, fieldY, false, false, status, spriteId, 0, 0, fieldZ, null);
    }

    // ------------------------------------------------------------- structures

    /**
     * The 3D state, the player and its billboard: looked for next to where they were (the models and the
     * billboards are arrays: from one map to another the player moves within them), else in the whole RAM.
     */
    private boolean located(long now) {
        if (!isGlb(glb)) {
            glb = -1;
        }
        if (!isPlayer(player)) {
            int was = player;
            player = -1;
            for (int d = 0x100; was >= 0 && d <= 0x4000 && player < 0; d += 0x100) {
                player = isPlayer(was + d) ? was + d : isPlayer(was - d) ? was - d : -1;
            }
        }
        if (glb >= 0 && player >= 0) {
            int px = i32(player + 0x44);
            int pz = i32(player + 0x4C);
            if (isBillboard(billboard, px, pz)) {
                return true;
            }
            int was = billboard;
            billboard = -1;
            for (int d = 4; was >= 0 && d <= 0x1000 && billboard < 0; d += 4) {
                billboard = isBillboard(was + d, px, pz) ? was + d : isBillboard(was - d, px, pz) ? was - d : -1;
            }
            if (billboard >= 0) {
                return true;
            }
        }
        if (now < nextSearch) {
            return false;
        }
        nextSearch = now + SEARCH_MS;
        int size = (int) ram.byteSize() - 0x300;
        for (int o = 0; o < size && glb < 0; o += 4) {
            if (isGlb(o)) {
                glb = o;
            }
        }
        for (int o = 0; o < size && player < 0; o += 4) {
            if (isPlayer(o)) {
                player = o;
            }
        }
        if (glb < 0 || player < 0) {
            return false;
        }
        int px = i32(player + 0x44);
        int pz = i32(player + 0x4C);
        for (int o = 0; o < size && billboard < 0; o += 4) {
            if (i32(o) == px && i32(o + 8) == pz && isBillboard(o, px, pz)) {
                billboard = o;
            }
        }
        return billboard >= 0;
    }

    private boolean inside(int o) {
        return o >= 0 && o < ram.byteSize() - 0x300;
    }

    private boolean isGlb(int o) {
        return inside(o) && i32(o) == 0x17101610 && i32(o + 0x48) == 2 && i32(o + 0x7C) == 0x32323232;
    }

    private boolean isPlayer(int o) {
        if (!inside(o) || i32(o + 8) != 0xFF) {
            return false;
        }
        int gx = u16(o + 0x3C);
        int gz = u16(o + 0x40);
        int x = i32(o + 0x44);
        int z = i32(o + 0x4C);
        return (gx != 0 || gz != 0) && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1;
    }

    private boolean isBillboard(int o, int px, int pz) {
        return inside(o) && Math.abs(i32(o) - px) <= 4 * FX && Math.abs(i32(o + 8) - pz) <= 4 * FX
                && (i32(o + 12) & 0xFF000000) == 0x20000000;
    }

    /**
     * Camera x projection (row vectors, 16 values), then the depth of the player's feet (drawn one texel
     * per pixel), from the fixed-point matrices of the 3D library; null when they
     * are not the field camera's: a perspective, and a camera matrix placing the eye where the camera
     * position (+0x240) says (another drawing pass sometimes leaves its own camera, measured on White 2).
     */
    private float[] viewProjection() {
        double[] p = new double[16];
        for (int i = 0; i < 16; i++) {
            p[i] = i32(glb + 8 + 4 * i) / (double) FX;
        }
        if (Math.abs(p[11] + 1) > 0.01 || Math.abs(p[15]) > 0.01) {
            return null;
        }
        double[] c = new double[16];
        for (int r = 0; r < 4; r++) {
            for (int k = 0; k < 3; k++) {
                c[r * 4 + k] = i32(glb + 0x4C + 4 * (r * 3 + k)) / (double) FX;
            }
        }
        c[15] = 1;
        // eye = -T x transpose(R): component j is -(T . row j)
        for (int j = 0; j < 3; j++) {
            double eye = -(c[12] * c[j * 4] + c[13] * c[j * 4 + 1] + c[14] * c[j * 4 + 2]);
            if (Math.abs(eye - i32(glb + 0x240 + 4 * j) / (double) FX) > 4) {
                return null;
            }
        }
        float[] m = new float[17];
        for (int r = 0; r < 4; r++) {
            for (int k = 0; k < 4; k++) {
                double s = 0;
                for (int j = 0; j < 4; j++) {
                    s += c[r * 4 + j] * p[j * 4 + k];
                }
                m[r * 4 + k] = (float) s;
            }
        }
        // the player is drawn one texel per pixel at the depth of their feet (measured on White 2: Pokémon
        // Centre, town, Aspertia lookout, whatever the camera distance): the others are scaled from it
        double x = i32(billboard) / (double) FX;
        double y = i32(billboard + 4) / (double) FX;
        double z = i32(billboard + 8) / (double) FX;
        m[16] = (float) (x * m[3] + y * m[7] + z * m[11] + m[15]);
        return m;
    }

    private int map() {
        long a = reader.mapAddress();
        return a < RAM ? -1 : u16((int) (a - RAM));
    }

    // ------------------------------------------------------------- sprite

    /**
     * The image of the player's texture shown now. Another appearance than walking (bike, surf…):
     * the standing image of the walking set, facing the same way, until that set is known.
     */
    private void readSprite(int image) {
        long g = reader.genderAddress();
        int girl = g < RAM ? 0 : ram.get(ValueLayout.JAVA_BYTE, g - RAM) & 1;
        int file = girl == 1 ? set.girl() : set.boy();
        int code = u16(player + 0x0C);
        int count = sprites.frames(file);
        if (count == 0) {
            return;
        }
        if (image >= 16 && image < count) {
            walkCode = code; // running: only the walking set has that many images
        } else if (walkCode < 0) {
            walkCode = code;
        }
        int frame = image;
        if (code != walkCode || image >= count) {
            frame = (u16(player + 0x18) & 3) * 3;
        }
        String id = "g5-" + file + "-" + frame;
        if (!id.equals(spriteId)) {
            String png = sprites.png(file, frame);
            if (png != null) {
                spriteId = id;
                spritePng = png;
            }
        }
    }

    // ------------------------------------------------------------- screen

    /** A message box over the top or the bottom of the top screen. */
    private static boolean dialogueBox(CoreHost core) {
        int w = core.frameWidth();
        int[] px = core.pixels();
        if (w != 256 || core.frameHeight() < 192 || px.length < w * 192) {
            return false;
        }
        return whiteBand(px, w, 6, 44) || whiteBand(px, w, 148, 186);
    }

    private static boolean whiteBand(int[] px, int w, int from, int to) {
        int white = 0;
        int total = 0;
        for (int y = from; y < to; y += 2) {
            for (int x = 16; x < 240; x += 2) {
                int c = px[y * w + x];
                total++;
                if (((c >> 16) & 0xFF) >= 0xE8 && ((c >> 8) & 0xFF) >= 0xE8 && (c & 0xFF) >= 0xE8) {
                    white++;
                }
            }
        }
        return white * 2 > total;
    }

    /** The top screen faded to black (map change, start of a battle). */
    private static boolean darkScreen(CoreHost core) {
        int w = core.frameWidth();
        int[] px = core.pixels();
        if (w != 256 || core.frameHeight() < 192 || px.length < w * 192) {
            return true;
        }
        long sum = 0;
        int n = 0;
        for (int y = 4; y < 192; y += 8) {
            for (int x = 4; x < 256; x += 8) {
                int c = px[y * w + x];
                sum += ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF);
                n++;
            }
        }
        return sum / (3L * n) < 10;
    }

    private int i32(int o) {
        return ram.get(I32, o);
    }

    private int u16(int o) {
        return ram.get(U16, o) & 0xFFFF;
    }
}
