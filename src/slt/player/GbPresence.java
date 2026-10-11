package slt.player;

import slt.player.track.GbReader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.util.Base64;
import java.util.zip.CRC32;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Where the player stands and what they are doing (generations 1 and 2).
 *
 * <pre>
 * Position: X and Y in steps of 16 pixels and the map, in the save data next to the party. The
 * background scrolls one pixel at a time during a step (SCX / SCY, 0xFF43 / 0xFF42): at rest,
 * steps * 16 - scroll is constant, which gives the pixel position during the step.
 * Player: four 8x8 objects centred on the screen (OAM 0xFE00); the colours are taken from the
 * image itself (they depend on the console model), the shape from the tiles (VRAM 0x8000, 2 bits
 * per pixel) or, for tiles in the second video bank (Crystal), from the colours of the object
 * palette the game keeps in work RAM.
 * Dialogue and menus, gen 1: their frame tiles (0x79-0x7E) in the copy of the screen tiles; the
 * message box spans the whole width from row 12. Gen 2 keeps old boxes in its tile copies (and
 * shows the field through the window): the image itself is read instead, a message box being a
 * light area at the bottom under a dark line across the screen, a menu a light area on the right
 * of a dark column.
 * Fade (gen 1): the background palette BGP differs from its usual value 0xE4.
 * </pre>
 */
final class GbPresence implements Presence {

    private static final long OAM = 0xFE00L;
    private static final long VRAM = 0x8000L;
    private static final long SCY = 0xFF42L;
    private static final long BGP = 0xFF47L;

    private final GbReader reader;
    private int lastPosX = Integer.MIN_VALUE;
    private int lastPosY;
    private int lastScrollX;
    private int lastScrollY;
    private int lastMap = -1;
    private int still;
    private int kx = Integer.MIN_VALUE;
    private int ky;
    private int fieldMap = -1;
    private int fieldX;
    private int fieldY;
    private String spriteId;
    private String spritePng;
    private int anchorX = 72;
    private int anchorY = 76;

    GbPresence(GbReader reader) {
        this.reader = reader;
    }

    @Override
    public String spritePng() {
        return spritePng;
    }

    @Override
    public int spriteW() {
        return 16;
    }

    @Override
    public int spriteH() {
        return 16;
    }

    /**
     * Work RAM by the addresses of the reader: 0xC000 + offset in the 8 banks laid end to end
     * (0xD000-0xDFFF = bank 1, whichever bank the game has switched in).
     */
    private static MemorySegment wram(CoreHost core, long addr, int len) {
        MemorySegment ram = core.systemRam();
        if (ram == null || addr < 0xC000L || addr - 0xC000L + len > ram.byteSize()) {
            return null;
        }
        return ram.asSlice(addr - 0xC000L, len);
    }

    @Override
    public Sample sample(CoreHost core) {
        long xa = reader.xAddress();
        long ma = reader.mapAddress();
        if (xa < 0 || ma < 0) {
            return null;
        }
        MemorySegment pos = wram(core, xa - 1, 2);
        MemorySegment mapSeg = wram(core, ma, 2);
        MemorySegment scroll = core.bus(SCY, 2);
        if (pos == null || mapSeg == null || scroll == null) {
            return null;
        }
        int posY = pos.get(JAVA_BYTE, 0) & 0xFF;
        int posX = pos.get(JAVA_BYTE, 1) & 0xFF;
        int map = reader.gen() == 1 ? mapSeg.get(JAVA_BYTE, 0) & 0xFF
                : (mapSeg.get(JAVA_BYTE, 0) & 0xFF) * 256 + (mapSeg.get(JAVA_BYTE, 1) & 0xFF);
        int scrollY = scroll.get(JAVA_BYTE, 0) & 0xFF;
        int scrollX = scroll.get(JAVA_BYTE, 1) & 0xFF;
        boolean battle = false;
        long ba = reader.battleAddress();
        if (ba >= 0) {
            MemorySegment b = wram(core, ba, 1);
            battle = b != null && b.get(JAVA_BYTE, 0) != 0;
        }
        boolean fading = false;
        if (reader.gen() == 1) {
            MemorySegment bgp = core.bus(BGP, 1);
            fading = bgp != null && (bgp.get(JAVA_BYTE, 0) & 0xFF) != 0xE4;
        }
        boolean frames;
        boolean textBox;
        if (reader.gen() == 1) {
            MemorySegment tiles = wram(core, reader.tilemapAddress(), 20 * 18);
            if (tiles == null) {
                return null;
            }
            byte[] screen = tiles.toArray(JAVA_BYTE);
            boolean any = false;
            for (byte b : screen) {
                int t = b & 0xFF;
                if (t >= 0x79 && t <= 0x7E) {
                    any = true;
                    break;
                }
            }
            frames = any;
            // the message box spans the whole width at the bottom (rows 12 to 17)
            textBox = (screen[12 * 20] & 0xFF) == 0x79 && (screen[12 * 20 + 19] & 0xFF) == 0x7B;
        } else {
            textBox = messageBox(core);
            frames = textBox || menuBox(core);
        }
        boolean player = !battle && findPlayer(core, false);
        boolean overworld = !battle && player;

        boolean moved = posX != lastPosX || posY != lastPosY;
        if (map != lastMap) {
            kx = Integer.MIN_VALUE;
            still = 0;
        }
        if (!moved && scrollX == lastScrollX && scrollY == lastScrollY) {
            still++;
        } else {
            still = 0;
        }
        // at rest for a few images (read on every image): one still image can happen in the middle of a step
        if (still >= 3 && overworld) {
            kx = posX * 16 - scrollX;
            ky = posY * 16 - scrollY;
        }
        lastPosX = posX;
        lastPosY = posY;
        lastScrollX = scrollX;
        lastScrollY = scrollY;
        lastMap = map;
        int fx = 0;
        int fy = 0;
        if (kx != Integer.MIN_VALUE) {
            fx = wrap(scrollX + kx - posX * 16);
            fy = wrap(scrollY + ky - posY * 16);
            // 16 is the first image of a step: the tile changed, the background did not move yet
            if (Math.abs(fx) > 16 || Math.abs(fy) > 16) {
                fx = 0;
                fy = 0;
            }
        }
        boolean free = overworld && !frames && !fading;
        if (free) {
            findPlayer(core, true);
        }
        if (overworld && !fading) {
            fieldMap = map;
            fieldX = posX * 16 + fx;
            fieldY = posY * 16 + fy;
        }
        String status;
        if (battle) {
            status = "battle";
        } else if (!overworld) {
            status = "menu";
        } else if (textBox) {
            status = "dialogue";
        } else if (frames || fading) {
            status = "busy";
        } else {
            status = "free";
        }
        if (fieldMap < 0) {
            return null;
        }
        return new Sample(fieldMap, fieldX, fieldY, overworld, free, status, spriteId, anchorX, anchorY);
    }

