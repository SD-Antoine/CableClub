package slt.player;

import slt.Json;
import slt.Log;
import slt.player.track.Gen3Reader;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.CRC32;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

/**
 * Where the player stands and what they are doing (generation 3), sampled a
 * few times per second, so the other players of the room can see them in
 * their own game: position to the pixel, map, state (battle, dialogue,
 * trade, menu) and the player's real sprite, read from the console's video
 * memory.
 *
 * <pre>
 * Position: SaveBlock1 +0 (x, y in tiles, s16) and +4 (map group, number). It changes at the start of
 * a step; the field background then scrolls one pixel per frame (BG1HOFS / BG1VOFS, kept by the
 * emulator in the I/O area): at rest, tiles * 16 - scroll is constant, which gives the pixel position
 * during the step.
 * Free: on the field screen, not in battle, field controls not locked (dialogue, menu, cutscene,
 * battle transition) and no fade of the screen running. The other players are only drawn then.
 * Sprite: the 16x32 (32x32 on a bike) object centred on the screen; 4bpp tiles in VRAM 0x06010000
 * (1D mapping), 16-colour palette at 0x05000200 + 32 * bank. Only read while free, so that nothing
 * else (battle animation, transition) is ever taken for the player.
 * </pre>
 */
final class Gen3Presence implements Presence {

    private static final long IO = 0x04000000L;
    private static final long OAM = 0x07000000L;
    private static final long PAL_OBJ = 0x05000200L;
    private static final long VRAM_OBJ = 0x06010000L;

    private final Gen3Reader reader;
    private final Path learnFile;
    private int lastPosX = Integer.MIN_VALUE;
    private int lastPosY;
    private int lastScrollX;
    private int lastScrollY;
    private int lastMap = -1;
    private int still;
    private int kx = Integer.MIN_VALUE;
    private int ky;
    private long owCb2 = -1;
    private long tradeCb2 = -1;
    private final Deque<long[]> cb2History = new ArrayDeque<>();
    private int samples;
    private boolean dialogue;
    private boolean lockTrusted = true;
    // last position on the field screen: kept while in battle or in a menu
    private int fieldMap = -1;
    private int fieldX;
    private int fieldY;
    private String spriteId;
    private String spritePng;
    private int spriteW = 16;
    private int spriteH = 32;
    private int anchorX = 120;
    private int anchorY = 88;
    private volatile long tradeUntil;

    Gen3Presence(Gen3Reader reader, Path saves) {
        this.reader = reader;
        this.learnFile = saves.resolve("presence.json");
        try {
            if (Files.isRegularFile(learnFile)) {
                Map<String, Object> learnt = Json.parseObject(Files.readString(learnFile));
                tradeCb2 = Json.num(learnt, "tradeCb2", -1);
                owCb2 = Json.num(learnt, "fieldCb2", -1);
            }
        } catch (IOException | RuntimeException e) {
            Log.warn("Présence : données apprises illisibles : " + e.getMessage());
        }
    }

    @Override
    public String spritePng() {
        return spritePng;
    }

    @Override
    public int spriteW() {
        return spriteW;
    }

    @Override
    public int spriteH() {
        return spriteH;
    }

    /** A Pokémon arrived by trade: the screen shown during the last minute is the trade screen. */
    @Override
    public void tradeHappened() {
        tradeUntil = System.currentTimeMillis() + 8000;
        long now = System.currentTimeMillis();
        Map<Long, Integer> seen = new HashMap<>();
        synchronized (cb2History) {
            for (long[] h : cb2History) {
                if (now - h[0] < 60_000 && h[1] != owCb2 && h[2] == 0) {
                    seen.merge(h[1], 1, Integer::sum);
                }
            }
        }
        long best = -1;
        int count = 0;
        for (Map.Entry<Long, Integer> e : seen.entrySet()) {
            if (e.getValue() > count) {
                best = e.getKey();
                count = e.getValue();
            }
        }
        if (best >= 0 && count >= 3) {
            tradeCb2 = best;
            learn();
        }
    }

    /** What was learnt about this game (same ROM for the whole room) is kept for the next sessions. */
    private void learn() {
        try {
            Files.writeString(learnFile, Json.stringify(Json.map("tradeCb2", tradeCb2, "fieldCb2", owCb2)));
        } catch (IOException e) {
            Log.warn("Présence : apprentissage non enregistré : " + e.getMessage());
        }
    }

