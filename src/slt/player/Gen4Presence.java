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
 * Map objects: an array of 0x128-byte structures (0x12C in HeartGold / SoulSilver), the player first:
 * flags +0x00 (bit 0 = in use, bit 15 = a character), local id 0xFF at +0x08, graphics id +0x10, facing +0x28 (0 up, 1 down, 2 left,
 * 3 right), tile x / y / z (int) +0x64, position (VecFx32, 16 units a tile, centre of the tile) +0x70.
 * The player's sprite is drawn standing on that position.
 * Sprites: NSBTX textures of the field characters (data/mmodel/mmodel.narc in Diamond / Pearl
 * and Platinum, a/0/8/1 in HeartGold / SoulSilver), "name.1" to "name.32": walking up, down,
 * left, right (standing, step, standing, other step), then running the same way. The image
 * shown is worked out from the facing, the movement and the speed (the drawn texture is not
 * found in the RAM): walking or running sets only, a bike shows the standing walking image.
 * Measured on Platinum FR (Twinleaf Town).
 *
 * Timing (measured on Platinum and HeartGold): the game works at 30 images a second, so the picture changes
 * every other image, and the camera and the positions read in the RAM change on the other image, or on the same
 * one, depending on the game. Whatever the order, the picture of an image goes with what the RAM holds on the
 * image where the picture changed: what is read between two such images is kept for the next one (a window
 * that placed the others with the very latest camera made them shake by a step, 30 times a second).
 * The sprite is drawn with its ground point 5 rows above the bottom of its 32 x 32 image, on the point the camera aims
 * at (the game draws the characters with the positions of its last move, which the RAM may already have left behind).
 * HeartGold / SoulSilver: the Pokémon of the first position of the party follows the player as the map object of
 * local id 253; its graphics id is turned into a texture of a/0/8/1 by the game's table (see Gen4Followers, found in the RAM), and
 * its image is worked out from its facing (two images a direction, the second standing, the two alternating at
 * each tile when it walks, starting with the first).
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
    private static final long SEARCH_MS = 250;
    /**
     * Rows of a 32 x 32 sprite under the ground point it stands on (the camera's target): measured on Platinum (outdoors
     * and in a house) and HeartGold (Pokémon Center). Its middle lies on that point indoors (orthographic projection),
     * one column right of it outdoors.
     */
    private static final float DROP = 5;
    /** Images a reading is kept at most when the picture does not change. */
    private static final int HOLD = 2;
    private static final int FOLLOWER_ID = 253;
    private static final int MAX_OTHERS = 32;

    private final NdsReader reader;
    private final Gen5Sprites sprites;
    private final Gen5Sprites.Set set;
    private final int stride;
    private final Gen4Followers followers;
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
    // what goes with the picture shown (see the timing above)
    private Snap snap;
    private int heldFrames;
    private final PictureWatch picture = new PictureWatch();
    // the Pokémon following
    private int petX;
    private int petZ;
    private int petGx;
    private int petGz;
    private int petFace = -1;
    private int petStep;
    private int petStill;
    private boolean petIdle = true;

    /** What the RAM held when the picture last changed. */
    private static final class Snap {
        final int map;
        final int x;
        final int y;
        final int z;
        final float[] view;
        final String spriteId;
        final String spritePng;
        final boolean exact;
        float[] others;
        Pet pet;

        Snap(int map, int x, int y, int z, float[] view, String spriteId, String spritePng, boolean exact) {
            this.map = map;
            this.x = x;
            this.y = y;
            this.z = z;
            this.view = view;
            this.spriteId = spriteId;
            this.spritePng = spritePng;
            this.exact = exact;
        }
    }

    Gen4Presence(NdsReader reader, Gen5Sprites sprites, Gen5Sprites.Set set, String game) {
        this.reader = reader;
        this.sprites = sprites;
        this.set = set;
        boolean hg = "heartgold".equals(game) || "soulsilver".equals(game);
        this.stride = hg ? 0x12C : 0x128;
        this.followers = hg ? new Gen4Followers() : null;
    }

    @Override
    public String spritePng() {
        return snap != null ? snap.spritePng : spritePng;
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
        return snap != null ? snap.exact : exact;
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
        boolean pictureChanged = picture.changed(core);
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
            if (snap == null || pictureChanged || ++heldFrames > HOLD || snap.map != map) {
                snap = new Snap(map, px >> 8, py >> 8, pz >> 8, view, spriteId, spritePng, exact);
                readObjects(snap);
                heldFrames = 0;
            }
            fieldMap = snap.map;
            fieldX = snap.x;
            fieldY = snap.y;
            fieldZ = snap.z;
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
        float[] shown = snap != null ? snap.view : view;
        return new Sample(fieldMap, fieldX, fieldY, field && !dark, free, status, snap != null ? snap.spriteId : spriteId, 0, 0,
                fieldZ, shown, box, snap != null ? snap.others : null, snap != null ? snap.pet : null);
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
            for (int d = stride; was >= 0 && d <= 64 * stride && player < 0; d += stride) {
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
        // 21 numbers: the matrix, the depth and the place of the feet, the rows of a sprite under its ground point,
        // and where the middle of the sprite lies across from that point (columns)
        float[] m = new float[21];
        for (int r = 0; r < 4; r++) {
            for (int k = 0; k < 4; k++) {
                double s = 0;
                for (int j = 0; j < 4; j++) {
                    s += c[r * 4 + j] * p[j * 4 + k];
                }
                m[r * 4 + k] = (float) s;
            }
        }
        // the player's sprite is drawn on the point the camera aims at (the game draws the characters with the positions
        // of its last move, a step behind what the RAM holds on some images: only the target stays on the sprite)
        double[] feet = project(m, i32(glb + 0x258) / (double) FX, i32(glb + 0x25C) / (double) FX, i32(glb + 0x260) / (double) FX);
        if (feet == null) {
            return null;
        }
        m[16] = (float) feet[2];
        m[17] = (float) feet[0];
        m[18] = (float) feet[1];
        m[19] = DROP;
        m[20] = orthographic ? 0 : 1;
        return m;
    }

    /** Where a point of the world lands on the top screen with a camera x projection: x, y, depth (clip w). */
    private static double[] project(float[] m, double x, double y, double z) {
        double w = x * m[3] + y * m[7] + z * m[11] + m[15];
        if (w <= 0.01) {
            return null;
        }
        return new double[]{((x * m[0] + y * m[4] + z * m[8] + m[12]) / w + 1) * 128,
                (1 - (x * m[1] + y * m[5] + z * m[9] + m[13]) / w) * 96, w};
    }

    private int map() {
        long a = reader.mapAddress();
        return a < RAM ? -1 : u16((int) (a - RAM));
    }

    // ------------------------------------------------------------- the other characters

    /**
     * The other characters of the map (the objects after the player's) that are on the screen, with the camera of
     * the snapshot, and the Pokémon following the player.
     */
    private void readObjects(Snap s) {
        float[] m = s.view;
        if (m == null) {
            return;
        }
        float[] buffer = new float[3 * MAX_OTHERS];
        int n = 0;
        Pet pet = null;
        long limit = ram.byteSize() - 0x300;
        for (int k = 1; k < 64; k++) {
            int o = player + k * stride;
            if (o + stride > limit) {
                break;
            }
            // in use (bit 0) and a character (bit 15: the people and the Pokémon have it, the mailboxes, rocks and signs do not)
            int flags = i32(o);
            if ((flags & 1) == 0 || (flags & 0x8000) == 0) {
                continue;
            }
            int gx = i32(o + 0x64);
            int gz = i32(o + 0x6C);
            int ox = i32(o + 0x70);
            int oy = i32(o + 0x74);
            int oz = i32(o + 0x78);
            if (gx <= 0 || gz <= 0 || gx >= 0x10000 || gz >= 0x10000 || Math.abs((ox >> 16) - gx) > 1 || Math.abs((oz >> 16) - gz) > 1) {
                continue;
            }
            if (followers != null && pet == null && i32(o + 8) == FOLLOWER_ID) {
                pet = readPet(o, ox, oy, oz, gx, gz);
            }
            double[] f = project(m, ox / (double) FX, oy / (double) FX, oz / (double) FX);
            if (f == null || f[0] < -24 || f[0] > 280 || f[1] < -8 || f[1] > 232 || n >= MAX_OTHERS) {
                continue;
            }
            buffer[3 * n] = (float) f[0];
            buffer[3 * n + 1] = (float) f[1];
            buffer[3 * n + 2] = (float) f[2];
            n++;
        }
        s.others = java.util.Arrays.copyOf(buffer, 3 * n);
        s.pet = pet;
    }

    /** The Pokémon following, with the image it shows (see the class comment). */
    private Pet readPet(int o, int ox, int oy, int oz, int gx, int gz) {
        int member = followers.member(ram, i32(o + 0x10));
        if (member < 0 || sprites.frames(member) < 8) {
            return null;
        }
        int face = i32(o + 0x28) & 3;
        boolean moving = petFace >= 0 && (ox != petX || oz != petZ);
        if (moving) {
            if (petIdle || face != petFace) {
                petStep = 0;
            } else if (gx != petGx || gz != petGz) {
                petStep ^= 1;
            }
            petIdle = false;
            petStill = 0;
        } else if (++petStill >= 2) {
            petIdle = true;
        }
        petX = ox;
        petZ = oz;
        petFace = face;
        petGx = gx;
        petGz = gz;
        int frame = face * 2 + (petIdle ? 1 : petStep);
        String png = sprites.png(member, frame);
        if (png == null) {
            return null;
        }
        int[] size = sprites.size(member, frame);
        return new Pet(ox >> 8, oy >> 8, oz >> 8, "g4p-" + member + "-" + frame, png, size[0], size[1]);
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