    private static boolean light(int c) {
        return ((c >> 16) & 0xFF) >= 0xD0 && ((c >> 8) & 0xFF) >= 0xD0 && (c & 0xFF) >= 0xD0;
    }

    private static boolean dark(int c) {
        return ((c >> 16) & 0xFF) + ((c >> 8) & 0xFF) + (c & 0xFF) < 200;
    }

    /** A message box on the image: a dark line across the screen around y 96, light below it. */
    private static boolean messageBox(CoreHost core) {
        int w = core.frameWidth();
        int[] px = core.pixels();
        if (w != 160 || core.frameHeight() != 144 || px.length < w * 144) {
            return false;
        }
        boolean line = false;
        for (int y = 92; y < 106 && !line; y++) {
            int n = 0;
            for (int x = 4; x < 156; x++) {
                if (dark(px[y * w + x])) {
                    n++;
                }
            }
            line = n >= 140;
        }
        if (!line) {
            return false;
        }
        int lit = 0;
        int total = 0;
        for (int y = 104; y < 140; y += 2) {
            for (int x = 8; x < 152; x += 2) {
                total++;
                if (light(px[y * w + x])) {
                    lit++;
                }
            }
        }
        return lit * 2 > total;
    }

    /** A menu on the right of the image: a dark column (its left frame) with a light area beside it. */
    private static boolean menuBox(CoreHost core) {
        int w = core.frameWidth();
        int[] px = core.pixels();
        if (w != 160 || core.frameHeight() != 144 || px.length < w * 144) {
            return false;
        }
        for (int x = 64; x < 112; x++) {
            int n = 0;
            for (int y = 4; y < 100; y++) {
                if (dark(px[y * w + x])) {
                    n++;
                }
            }
            if (n < 80) {
                continue;
            }
            int lit = 0;
            int total = 0;
            for (int y = 12; y < 92; y += 2) {
                for (int xx = x + 8; xx < 152; xx += 2) {
                    total++;
                    if (light(px[y * w + xx])) {
                        lit++;
                    }
                }
            }
            if (total > 0 && lit * 2 > total) {
                return true;
            }
        }
        return false;
    }