    @Override
    public Sample sample(CoreHost core) {
        long sb1 = reader.saveBlock1();
        long main = reader.gMain();
        if (sb1 < 0 || main < 0) {
            return null;
        }
        MemorySegment pos = core.bus(sb1, 6);
        MemorySegment io = core.bus(IO + 0x14, 4);
        MemorySegment gm = core.bus(main, 0x440);
        if (pos == null || io == null || gm == null) {
            return null;
        }
        byte[] p = pos.toArray(JAVA_BYTE);
        byte[] s = io.toArray(JAVA_BYTE);
        byte[] g = gm.toArray(JAVA_BYTE);
        int posX = (short) ((p[0] & 0xFF) | (p[1] << 8));
        int posY = (short) ((p[2] & 0xFF) | (p[3] << 8));
        int map = (p[4] & 0xFF) * 256 + (p[5] & 0xFF);
        int scrollX = ((s[0] & 0xFF) | ((s[1] & 0xFF) << 8)) & 0x1FF;
        int scrollY = ((s[2] & 0xFF) | ((s[3] & 0xFF) << 8)) & 0x1FF;
        long cb2 = u32(g, 4);
        boolean battle = ((g[reader.battleOffset()] >> 1) & 1) != 0;
        int lock = readLock(core);
        boolean fading = fading(core);

        boolean moved = posX != lastPosX || posY != lastPosY;
        if (map != lastMap) {
            kx = Integer.MIN_VALUE; // new map: the background was redrawn, calibrate again
            still = 0;
        }
        if (!moved && scrollX == lastScrollX && scrollY == lastScrollY) {
            still++;
        } else {
            still = 0;
        }
        if (still >= 1 && !battle) {
            kx = posX * 16 - scrollX;
            ky = posY * 16 - scrollY;
        }
        if (moved && !battle && lastPosX != Integer.MIN_VALUE && map == lastMap && cb2 != owCb2) {
            owCb2 = cb2; // the player walks: this is the field screen
            learn();
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
            if (Math.abs(fx) >= 16 || Math.abs(fy) >= 16) {
                fx = 0;
                fy = 0;
            }
        }
        // controls free and nothing moving on the screen: the centred sprite can only be the player
        boolean unlocked = lock == 0 && !fading && !battle;
        if (owCb2 < 0 && unlocked && readSprite(core) && anchorX == 120 && anchorY == 88) {
            owCb2 = cb2;
            learn();
        }
        boolean overworld = !battle && owCb2 >= 0 && cb2 == owCb2;
        synchronized (cb2History) {
            cb2History.addLast(new long[]{System.currentTimeMillis(), cb2, battle ? 1 : 0});
            while (cb2History.size() > 600) {
                cb2History.pollFirst();
            }
        }
        if (overworld && samples % 2 == 0) {
            dialogue = !fading && dialogueBox(core);
        } else if (!overworld) {
            dialogue = false;
        }
        boolean locked = lock == 1 || fading;
        boolean free = overworld && !locked && !dialogue;
        if (free) {
            readSprite(core);
        }
        if (overworld && !fading) {
            fieldMap = map;
            fieldX = posX * 16 + fx;
            fieldY = posY * 16 + fy;
        }
        samples++;
        String status;
        if (battle) {
            status = "battle";
        } else if ((tradeCb2 >= 0 && cb2 == tradeCb2) || System.currentTimeMillis() < tradeUntil) {
            status = "trade";
        } else if (!overworld) {
            status = "menu";
        } else if (dialogue) {
            status = "dialogue";
        } else if (locked) {
            status = "busy";
        } else {
            status = "free";
        }
        if (fieldMap < 0) {
            return null; // never seen on the field yet: nothing to show to the others
        }
        // in battle or in a menu, the others keep seeing the player where they left the field
        return new Sample(fieldMap, fieldX, fieldY, overworld, free, status, spriteId, anchorX, anchorY);
    }

    /** 1 = field controls locked, 0 = free, -1 = unknown (the byte did not look like a flag). */
    private int readLock(CoreHost core) {
        if (!lockTrusted) {
            return -1;
        }
        MemorySegment seg = core.bus(reader.fieldLockAddress(), 1);
        if (seg == null) {
            return -1;
        }
        int v = seg.get(JAVA_BYTE, 0) & 0xFF;
        if (v > 1) {
            lockTrusted = false;
            Log.warn("Présence : indicateur « commandes bloquées » inattendu (" + v + "), ignoré pour cette partie.");
            return -1;
        }
        return v;
    }

    private boolean fading(CoreHost core) {
        MemorySegment seg = core.bus(reader.paletteFadeAddress() + 7, 1);
        return seg != null && (seg.get(JAVA_BYTE, 0) & 0x80) != 0;
    }

    private static int wrap(int v) {
        int m = Math.floorMod(v, 256);
        return m >= 128 ? m - 256 : m;
    }

    private static long u32(byte[] d, int o) {
        return ((d[o] & 0xFFL) | ((d[o + 1] & 0xFFL) << 8) | ((d[o + 2] & 0xFFL) << 16) | ((d[o + 3] & 0xFFL) << 24));
    }

    /** The message box: a white band at the bottom of the screen (measured: 70-99 % white with, under 16 % without). */
    private static boolean dialogueBox(CoreHost core) {
        int w = core.frameWidth();
        int h = core.frameHeight();
        int[] px = core.pixels();
        if (w != 240 || h != 160 || px.length < w * h) {
            return false;
        }
        int white = 0;
        int total = 0;
        for (int y = 122; y < 152; y += 2) {
            for (int x = 16; x < 224; x += 2) {
                int c = px[y * w + x];
                total++;
                if (((c >> 16) & 0xFF) >= 0xF0 && ((c >> 8) & 0xFF) >= 0xF0 && (c & 0xFF) >= 0xF0) {
                    white++;
                }
            }
        }
        return white * 2 > total;
    }

