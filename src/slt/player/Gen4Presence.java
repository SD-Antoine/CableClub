package slt.player;

import slt.player.track.NdsReader;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

/**
 * Where the player stands in generation 4 (Diamond / Pearl, Platinum, HeartGold / SoulSilver),
 * read on every image from the main RAM, so the other players of the room can see them in
 * their own game. The field is in 3D, drawn with Nintendo's 3D library like generation 5: the
 * window places the others in the world with the camera of the very image it shows.
 *
 * <pre>
 * NNS_G3dGlb: same signature and fields as in generation 5 (see Gen5Presence): projection +0x08,
 * camera matrix +0x4C, camera position +0x240, target +0x258 (the player, on the field).
 * Map objects: an array of 0x128-byte structures, the player first: local id 0xFF at +0x08,
 * appearance +0x10, facing +0x28 (0 up, 1 down, 2 left, 3 right), tile x / y / z (int) +0x64,
 * position (VecFx32, 16 units a tile, centre of the tile) +0x70. The player's sprite is drawn
 * standing on that position.
 * Sprites: NSBTX textures of the field characters (data/mmodel/mmodel.narc in Diamond / Pearl
 * and Platinum, a/0/8/1 in HeartGold / SoulSilver), "name.1" to "name.32": walking up, down,
 * left, right (standing, step, standing, other step), then running the same way. The image
 * shown is worked out from the facing, the movement and the speed (the drawn texture is not
 * found in the RAM): walking or running sets only, a bike shows the standing walking image.
 * Measured on Platinum FR (Twinleaf Town).
 * </pre>
 */
final class Gen4Presence implements Presence {

    /** Archive and walking sets (boy, girl) of a game. */
    static Gen5Sprites.Set setFor(String game) {
        return switch (game) {
            case "diamond", "pearl", "platinum" -> new Gen5Sprites.Set("data/mmodel/mmodel.narc", 90, 91);
            case "heartgold", "soulsilver" -> new Gen5Sprites.Set("a/0/8/1", 69, 70);
            default -> null;
        };
    }

    private static final long RAM = 0x02000000L;
    private static final ValueLayout.OfInt I32 = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort U16 = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final int FX = 4096;
    private static final int TILE = 16 * FX;
    private static final int OBJECT = 0x128;
    private static final long SEARCH_MS = 250;

    private final NdsReader reader;
    private final Gen5Sprites sprites;
    private final Gen5Sprites.Set set;
    private MemorySegment ram;
    private int glb = -1;
    private int player = -1;
    private long nextSearch;
    private float[] view;
    private int offField;
    private float fieldProjection = Float.NaN;
    // last position on the field: kept while in battle or in a cutscene
    private int fieldMap = -1;
    private int fieldX;
    private int fieldY;
    private int fieldZ;
    private String spriteId;
    private String spritePng;
    private volatile long tradeUntil;
    private boolean exact;
    // the last positions, to work out the speed (walking, running, bike)
    private final int[] trailX = new int[4];
    private final int[] trailZ = new int[4];
    private int trail;