    /** Colours 1-3 of object palette {@code pal}, as the image shows them, or null when unknown. */
    private int[] objectColours(CoreHost core, int pal) {
        long a = reader.objectPalettesAddress();
        MemorySegment seg = a < 0 ? null : wram(core, a + pal * 8L, 8);
        if (seg == null) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 1; i <= 3; i++) {
            int v = (seg.get(JAVA_BYTE, 2 * i) & 0xFF) | ((seg.get(JAVA_BYTE, 2 * i + 1) & 0xFF) << 8);
            int r = v & 31;
            int g = (v >> 5) & 31;
            int b = (v >> 10) & 31;
            out[i - 1] = ((r << 3 | r >> 2) << 16) | ((g << 3 | g >> 2) << 8) | (b << 3 | b >> 2);
        }
        return out;
    }

    /** Whether a pixel has one of these colours (the emulator converts colours slightly differently). */
    private static boolean near(int c, int[] colours) {
        for (int k : colours) {
            if (Math.abs(((c >> 16) & 0xFF) - ((k >> 16) & 0xFF)) <= 8 && Math.abs(((c >> 8) & 0xFF) - ((k >> 8) & 0xFF)) <= 8
                    && Math.abs((c & 0xFF) - (k & 0xFF)) <= 8) {
                return true;
            }
        }
        return false;
    }

    private static int wrap(int v) {
        int m = Math.floorMod(v, 256);
        return m >= 128 ? m - 256 : m;
    }

    /**
     * The player: four 8x8 objects forming a 16x16 square centred on the screen (left at x 64,
     * bottom at y 76). With {@code capture}, the sprite image is taken. @return true if found
     */
    private boolean findPlayer(CoreHost core, boolean capture) {
        MemorySegment oamSeg = core.bus(OAM, 160);
        if (oamSeg == null) {
            return false;
        }
        byte[] oam = oamSeg.toArray(JAVA_BYTE);
        // the top-left object of a complete 2 x 2 block where the player stands (left 64, top 60)
        int[] slot = null;
        int left = 0;
        int top = 0;
        for (int i = 0; i < 40 && slot == null; i++) {
            int y = (oam[i * 4] & 0xFF) - 16;
            int x = (oam[i * 4 + 1] & 0xFF) - 8;
            if (Math.abs(x - 64) > 2 || Math.abs(y - 60) > 4) {
                continue;
            }
            int[] found = {i, -1, -1, -1};
            for (int j = 0; j < 40; j++) {
                int yj = (oam[j * 4] & 0xFF) - 16;
                int xj = (oam[j * 4 + 1] & 0xFF) - 8;
                if (xj == x + 8 && yj == y) {
                    found[1] = j;
                } else if (xj == x && yj == y + 8) {
                    found[2] = j;
                } else if (xj == x + 8 && yj == y + 8) {
                    found[3] = j;
                }
            }
            if (found[1] >= 0 && found[2] >= 0 && found[3] >= 0) {
                slot = found;
                left = x;
                top = y;
            }
        }
        if (slot == null) {
            return false;
        }
        anchorX = left + 8;
        anchorY = top + 16;
        if (!capture) {
            return true;
        }
        int w = core.frameWidth();
        int h = core.frameHeight();
        int[] px = core.pixels();
        if (w != 160 || h != 144 || px.length < w * h) {
            return true;
        }
        BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
        CRC32 crc = new CRC32();
        for (int k = 0; k < 4; k++) {
            int o = slot[k] * 4;
            int sy = (oam[o] & 0xFF) - 16;
            int sx = (oam[o + 1] & 0xFF) - 8;
            int tile = oam[o + 2] & 0xFF;
            int attr = oam[o + 3] & 0xFF;
            boolean xflip = (attr & 0x20) != 0;
            boolean yflip = (attr & 0x40) != 0;
            byte[] td = null;
            int[] colours = null;
            if ((attr & 0x08) == 0) {
                MemorySegment t = core.bus(VRAM + tile * 16L, 16);
                if (t == null) {
                    return true;
                }
                td = t.toArray(JAVA_BYTE);
            } else {
                colours = objectColours(core, attr & 7);
                if (colours == null) {
                    return true; // tiles out of reach and no palette: no picture
                }
            }
            for (int ty = 0; ty < 8; ty++) {
                for (int tx = 0; tx < 8; tx++) {
                    int x = sx + tx;
                    int y = sy + ty;
                    if (x < 0 || y < 0 || x >= w || y >= h) {
                        continue;
                    }
                    int c = px[y * w + x] | 0xFF000000;
                    if (td != null) {
                        int row = yflip ? 7 - ty : ty;
                        int lo = td[row * 2] & 0xFF;
                        int hi = td[row * 2 + 1] & 0xFF;
                        int bit = xflip ? tx : 7 - tx;
                        if ((((lo >> bit) & 1) | (((hi >> bit) & 1) << 1)) == 0) {
                            continue; // transparent
                        }
                    } else if (!near(c, colours)) {
                        continue; // not one of the sprite's colours: the floor behind it
                    }
                    img.setRGB(x - left, y - top, c);
                    crc.update(c);
                    crc.update(x - left);
                    crc.update(y - top);
                }
            }
        }
        String id = Long.toHexString(crc.getValue());
        if (id.equals(spriteId)) {
            return true;
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(256);
            ImageIO.write(img, "png", out);
            spritePng = Base64.getEncoder().encodeToString(out.toByteArray());
            spriteId = id;
        } catch (IOException e) {
            // keep the previous sprite
        }
        return true;
    }
}