    /** The player's object: the 16x32 (or 32x32) sprite centred on the screen. @return true if found */
    private boolean readSprite(CoreHost core) {
        MemorySegment oamSeg = core.bus(OAM, 1024);
        if (oamSeg == null) {
            return false;
        }
        byte[] oam = oamSeg.toArray(JAVA_BYTE);
        int best = -1;
        int bestDist = 3;
        int bw = 0;
        int bh = 0;
        int bx = 0;
        int by = 0;
        for (int i = 0; i < 128; i++) {
            int a0 = (oam[i * 8] & 0xFF) | ((oam[i * 8 + 1] & 0xFF) << 8);
            int a1 = (oam[i * 8 + 2] & 0xFF) | ((oam[i * 8 + 3] & 0xFF) << 8);
            boolean affine = ((a0 >> 8) & 1) != 0;
            if (affine || ((a0 >> 9) & 1) != 0) {
                continue; // hidden, or stretched / rotated: not the walking player
            }
            if (((a0 >> 13) & 1) != 0) {
                continue; // 256-colour sprite: not the player
            }
            int shape = a0 >> 14;
            int size = a1 >> 14;
            int w;
            int h;
            if (shape == 2 && size == 2) {
                w = 16;
                h = 32;
            } else if (shape == 0 && size == 2) {
                w = 32;
                h = 32;
            } else {
                continue;
            }
            int y = a0 & 0xFF;
            int x = a1 & 0x1FF;
            if (x >= 256) {
                x -= 512;
            }
            if (y >= 160) {
                y -= 256;
            }
            int dist = Math.abs(x + w / 2 - 120) + Math.abs(y + h - 88);
            if (dist < bestDist) {
                best = i;
                bestDist = dist;
                bw = w;
                bh = h;
                bx = x;
                by = y;
            }
        }
        if (best < 0) {
            return false;
        }
        int a1 = (oam[best * 8 + 2] & 0xFF) | ((oam[best * 8 + 3] & 0xFF) << 8);
        int a2 = (oam[best * 8 + 4] & 0xFF) | ((oam[best * 8 + 5] & 0xFF) << 8);
        boolean hflip = ((a1 >> 12) & 1) != 0;
        int tile = a2 & 0x3FF;
        int bank = a2 >> 12;
        MemorySegment tiles = core.bus(VRAM_OBJ + tile * 32L, bw * bh / 2);
        MemorySegment pal = core.bus(PAL_OBJ + bank * 32L, 32);
        if (tiles == null || pal == null) {
            return false;
        }
        byte[] t = tiles.toArray(JAVA_BYTE);
        byte[] c = pal.toArray(JAVA_BYTE);
        int opaque = 0;
        for (byte b : t) {
            if ((b & 0x0F) != 0) {
                opaque++;
            }
            if ((b & 0xF0) != 0) {
                opaque++;
            }
        }
        if (opaque < bw * bh / 8) {
            return false; // almost empty: a placeholder object, not the player
        }
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(c);
        crc.update(hflip ? 1 : 0);
        String id = Long.toHexString(crc.getValue());
        anchorX = bx + bw / 2;
        anchorY = by + bh;
        if (id.equals(spriteId)) {
            return true;
        }
        BufferedImage img = new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB);
        int tilesPerRow = bw / 8;
        for (int ty = 0; ty < bh / 8; ty++) {
            for (int tx = 0; tx < tilesPerRow; tx++) {
                int base = (ty * tilesPerRow + tx) * 32;
                for (int py = 0; py < 8; py++) {
                    for (int pxl = 0; pxl < 8; pxl++) {
                        int b = t[base + py * 4 + pxl / 2] & 0xFF;
                        int idx = (pxl & 1) != 0 ? b >> 4 : b & 15;
                        if (idx == 0) {
                            continue;
                        }
                        int col = (c[idx * 2] & 0xFF) | ((c[idx * 2 + 1] & 0xFF) << 8);
                        int r = (col & 31) << 3;
                        int gg = ((col >> 5) & 31) << 3;
                        int bb = ((col >> 10) & 31) << 3;
                        int x = tx * 8 + pxl;
                        img.setRGB(hflip ? bw - 1 - x : x, ty * 8 + py, 0xFF000000 | (r << 16) | (gg << 8) | bb);
                    }
                }
            }
        }
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream(512);
            ImageIO.write(img, "png", out);
            spritePng = Base64.getEncoder().encodeToString(out.toByteArray());
            spriteId = id;
            spriteW = bw;
            spriteH = bh;
        } catch (IOException e) {
            // keep the previous sprite
        }
        return true;
    }
}