    Gen4Presence(NdsReader reader, Gen5Sprites sprites, Gen5Sprites.Set set) {
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
    public boolean spriteExact() {
        return exact;
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
        int px = i32(player + 0x70);
        int py = i32(player + 0x74);
        int pz = i32(player + 0x78);
        int map = map();
        if (map < 0) {
            return null;
        }
        float[] next = viewProjection(px, py, pz);
        int tx = i32(glb + 0x258);
        int tz = i32(glb + 0x260);
        boolean aimed = next != null && Math.abs(tx - px) <= 8 * FX && Math.abs(tz - pz) <= 48 * FX;
        if (aimed) {
            view = next;
            fieldProjection = next[0];
            offField = 0;
        } else {
            offField++;
        }
        // unlike generation 5, the image shown is drawn with the camera of this very image (measured indoors on
        // Platinum: the player stays still on the screen while the camera follows them)
        float[] shown = view;
        boolean field = view != null && (aimed || offField <= 2);
        boolean battle = !field && next != null && !Float.isNaN(fieldProjection) && Math.abs(next[0] - fieldProjection) > 0.05f;
        boolean dark = Gen5Presence.darkScreen(core);
        int box = field && !dark ? Gen5Presence.messageBox(core) : 0;
        boolean flash = box < 0;
        box = Math.max(box, 0);
        boolean dialogue = field && !dark && box > 0;
        boolean free = field && !dark && !flash;
        if (field && !dark) {
            readSprite(px, pz);
            fieldMap = map;
            fieldX = px >> 8;
            fieldY = py >> 8;
            fieldZ = pz >> 8;
        }
        String status;
        if (battle) {
            status = "battle";
        } else if (now < tradeUntil) {
            status = "trade";
        } else if (dialogue) {
            status = "dialogue";
        } else if (!free || flash) {
            status = "busy";
        } else {
            status = "free";
        }
        if (fieldMap < 0) {
            return null;
        }
        return new Sample(fieldMap, fieldX, fieldY, field && !dark, free, status, spriteId, 0, 0, fieldZ, shown, box);
    }

    private Sample held(String status) {
        return fieldMap < 0 ? null : new Sample(fieldMap, fieldX, fieldY, false, false, status, spriteId, 0, 0, fieldZ, null);
    }

    // ------------------------------------------------------------- structures

    private boolean located(long now) {
        if (!isGlb(glb)) {
            glb = -1;
        }
        if (!isPlayer(player)) {
            int was = player;
            player = -1;
            for (int d = OBJECT; was >= 0 && d <= 64 * OBJECT && player < 0; d += OBJECT) {
                player = isPlayer(was + d) ? was + d : isPlayer(was - d) ? was - d : -1;
            }
        }
        if (glb >= 0 && player >= 0) {
            return true;
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
        return glb >= 0 && player >= 0;
    }

    private boolean inside(int o) {
        return o >= 0 && o < ram.byteSize() - 0x300;
    }

    private boolean isGlb(int o) {
        return inside(o) && i32(o) == 0x17101610 && i32(o + 0x48) == 2 && i32(o + 0x7C) == 0x32323232;
    }

    /** The player's map object: id 0xFF, its tile and its position agree (at most one tile apart while walking). */
    private boolean isPlayer(int o) {
        if (!inside(o) || i32(o + 8) != 0xFF) {
            return false;
        }
        int gx = i32(o + 0x64);
        int gz = i32(o + 0x6C);
        int x = i32(o + 0x70);
        int z = i32(o + 0x78);
        return gx > 0 && gz > 0 && gx < 0x10000 && gz < 0x10000 && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1;
    }

    /**
     * As in generation 5: camera x projection, then the depth of the player's feet and where they are on the
     * image. Outdoors a perspective; indoors (Platinum) an orthographic projection (w = 1 everywhere: the others
     * are drawn at their size wherever they stand).
     */
    private float[] viewProjection(int px, int py, int pz) {
        double[] p = new double[16];
        for (int i = 0; i < 16; i++) {
            p[i] = i32(glb + 8 + 4 * i) / (double) FX;
        }
        boolean perspective = Math.abs(p[11] + 1) <= 0.01 && Math.abs(p[15]) <= 0.01;
        boolean orthographic = Math.abs(p[11]) <= 0.01 && Math.abs(p[15] - 1) <= 0.01;
        if (!perspective && !orthographic) {
            return null;
        }
        double[] c = new double[16];
        for (int r = 0; r < 4; r++) {
            for (int k = 0; k < 3; k++) {
                c[r * 4 + k] = i32(glb + 0x4C + 4 * (r * 3 + k)) / (double) FX;
            }
        }
        c[15] = 1;
        for (int j = 0; j < 3; j++) {
            double eye = -(c[12] * c[j * 4] + c[13] * c[j * 4 + 1] + c[14] * c[j * 4 + 2]);
            if (Math.abs(eye - i32(glb + 0x240 + 4 * j) / (double) FX) > 4) {
                return null;
            }
        }
        float[] m = new float[19];
        for (int r = 0; r < 4; r++) {
            for (int k = 0; k < 4; k++) {
                double s = 0;
                for (int j = 0; j < 4; j++) {
                    s += c[r * 4 + j] * p[j * 4 + k];
                }
                m[r * 4 + k] = (float) s;
            }
        }
        double x = px / (double) FX;
        double y = py / (double) FX;
        double z = pz / (double) FX;
        double w = x * m[3] + y * m[7] + z * m[11] + m[15];
        m[16] = (float) w;
        m[17] = (float) (((x * m[0] + y * m[4] + z * m[8] + m[12]) / w + 1) * 128);
        m[18] = (float) ((1 - (x * m[1] + y * m[5] + z * m[9] + m[13]) / w) * 96);
        return m;
    }

    private int map() {
        long a = reader.mapAddress();
        return a < RAM ? -1 : u16((int) (a - RAM));
    }

    // ------------------------------------------------------------- sprite

    /**
     * The image of the walking (or running) animation: facing, then standing at the centre of a tile
     * and over the second half of a step, a step image over its first half, the left and the right foot
     * one tile in two. Running: the same in the running images (moving twice as fast).
     */
    private void readSprite(int px, int pz) {
        long g = reader.genderAddress();
        int girl = g < RAM ? 0 : ram.get(ValueLayout.JAVA_BYTE, g - RAM) & 1;
        int file = girl == 1 ? set.girl() : set.boy();
        int count = sprites.frames(file);
        if (count < 16) {
            return;
        }
        // distance covered over the last 4 images: walking 4 units (16 images a tile, the game moving the
        // player 2 units every other image), running 8, faster = a bike
        int at = trail % 4;
        int speed = trail >= 4 ? Math.max(Math.abs(px - trailX[at]), Math.abs(pz - trailZ[at])) : 0;
        trailX[at] = px;
        trailZ[at] = pz;
        trail++;
        int dir = i32(player + 0x28) & 3;
        int gx = i32(player + 0x64);
        int gz = i32(player + 0x6C);
        // distance still to walk to the tile the player is heading for (0 = standing on its centre); the
        // game shows the step over the last quarter of the way, one foot then the other from a tile to the next
        // (measured on Platinum: tile x + z odd = the image after the second standing one)
        int left = Math.max(Math.abs(gx * TILE + TILE / 2 - px), Math.abs(gz * TILE + TILE / 2 - pz));
        int phase = 0;
        if (left > 0 && left <= TILE / 4) {
            phase = ((gx + gz) & 1) == 0 ? 1 : 3;
        }
        boolean running = speed > 6 * FX && speed <= 11 * FX;
        boolean fast = speed > 11 * FX; // bike: another set of images, the standing one stands in
        exact = !fast;
        int frame = fast ? dir * 4 : (running && count >= 32 ? 16 : 0) + dir * 4 + phase;
        String id = "g4-" + file + "-" + frame;
        if (!id.equals(spriteId)) {
            String png = sprites.png(file, frame);
            if (png != null) {
                spriteId = id;
                spritePng = png;
            }
        }
    }

    private int i32(int o) {
        return ram.get(I32, o);
    }

    private int u16(int o) {
        return ram.get(U16, o) & 0xFFFF;
    }
}
