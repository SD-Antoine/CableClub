package slt.player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;


/**
 * Development tool: drives a game headless from a text script (inputs,
 * save states, screenshots, memory dumps). Used to reach real game states
 * and to verify the automatic tracker.
 *
 * <pre>
 * load etat.state          save etat.state
 * wait 60                  press A 6 20      (buttons, frames held, frames after)
 * hold RIGHT 32            mash A 10 24      (button, count, interval)
 * touch 128 300 6          shot titre        dump ram
 * status                   (prints what the tracker found)
 * bag / additem ID QTY / tobox UID dead|alive   (the bag read; an item given back; a party Pokémon to the PC)
 * poke 20244e9 02          copy 2024744 2024550 100     peek 2024550 16   (tests)
 * sram 75ea 16            (cartridge save memory)
 * presence on             presence          (players drawn in game: last reading)
 * trace LEFT 40            (holds a button, compares the position read with the scrolling of the image)
 * trace3d DOWN 60 t/d      (3D games: where the position lands with the camera sent, + screenshots)
 * jitter3d DOWN 60         (3D games: on which images the picture, the camera and the position change, and how the camera
 *                           read matches the way the ground moved; where the player's sprite is drawn against where it is placed)
 * dumpframe NAME           (3D games: NAME.json with the camera, the other characters and the sprites, NAME.png: for a test page)
 * objects4 / objects5 / annotate NAME STRIDE / annotate5 NAME   (map objects of generation 4 and 5, boxes on a screenshot)
 * followtrace RIGHT 50 a/0/8/1 816 / findfollower a/0/8/1 297 862   (HeartGold: the Pokémon following and its texture)
 * followtable             (HeartGold / SoulSilver: the table of the followers in the RAM: entries that differ from the usual ones)
 * btxsheet NARC 1,2,3 NAME [zoom] / btxcount NARC FIRST LAST / words ADDR COUNT   (textures of a NARC of the ROM, memory)
 * </pre>
 */
final class ScriptRunner {

    /** Input source fully controlled by the script. */
    static final class ScriptInput implements CoreHost.InputSource {
        volatile int mask;
        volatile int px;
        volatile int py;
        volatile boolean down;

        @Override
        public void poll() {
            // nothing to poll: the script sets the state
        }

        @Override
        public int buttons() {
            return mask;
        }

        @Override
        public int pointerX() {
            return px;
        }

        @Override
        public int pointerY() {
            return py;
        }

        @Override
        public boolean pointerPressed() {
            return down;
        }
    }

    private final CoreHost core;
    private final ScriptInput input;
    private final Path out;
    private final Session.Tracker tracker;
    private final Path rom;
    private long frame;
    private Presence presence;
    private Presence.Sample sample;

    ScriptRunner(CoreHost core, ScriptInput input, Path out, Session.Tracker tracker, Path rom) {
        this.core = core;
        this.input = input;
        this.out = out;
        this.tracker = tracker;
        this.rom = rom;
    }

    void run(Path script) throws IOException {
        Files.createDirectories(out);
        List<String> lines = Files.readAllLines(script, StandardCharsets.UTF_8);
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            switch (t[0].toLowerCase(Locale.ROOT)) {
                case "load":
                    if (!core.loadState(Files.readAllBytes(out.resolve(t[1])))) {
                        throw new IOException("État illisible : " + t[1]);
                    }
                    break;
                case "save":
                    Files.write(out.resolve(t[1]), core.saveState());
                    break;
                case "wait":
                case "run":
                    frames(Integer.parseInt(t[1]));
                    break;
                case "press":
                    input.mask = mask(t[1]);
                    frames(t.length > 2 ? Integer.parseInt(t[2]) : 6);
                    input.mask = 0;
                    frames(t.length > 3 ? Integer.parseInt(t[3]) : 12);
                    break;
                case "hold":
                    input.mask = mask(t[1]);
                    frames(Integer.parseInt(t[2]));
                    input.mask = 0;
                    break;
                case "mash": {
                    int count = Integer.parseInt(t[2]);
                    int interval = t.length > 3 ? Integer.parseInt(t[3]) : 20;
                    for (int i = 0; i < count; i++) {
                        input.mask = mask(t[1]);
                        frames(4);
                        input.mask = 0;
                        frames(Math.max(1, interval - 4));
                    }
                    break;
                }
                case "touch": {
                    // frame coordinates (DS: 256x384, bottom screen from y=192)
                    int x = Integer.parseInt(t[1]);
                    int y = Integer.parseInt(t[2]);
                    int w = Math.max(1, core.frameWidth() > 0 ? core.frameWidth() : core.baseWidth);
                    int h = Math.max(1, core.frameHeight() > 0 ? core.frameHeight() : core.baseHeight);
                    input.px = (int) Math.round((x + 0.5) / w * 0xFFFE - 0x7FFF);
                    input.py = (int) Math.round((y + 0.5) / h * 0xFFFE - 0x7FFF);
                    input.down = true;
                    frames(t.length > 3 ? Integer.parseInt(t[3]) : 6);
                    input.down = false;
                    frames(12);
                    break;
                }
                case "shot":
                    shot(out.resolve(t[1] + ".png"));
                    break;
                case "dump":
                    dump(t[1]);
                    break;
                case "poke": {
                    // poke ADDR HEX: writes bytes on the console bus (tests)
                    long addr = Long.parseLong(t[1], 16);
                    byte[] b = java.util.HexFormat.of().parseHex(t[2]);
                    Ram.put(seg(addr, b.length), 0, b, 0, b.length);
                    break;
                }
                case "copy": {
                    // copy SRC DST LEN (hex addresses, decimal length)
                    int len = Integer.parseInt(t[3]);
                    byte[] b = Ram.bytes(seg(Long.parseLong(t[1], 16), len));
                    Ram.put(seg(Long.parseLong(t[2], 16), len), 0, b, 0, len);
                    break;
                }
                case "peek": {
                    long addr = Long.parseLong(t[1], 16);
                    int len = Integer.parseInt(t[2]);
                    System.out.println("MEM " + t[1] + " " + java.util.HexFormat.of().formatHex(Ram.bytes(seg(addr, len))));
                    break;
                }
                case "sram": {
                    // sram OFFSET LEN: cartridge save memory (hex offset)
                    java.nio.ByteBuffer sr = core.saveRam();
                    long off = Long.parseLong(t[1], 16);
                    int len = Integer.parseInt(t[2]);
                    System.out.println("SRAM " + t[1] + " " + (sr == null ? "?" : java.util.HexFormat.of().formatHex(Ram.bytes(sr, off, len))));
                    break;
                }
                case "presence":
                    // presence on: sample every 2 frames; presence: print the last sample (+ sprite PNG)
                    if (t.length > 1 && tracker instanceof TrackerHost th) {
                        presence = Presence.forReader(th.engine().reader(), out, rom);
                    } else {
                        System.out.println("PRESENCE image " + frame + " : " + sample);
                        if (presence != null && presence.spritePng() != null) {
                            Files.write(out.resolve("sprite-" + frame + ".png"), java.util.Base64.getDecoder().decode(presence.spritePng()));
                        }
                    }
                    break;
                case "trace":
                    // trace BUTTON FRAMES: holds a button, reads the presence on every image and measures how
                    // far the image itself scrolled: the position must follow the image the player sees
                    trace(t[1], Integer.parseInt(t[2]));
                    break;
                case "trace3d": {
                    // trace3d BUTTON FRAMES NAME: 3D games, where the position read lands with the camera sent with
                    // each image (screenshots NAME-n.png, to compare with the player drawn there)
                    input.mask = mask(t[1]);
                    for (int i = 0, n = Integer.parseInt(t[2]); i < n; i++) {
                        core.runFrame();
                        frame++;
                        Presence.Sample s = presence.sample(core);
                        float[] m = s == null ? null : s.view();
                        if (m != null) {
                            double x = s.x() / 16.0, y = s.y() / 16.0, z = s.z() / 16.0;
                            double w = x * m[3] + y * m[7] + z * m[11] + m[15];
                            double sx = ((x * m[0] + y * m[4] + z * m[8] + m[12]) / w + 1) * 128;
                            double sy = (1 - (x * m[1] + y * m[5] + z * m[9] + m[13]) / w) * 96;
                            System.out.println(String.format(Locale.ROOT, "TRACE3D %d %s %.1f %.1f %s %s %s", i, t[3], sx, sy, s.spriteId(), s.status(), s.free()));
                        }
                        shot(out.resolve(t[3] + "-" + i + ".png"));
                    }
                    input.mask = 0;
                    break;
                }
                case "btxsheet": {
                    // btxsheet NARC FILE[,FILE...] NAME: contact sheet of the textures of members of a NARC of the ROM (one row per member)
                    Gen5Sprites gs = new Gen5Sprites(rom, t[1], true);
                    String[] files = t[2].split(",");
                    BufferedImage sheet = new BufferedImage(34 * 32, 34 * files.length, BufferedImage.TYPE_INT_ARGB);
                    java.awt.Graphics2D g2 = sheet.createGraphics();
                    g2.setColor(new java.awt.Color(150, 190, 150));
                    g2.fillRect(0, 0, sheet.getWidth(), sheet.getHeight());
                    for (int r = 0; r < files.length; r++) {
                        int file = Integer.parseInt(files[r].trim());
                        int n = gs.frames(file);
                        System.out.println("BTX file " + file + ": " + n + " images");
                        for (int f = 0; f < n && f < 32; f++) {
                            String png = gs.png(file, f);
                            if (png != null) {
                                g2.drawImage(ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(png))), f * 34, r * 34, null);
                            }
                        }
                    }
                    g2.dispose();
                    int zoom = t.length > 4 ? Integer.parseInt(t[4]) : 1;
                    if (zoom > 1) {
                        BufferedImage big = new BufferedImage(sheet.getWidth() * zoom, sheet.getHeight() * zoom, BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < big.getHeight(); y++) {
                            for (int x = 0; x < big.getWidth(); x++) {
                                big.setRGB(x, y, sheet.getRGB(x / zoom, y / zoom));
                            }
                        }
                        sheet = big;
                    }
                    ImageIO.write(sheet, "png", out.resolve(t[3] + ".png").toFile());
                    break;
                }
                case "objects5": {
                    // objects5: generation 5, the movement models around the player
                    java.nio.ByteBuffer ram = core.systemRam();
                    int size = ram.capacity() - 0x300;
                    int player = -1;
                    for (int o = 0; o < size && player < 0; o += 4) {
                        if (ram.getInt(o + 8) == 0xFF) {
                            int gx = ram.getShort(o + 0x3C) & 0xFFFF;
                            int gz = ram.getShort(o + 0x40) & 0xFFFF;
                            int x = ram.getInt(o + 0x44);
                            int z = ram.getInt(o + 0x4C);
                            if ((gx != 0 || gz != 0) && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1) {
                                player = o;
                            }
                        }
                    }
                    System.out.println("OBJECTS5 player at 0x" + Long.toHexString(0x02000000L + player));
                    for (int k = -8; k <= 40; k++) {
                        int o = player + k * 0x100;
                        if (o < 0 || o + 0x100 >= size) {
                            continue;
                        }
                        int gx = ram.getShort(o + 0x3C) & 0xFFFF;
                        int gz = ram.getShort(o + 0x40) & 0xFFFF;
                        int x = ram.getInt(o + 0x44);
                        int z = ram.getInt(o + 0x4C);
                        boolean plausible = (gx != 0 || gz != 0) && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1;
                        if (!plausible) {
                            continue;
                        }
                        System.out.println(String.format(Locale.ROOT, "OBJ5 %+3d @%08X w0=%08X w1=%08X id=%d code=%04X dir(+18)=%d tile=%d,%d pos=%.1f,%.1f,%.1f +10=%08X +14=%08X +1C=%08X +20=%08X",
                                k, 0x02000000 + o, ram.getInt(o), ram.getInt(o + 4), ram.getInt(o + 8), ram.getShort(o + 0x0C) & 0xFFFF, ram.getShort(o + 0x18) & 0xFFFF,
                                gx, gz, x / 4096.0, ram.getInt(o + 0x48) / 4096.0, ram.getInt(o + 0x4C) / 4096.0,
                                ram.getInt(o + 0x10), ram.getInt(o + 0x14), ram.getInt(o + 0x1C), ram.getInt(o + 0x20)));
                    }
                    break;
                }
                case "annotate5": {
                    // annotate5 NAME: generation 5, a screenshot with a box on every movement model near the player
                    presence.sample(core);
                    Presence.Sample s5 = presence.sample(core);
                    float[] m5 = s5 == null ? null : s5.view();
                    if (m5 == null) {
                        throw new IllegalStateException("annotate5 : pas de caméra lue");
                    }
                    java.nio.ByteBuffer ram = core.systemRam();
                    int size = ram.capacity() - 0x300;
                    int player = -1;
                    for (int o = 0; o < size && player < 0; o += 4) {
                        if (ram.getInt(o + 8) == 0xFF) {
                            int gx = ram.getShort(o + 0x3C) & 0xFFFF;
                            int gz = ram.getShort(o + 0x40) & 0xFFFF;
                            int x = ram.getInt(o + 0x44);
                            int z = ram.getInt(o + 0x4C);
                            if ((gx != 0 || gz != 0) && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1) {
                                player = o;
                            }
                        }
                    }
                    int pw = core.frameWidth();
                    BufferedImage img = new BufferedImage(pw, core.frameHeight(), BufferedImage.TYPE_INT_RGB);
                    img.setRGB(0, 0, pw, core.frameHeight(), core.pixels(), 0, pw);
                    java.awt.Graphics2D g2 = img.createGraphics();
                    for (int k = -80; k <= 80; k++) {
                        int o = player + k * 0x100;
                        if (o < 0 || o + 0x100 >= size) {
                            continue;
                        }
                        int gx = ram.getShort(o + 0x3C) & 0xFFFF;
                        int gz = ram.getShort(o + 0x40) & 0xFFFF;
                        double x = ram.getInt(o + 0x44) / 4096.0;
                        double y = ram.getInt(o + 0x48) / 4096.0;
                        double z = ram.getInt(o + 0x4C) / 4096.0;
                        if ((gx == 0 && gz == 0) || Math.abs((((int) x) >> 4) - gx) > 1 && Math.abs(((int) x >> 16) - gx) > 1) {
                            continue;
                        }
                        double[] f = project(m5, new double[]{x, y, z});
                        System.out.println(String.format(Locale.ROOT, "ANNOT5 %+3d w0=%08X w1=%08X id=%d code=%04X pos=%.1f,%.1f,%.1f feet=%s", k, ram.getInt(o), ram.getInt(o + 4),
                                ram.getInt(o + 8), ram.getShort(o + 0x0C) & 0xFFFF, x, y, z, f == null ? "-" : String.format(Locale.ROOT, "%.1f,%.1f", f[0], f[1])));
                        if (f == null || f[0] < -40 || f[0] > 300 || f[1] < -40 || f[1] > 230) {
                            continue;
                        }
                        g2.setColor(k == 0 ? java.awt.Color.GREEN : java.awt.Color.RED);
                        g2.drawRect((int) Math.round(f[0]) - 8, (int) Math.round(f[1]) - 23, 17, 25);
                        g2.fillRect((int) Math.round(f[0]) - 1, (int) Math.round(f[1]) - 1, 3, 3);
                        g2.setColor(java.awt.Color.YELLOW);
                        g2.drawString(k + ":" + Integer.toHexString(ram.getShort(o + 0x0C) & 0xFFFF), (int) Math.round(f[0]) - 8, (int) Math.round(f[1]) - 25);
                    }
                    g2.dispose();
                    ImageIO.write(img, "png", out.resolve(t[1] + ".png").toFile());
                    break;
                }
                case "dumpframe": {
                    // dumpframe NAME: the 3D reading of the image (camera, feet, other characters, the player and the Pokémon
                    // following with their sprites) as NAME.json, and the top screen as NAME.png: to try the window's drawing
                    presence.sample(core);
                    Presence.Sample s = presence.sample(core);
                    if (s == null || s.view() == null) {
                        System.out.println("DUMP " + t[1] + " : pas de lecture 3D");
                        break;
                    }
                    java.util.Map<String, Object> d = new java.util.LinkedHashMap<>();
                    List<Object> mtx = new java.util.ArrayList<>();
                    for (float f : s.view()) {
                        mtx.add((double) f);
                    }
                    d.put("view", mtx);
                    List<Object> others = new java.util.ArrayList<>();
                    if (s.others() != null) {
                        for (float f : s.others()) {
                            others.add((double) f);
                        }
                    }
                    d.put("others", others);
                    d.put("map", (long) s.map());
                    d.put("x", (long) s.x());
                    d.put("y", (long) s.y());
                    d.put("z", (long) s.z());
                    d.put("sid", s.spriteId());
                    d.put("png", presence.spritePng());
                    if (s.pet() != null) {
                        d.put("pet", slt.Json.map("x", (long) s.pet().x(), "y", (long) s.pet().y(), "z", (long) s.pet().z(), "sid", s.pet().spriteId(),
                                "png", s.pet().png(), "w", (long) s.pet().w(), "h", (long) s.pet().h()));
                    }
                    Files.writeString(out.resolve(t[1] + ".json"), slt.Json.stringify(d));
                    BufferedImage top = new BufferedImage(256, 192, BufferedImage.TYPE_INT_RGB);
                    top.setRGB(0, 0, 256, 192, core.pixels(), 0, core.frameWidth());
                    ImageIO.write(top, "png", out.resolve(t[1] + ".png").toFile());
                    break;
                }
                case "btxcount": {
                    // btxcount NARC FIRST LAST: number and size of the textures of each member (summary)
                    Gen5Sprites gs = new Gen5Sprites(rom, t[1], true);
                    java.util.Map<String, List<Integer>> kinds = new java.util.TreeMap<>();
                    for (int f = Integer.parseInt(t[2]); f <= Integer.parseInt(t[3]); f++) {
                        int n = gs.frames(f);
                        String png = n > 0 ? gs.png(f, 0) : null;
                        String dim = "-";
                        if (png != null) {
                            BufferedImage im = ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(png)));
                            dim = im.getWidth() + "x" + im.getHeight();
                        }
                        kinds.computeIfAbsent(n + " images of " + dim, k -> new java.util.ArrayList<>()).add(f);
                    }
                    for (var e : kinds.entrySet()) {
                        List<Integer> l = e.getValue();
                        System.out.println("BTXCOUNT " + e.getKey() + " : " + l.size() + " members, e.g. " + l.subList(0, Math.min(12, l.size())));
                    }
                    break;
                }
                case "annotate": {
                    // annotate NAME STRIDE: generation 4, a screenshot with a box on every map object (gfx id and flags written)
                    annotate(t[1], Integer.parseInt(t[2], 16));
                    break;
                }
                case "followtrace":
                    // followtrace BUTTON FRAMES NARC MEMBER: generation 4 follower, per image: where it is, facing, the image of
                    // its texture that is drawn on the screen (best match), and the words of its object that changed
                    followTrace(t[1], Integer.parseInt(t[2]), t[3], Integer.parseInt(t[4]));
                    break;
                case "words": {
                    // words ADDR COUNT: 32-bit words of the console memory, 6 per line
                    long addr = Long.parseLong(t[1], 16);
                    int count = Integer.parseInt(t[2]);
                    java.nio.ByteBuffer sg = seg(addr, count * 4);
                    for (int i = 0; i < count; i += 6) {
                        StringBuilder sb = new StringBuilder(String.format("WORDS %08X:", addr + 4L * i));
                        for (int j = i; j < Math.min(count, i + 6); j++) {
                            sb.append(String.format(" %08X", sg.getInt(4 * j)));
                        }
                        System.out.println(sb);
                    }
                    break;
                }
                case "findfollower": {
                    // findfollower NARC FIRST LAST: generation 4 (HeartGold / SoulSilver), which texture of the NARC is the
                    // follower's: each image of each member is matched against the screen where the follower is
                    findFollower(t[1], Integer.parseInt(t[2]), Integer.parseInt(t[3]));
                    break;
                }
                case "followtable":
                    followTable();
                    break;
                case "objects4":
                    // objects4: generation 4, the map objects around the player (fields and first bytes)
                    objects4(t.length > 1 ? Integer.parseInt(t[1]) : 40);
                    break;
                case "jitter3d":
                    // jitter3d BUTTON FRAMES: 3D games, on which images the picture changes and on which the position
                    // and the camera read change: they must change together for the others to stay on the ground
                    jitter3d(t[1], Integer.parseInt(t[2]));
                    break;
                case "bag":
                    if (tracker instanceof TrackerHost th) {
                        System.out.println("SAC " + new java.util.TreeMap<>(th.testBag(core) == null ? java.util.Map.of() : th.testBag(core)));
                    }
                    break;
                case "additem":
                    if (tracker instanceof TrackerHost th) {
                        System.out.println("AJOUT " + t[1] + " x" + t[2] + " : " + th.testAddItem(core, Integer.parseInt(t[1]), Integer.parseInt(t[2])));
                    }
                    break;
                case "tobox":
                    if (tracker instanceof TrackerHost th) {
                        System.out.println("PC " + t[1] + " " + t[2] + " : " + th.testToBox(core, Long.parseLong(t[1]), "dead".equals(t[2])));
                    }
                    break;
                case "directives":
                    // directives {JSON}: the room's decisions, as the server sends them (park, setMode…)
                    if (tracker instanceof TrackerHost th) {
                        th.setToast(s -> System.out.println("TOAST " + s));
                        th.directives(slt.Json.parseObject(line.substring(line.indexOf('{'))));
                    }
                    break;
                case "status":
                    if (tracker instanceof TrackerHost th) {
                        StringBuilder sb = new StringBuilder("ETAT image " + frame + " : " + th.engine().reader()
                                + " combat=" + th.engine().battleState() + " équipe=");
                        for (slt.player.track.Mon m : th.engine().party()) {
                            sb.append(m.nickname()).append('#').append(m.species()).append(" N.").append(m.level())
                                    .append(' ').append(m.hp()).append('/').append(m.maxHp()).append(" uid=").append(m.uid()).append("; ");
                        }
                        System.out.println(sb);
                    }
                    break;
                default:
                    throw new IOException("Commande inconnue : " + line);
            }
        }
        System.out.println("SCRIPT OK : " + frame + " images");
    }

    /** Console memory: the bus, or the DS main RAM (0x02000000) the core only exposes as system RAM. */
    private java.nio.ByteBuffer seg(long addr, int len) {
        java.nio.ByteBuffer s = core.bus(addr, len);
        if (s == null && addr >= 0x02000000L && addr + len <= 0x02400000L && core.systemRam() != null) {
            s = Ram.slice(core.systemRam(), addr - 0x02000000L, len);
        }
        if (s == null) {
            throw new IllegalArgumentException("Adresse inaccessible : " + Long.toHexString(addr));
        }
        return s;
    }

    private void frames(int n) {
        for (int i = 0; i < n; i++) {
            core.runFrame();
            frame++;
            if (tracker != null && frame % 30 == 0) {
                tracker.tick(core, frame);
            }
            if (presence != null) {
                sample = presence.sample(core);
            }
        }
    }

    private void trace(String button, int n) {
        if (presence == null) {
            throw new IllegalStateException("trace : faire « presence on » d'abord");
        }
        int w = core.frameWidth();
        int h = core.frameHeight();
        int[] prev = java.util.Arrays.copyOf(core.pixels(), w * h);
        int camX = 0;
        int camY = 0;
        Presence.Sample first = presence.sample(core);
        input.mask = mask(button);
        for (int i = 0; i < n; i++) {
            core.runFrame();
            frame++;
            Presence.Sample s = presence.sample(core);
            int[] px = java.util.Arrays.copyOf(core.pixels(), w * h);
            int[] d = imageShift(prev, px, w, h);
            camX += d[0];
            camY += d[1];
            prev = px;
            System.out.println(String.format(Locale.ROOT, "TRACE %d image %+d,%+d presence %s", frame, camX, camY,
                    s == null || first == null ? "-" : String.format(Locale.ROOT, "%+d,%+d %s %s", s.x() - first.x(),
                            s.y() - first.y(), s.free() ? "libre" : s.status(), s.anchorX() + "," + s.anchorY())));
        }
        input.mask = 0;
    }

    private void annotate(String name, int stride) throws IOException {
        presence.sample(core);
        Presence.Sample s = presence.sample(core);
        float[] m = s == null ? null : s.view();
        if (m == null) {
            throw new IllegalStateException("annotate : pas de caméra lue");
        }
        java.nio.ByteBuffer ram = core.systemRam();
        int size = ram.capacity() - 0x300;
        int player = -1;
        for (int o = 0; o < size && player < 0; o += 4) {
            if (ram.getInt(o + 8) == 0xFF) {
                int gx = ram.getInt(o + 0x64);
                int gz = ram.getInt(o + 0x6C);
                int x = ram.getInt(o + 0x70);
                int z = ram.getInt(o + 0x78);
                if (gx > 0 && gz > 0 && gx < 0x10000 && gz < 0x10000 && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1) {
                    player = o;
                }
            }
        }
        int w = core.frameWidth();
        int h = core.frameHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, core.pixels(), 0, w);
        java.awt.Graphics2D g2 = img.createGraphics();
        for (int k = 0; k < 64; k++) {
            int o = player + k * stride;
            if (o + stride >= size) {
                break;
            }
            int flags = ram.getInt((int) o);
            int gx = ram.getInt(o + 0x64);
            int gz = ram.getInt(o + 0x6C);
            double x = ram.getInt(o + 0x70) / 4096.0;
            double y = ram.getInt(o + 0x74) / 4096.0;
            double z = ram.getInt(o + 0x78) / 4096.0;
            if ((flags & 1) == 0 || gx <= 0 || gz <= 0 || gx > 0x10000 || gz > 0x10000) {
                continue;
            }
            double[] f = project(m, new double[]{x, y, z});
            System.out.println(String.format(Locale.ROOT, "ANNOT %2d flags=%08X id=%d gfx=%d face=%d feet=%s", k, flags, ram.getInt(o + 8), ram.getInt(o + 0x10),
                    ram.getInt(o + 0x28), f == null ? "-" : String.format(Locale.ROOT, "%.1f,%.1f", f[0], f[1])));
            if (f == null) {
                continue;
            }
            g2.setColor(k == 0 ? java.awt.Color.GREEN : java.awt.Color.RED);
            g2.drawRect((int) Math.round(f[0]) - 8, (int) Math.round(f[1]) - 23, 17, 25);
            g2.fillRect((int) Math.round(f[0]) - 1, (int) Math.round(f[1]) - 1, 3, 3);
            g2.setColor(java.awt.Color.YELLOW);
            g2.drawString(k + ":" + ram.getInt(o + 0x10), (int) Math.round(f[0]) - 8, (int) Math.round(f[1]) - 25);
        }
        g2.dispose();
        ImageIO.write(img, "png", out.resolve(name + ".png").toFile());
    }

    private void followTrace(String button, int n, String narc, int member) throws IOException {
        java.nio.ByteBuffer ram = core.systemRam();
        int size = ram.capacity() - 0x300;
        int player = -1;
        for (int o = 0; o < size && player < 0; o += 4) {
            if (ram.getInt(o + 8) == 0xFF) {
                int gx = ram.getInt(o + 0x64);
                int gz = ram.getInt(o + 0x6C);
                int x = ram.getInt(o + 0x70);
                int z = ram.getInt(o + 0x78);
                if (gx > 0 && gz > 0 && gx < 0x10000 && gz < 0x10000 && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1) {
                    player = o;
                }
            }
        }
        int follower = -1;
        for (int k = 1; k < 40 && follower < 0; k++) {
            if (player + k * 0x12C + 0x12C < size && ram.getInt(player + k * 0x12C + 8) == 253) {
                follower = player + k * 0x12C;
            }
        }
        if (follower < 0) {
            System.out.println("FTRACE follower not found");
            return;
        }
        Gen5Sprites gs = new Gen5Sprites(rom, narc, true);
        BufferedImage[] frames = new BufferedImage[gs.frames(member)];
        for (int f = 0; f < frames.length; f++) {
            frames[f] = ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(gs.png(member, f))));
        }
        int w = core.frameWidth();
        int[] prevWords = new int[75];
        input.mask = mask(button);
        for (int i = 0; i < n; i++) {
            core.runFrame();
            frame++;
            Presence.Sample s = presence.sample(core);
            float[] m = s == null ? null : s.view();
            int[] px = core.pixels();
            StringBuilder changed = new StringBuilder();
            for (int k = 0; k < 75; k++) {
                int v = ram.getInt(follower + 4 * k);
                if (v != prevWords[k]) {
                    changed.append(String.format(" +%X=%X", 4 * k, v));
                }
                prevWords[k] = v;
            }
            String best = "-";
            if (m != null) {
                double[] feet = project(m, new double[]{ram.getInt(follower + 0x70) / 4096.0, ram.getInt(follower + 0x74) / 4096.0, ram.getInt(follower + 0x78) / 4096.0});
                if (feet != null) {
                    int bestBad = Integer.MAX_VALUE;
                    int bestFrame = -1;
                    String second = "";
                    int[] bads = new int[frames.length];
                    for (int f = 0; f < frames.length; f++) {
                        int fb = Integer.MAX_VALUE;
                        for (int oy = -4; oy <= 4; oy++) {
                            for (int ox = -4; ox <= 4; ox++) {
                                int bad = 0;
                                int count = 0;
                                for (int y = 0; y < 32; y++) {
                                    for (int x = 0; x < 32; x++) {
                                        int c = frames[f].getRGB(x, y);
                                        if ((c >>> 24) < 200) {
                                            continue;
                                        }
                                        int sx = (int) Math.round(feet[0]) + 1 - 16 + ox + x;
                                        int sy = (int) Math.round(feet[1]) - 29 + oy + y;
                                        count++;
                                        if (sx < 0 || sx >= 256 || sy < 0 || sy >= 192) {
                                            bad += 3;
                                            continue;
                                        }
                                        int p = px[sy * w + sx];
                                        if (Math.abs(((p >> 16) & 0xFF) - ((c >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((c >> 8) & 0xFF))
                                                + Math.abs((p & 0xFF) - (c & 0xFF)) > 60) {
                                            bad++;
                                        }
                                    }
                                }
                                fb = Math.min(fb, bad * 100 / Math.max(1, count));
                            }
                        }
                        bads[f] = fb;
                        if (fb < bestBad) {
                            bestBad = fb;
                            bestFrame = f;
                        }
                    }
                    best = String.format(Locale.ROOT, "image %d (%d%% differ) all %s feet %.1f,%.1f", bestFrame, bestBad, java.util.Arrays.toString(bads), feet[0], feet[1]);
                }
            }
            System.out.println(String.format(Locale.ROOT, "FTRACE %3d pos %.2f,%.2f tile %d,%d face %d  %s  | words%s", i,
                    ram.getInt(follower + 0x70) / 4096.0, ram.getInt(follower + 0x78) / 4096.0, ram.getInt(follower + 0x64), ram.getInt(follower + 0x6C),
                    ram.getInt(follower + 0x28), best, changed));
        }
        input.mask = 0;
    }

    private void findFollower(String narc, int first, int last) throws IOException {
        presence.sample(core); // the camera of the image
        Presence.Sample s = presence.sample(core);
        float[] m = s == null ? null : s.view();
        if (m == null) {
            throw new IllegalStateException("findfollower : pas de caméra lue");
        }
        java.nio.ByteBuffer ram = core.systemRam();
        int size = ram.capacity() - 0x300;
        int player = -1;
        for (int o = 0; o < size && player < 0; o += 4) {
            if (ram.getInt(o + 8) == 0xFF) {
                int gx = ram.getInt(o + 0x64);
                int gz = ram.getInt(o + 0x6C);
                int x = ram.getInt(o + 0x70);
                int z = ram.getInt(o + 0x78);
                if (gx > 0 && gz > 0 && gx < 0x10000 && gz < 0x10000 && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1) {
                    player = o;
                }
            }
        }
        double[] feet = null;
        for (int k = 1; k < 40 && feet == null; k++) {
            int o = player + k * 0x12C;
            if (o + 0x12C < size && ram.getInt(o + 8) == 253) {
                double[] w = {ram.getInt(o + 0x70) / 4096.0, ram.getInt(o + 0x74) / 4096.0, ram.getInt(o + 0x78) / 4096.0};
                feet = project(m, w);
                System.out.println(String.format(Locale.ROOT, "FOLLOWER object %d gfx %d at %.1f,%.1f,%.1f -> screen %s", k, ram.getInt(o + 0x10), w[0], w[1], w[2],
                        feet == null ? "-" : String.format(Locale.ROOT, "%.1f,%.1f", feet[0], feet[1])));
            }
        }
        if (feet == null) {
            System.out.println("FOLLOWER introuvable");
            return;
        }
        int w = core.frameWidth();
        int[] px = core.pixels();
        Gen5Sprites gs = new Gen5Sprites(rom, narc, true);
        List<String> best = new java.util.ArrayList<>();
        record Hit(int file, int frame, int bad, int used, int dx, int dy) {
        }
        List<Hit> hits = new java.util.ArrayList<>();
        for (int file = first; file <= last; file++) {
            int n = gs.frames(file);
            for (int f = 0; f < n; f++) {
                String png = gs.png(file, f);
                if (png == null) {
                    continue;
                }
                BufferedImage spr = ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(png)));
                int bestBad = Integer.MAX_VALUE;
                int bx = 0;
                int by = 0;
                int used = 0;
                for (int oy = -12; oy <= 12; oy++) {
                    for (int ox = -12; ox <= 12; ox++) {
                        int bad = 0;
                        int count = 0;
                        for (int y = 0; y < 32; y++) {
                            for (int x = 0; x < 32; x++) {
                                int c = spr.getRGB(x, y);
                                if ((c >>> 24) < 200) {
                                    continue;
                                }
                                int sx = (int) Math.round(feet[0]) + 1 - 16 + ox + x;
                                int sy = (int) Math.round(feet[1]) - 29 + oy + y;
                                count++;
                                if (sx < 0 || sx >= 256 || sy < 0 || sy >= 192) {
                                    bad += 3;
                                    continue;
                                }
                                int p = px[sy * w + sx];
                                if (Math.abs(((p >> 16) & 0xFF) - ((c >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((c >> 8) & 0xFF))
                                        + Math.abs((p & 0xFF) - (c & 0xFF)) > 60) {
                                    bad++;
                                }
                            }
                        }
                        if (count >= 30 && bad < bestBad) {
                            bestBad = bad;
                            bx = ox;
                            by = oy;
                            used = count;
                        }
                    }
                }
                if (used > 0) {
                    hits.add(new Hit(file, f, bestBad, used, bx, by));
                }
            }
        }
        hits.sort(java.util.Comparator.comparingDouble(h -> h.bad() / (double) h.used()));
        for (int i = 0; i < Math.min(12, hits.size()); i++) {
            Hit h = hits.get(i);
            System.out.println(String.format(Locale.ROOT, "HIT member %d image %d : %d of %d opaque pixels differ, offset %+d,%+d", h.file(), h.frame(), h.bad(), h.used(), h.dx(), h.dy()));
        }
    }

    private void followTable() {
        java.nio.ByteBuffer ram = core.systemRam();
        long ref = ram == null ? -1 : Gen4Followers.locate(ram);
        if (ref < 0) {
            System.out.println("FOLLOWTABLE introuvable");
            return;
        }
        System.out.println(String.format("FOLLOWTABLE entrée de référence @%08X", 0x02000000L + ref));
        int usual = 0;
        for (int k = -1100; k <= 1100; k++) {
            long o = ref + 12L * k;
            if (o < 0 || o + 12 > ram.capacity()) {
                continue;
            }
            int member = ram.getInt((int) o);
            int a = ram.getShort((int) o + 4) & 0xFFFF;
            int b = ram.getShort((int) o + 6) & 0xFFFF;
            int c = ram.getShort((int) o + 8) & 0xFFFF;
            int d = ram.getShort((int) o + 10) & 0xFFFF;
            if (member >= 297 && member < 863) {
                if (a == 751 && c == 832 && d == 0) {
                    usual++;
                } else {
                    System.out.println(String.format("FOLLOWTABLE gfx %d : membre %d, mots %d %d %d %d", 947 + k, member, a, b, c, d));
                }
            }
        }
        System.out.println("FOLLOWTABLE entrées habituelles : " + usual);
    }

    private void objects4(int around) {
        java.nio.ByteBuffer ram = core.systemRam();
        int size = ram.capacity() - 0x300;
        int player = -1;
        for (int o = 0; o < size && player < 0; o += 4) {
            if (ram.getInt(o + 8) == 0xFF) {
                int gx = ram.getInt(o + 0x64);
                int gz = ram.getInt(o + 0x6C);
                int x = ram.getInt(o + 0x70);
                int z = ram.getInt(o + 0x78);
                if (gx > 0 && gz > 0 && gx < 0x10000 && gz < 0x10000 && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1) {
                    player = o;
                }
            }
        }
        System.out.println("OBJECTS4 player at 0x" + Long.toHexString(0x02000000L + player));
        // every plausible object of the RAM, whatever the stride
        for (int o = 0; o < size; o += 4) {
            int id = ram.getInt(o + 8);
            int gx = ram.getInt(o + 0x64);
            int gz = ram.getInt(o + 0x6C);
            int x = ram.getInt(o + 0x70);
            int z = ram.getInt(o + 0x78);
            if (o != player && gx > 0 && gz > 0 && gx < 0x1000 && gz < 0x1000 && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1
                    && id >= 0 && id < 0x100) {
                System.out.println(String.format(Locale.ROOT, "ANY @%08X (%+d from the player) id=%d gfx=%d tile=%d,%d pos=%.1f,%.1f face=%d w0=%08X",
                        0x02000000 + o, o - player, id, ram.getInt(o + 0x10), gx, gz, x / 4096.0, z / 4096.0, ram.getInt(o + 0x28), ram.getInt(o)));
            }
        }
        for (int k = -around; k <= around; k++) {
            int o = player + k * 0x128;
            if (o < 0 || o + 0x128 > size) {
                continue;
            }
            int gx = ram.getInt(o + 0x64);
            int gz = ram.getInt(o + 0x6C);
            int x = ram.getInt(o + 0x70);
            int z = ram.getInt(o + 0x78);
            boolean plausible = gx > 0 && gz > 0 && gx < 0x10000 && gz < 0x10000 && Math.abs((x >> 16) - gx) <= 1 && Math.abs((z >> 16) - gz) <= 1;
            if (!plausible) {
                continue;
            }
            StringBuilder sb = new StringBuilder(String.format(Locale.ROOT,
                    "OBJ %+3d @%08X  w0=%08X w1=%08X id=%d  +0C=%08X gfx(+10)=%d  +14=%08X +18=%08X +1C=%08X +20=%08X +24=%08X face(+28)=%d  tile=%d,%d,%d  pos=%.2f,%.2f,%.2f  +7C=%08X +80=%08X +84=%08X",
                    k, 0x02000000 + o, ram.getInt(o), ram.getInt(o + 4), ram.getInt(o + 8), ram.getInt(o + 0x0C), ram.getInt(o + 0x10),
                    ram.getInt(o + 0x14), ram.getInt(o + 0x18), ram.getInt(o + 0x1C), ram.getInt(o + 0x20), ram.getInt(o + 0x24),
                    ram.getInt(o + 0x28), gx, ram.getInt(o + 0x68), gz, x / 4096.0, ram.getInt(o + 0x74) / 4096.0, z / 4096.0,
                    ram.getInt(o + 0x7C), ram.getInt(o + 0x80), ram.getInt(o + 0x84)));
            System.out.println(sb);
        }
    }

    private void jitter3d(String button, int n) {
        if (presence == null) {
            throw new IllegalStateException("jitter3d : faire « presence on » d'abord");
        }
        int w = core.frameWidth();
        int[] prev = java.util.Arrays.copyOf(core.pixels(), w * 192);
        float[] prevView = null;
        int prevX = 0;
        int prevZ = 0;
        // a point of the ground 3 tiles from where the player starts (ahead of the walk): where it lands on each image
        // with the camera read on that image or on the one before, against how the ground moved on the image
        List<float[]> views = new java.util.ArrayList<>();
        List<int[]> images = new java.util.ArrayList<>();
        double[] anchor = null;
        input.mask = mask(button);
        for (int i = 0; i < n; i++) {
            core.runFrame();
            frame++;
            Presence.Sample s = presence.sample(core);
            int[] px = java.util.Arrays.copyOf(core.pixels(), w * 192);
            if (s != null && s.view() != null) {
                if (anchor == null) {
                    boolean vertical = button.toUpperCase(Locale.ROOT).contains("UP") || button.toUpperCase(Locale.ROOT).contains("DOWN");
                    int sign = button.toUpperCase(Locale.ROOT).contains("UP") || button.toUpperCase(Locale.ROOT).contains("LEFT") ? -1 : 1;
                    anchor = new double[]{s.x() / 16.0 + (vertical ? 0 : sign * 48), s.y() / 16.0, s.z() / 16.0 + (vertical ? sign * 48 : 0)};
                }
                views.add(s.view());
                images.add(px);
            }
            int diff = 0;
            int total = 0;
            for (int y = 0; y < 192; y += 2) {
                for (int x = 0; x < w; x += 2) {
                    int p = px[y * w + x];
                    int q = prev[y * w + x];
                    total++;
                    if (Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                            + Math.abs((p & 0xFF) - (q & 0xFF)) > 24) {
                        diff++;
                    }
                }
            }
            float[] m = s == null ? null : s.view();
            String found = "";
            if (m != null && presence.spritePng() != null) {
                int drop = m.length > 19 ? Math.round(m[19]) : 0;
                int dx = m.length > 20 ? Math.round(m[20]) : 1;
                int[] at = locateSprite(px, w, presence.spritePng(), Math.round(m[17]) + dx - 16, Math.round(m[18]) - 32 + drop);
                found = at == null ? "sprite not found" : String.format(Locale.ROOT, "sprite at %d,%d (as placed: %d,%d) score %d", at[0], at[1],
                        Math.round(m[17]) + dx - 16, Math.round(m[18]) - 32 + drop, at[2]);
            }
            double dv = 0;
            if (m != null && prevView != null) {
                for (int k = 0; k < 16; k++) {
                    dv = Math.max(dv, Math.abs(m[k] - prevView[k]));
                }
            }
            int dx = s == null ? 0 : s.x() - prevX;
            int dz = s == null ? 0 : s.z() - prevZ;
            System.out.println(String.format(Locale.ROOT, "JITTER %3d image %s (%4.1f%%)  camera %s (%.4f)  position %s (%+d,%+d)  feet %s  %s",
                    i, diff * 100 > total * 2 ? "CHANGE" : "  =   ", 100.0 * diff / total, dv > 0.0005 ? "CHANGE" : "  =   ", dv,
                    dx != 0 || dz != 0 ? "CHANGE" : "  =   ", dx, dz,
                    m == null ? "-" : String.format(Locale.ROOT, "%.1f,%.1f w%.2f", m[17], m[18], m[16]),
                    s == null ? "-" : s.status() + " " + s.spriteId() + "  " + found
                            + (s.others() == null ? "" : "  others " + s.others().length / 4)
                            + (s.pet() == null ? "" : "  pet " + s.pet().spriteId() + " at " + s.pet().x() / 16.0 + "," + s.pet().z() / 16.0)));
            prev = px;
            prevView = m != null ? m : prevView;
            if (s != null) {
                prevX = s.x();
                prevZ = s.z();
            }
        }
        input.mask = 0;
        if (anchor == null || views.size() < 6) {
            return;
        }
        // for each image that differs from the one before: the ground around the anchor moved by (dx, dy); the anchor moved by
        // the same amount if the camera is the right one. lag 0: camera read on this image; 1: on the image before; -1: on the next
        double[] err = new double[3];
        int counted = 0;
        for (int i = 3; i + 1 < images.size(); i++) {
            double[] a1 = project(views.get(i - 1), anchor);
            if (a1 == null || a1[0] < 30 || a1[0] > 226 || a1[1] < 30 || a1[1] > 160) {
                continue;
            }
            int[] d = patchShift(images.get(i - 1), images.get(i), w, (int) Math.round(a1[0]), (int) Math.round(a1[1]));
            if (d == null) {
                continue;
            }
            double[][] before = new double[3][];
            double[][] after = new double[3][];
            for (int lag = -1; lag <= 1; lag++) {
                before[lag + 1] = project(views.get(Math.max(0, i - 1 - lag)), anchor);
                after[lag + 1] = project(views.get(Math.max(0, i - lag)), anchor);
            }
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "SHIFT %3d actual %+d,%+d  ", i, d[0], d[1]));
            for (int k = 0; k < 3; k++) {
                if (before[k] == null || after[k] == null) {
                    continue;
                }
                double px = after[k][0] - before[k][0];
                double py = after[k][1] - before[k][1];
                row.append(String.format(Locale.ROOT, "lag%+d %+.1f,%+.1f  ", k - 1, px, py));
                err[k] += Math.abs(px - d[0]) + Math.abs(py - d[1]);
            }
            counted++;
            System.out.println(row);
        }
        System.out.println(String.format(Locale.ROOT, "SHIFT total error over %d images: camera of the next image %.1f, of this image %.1f, of the image before %.1f",
                counted, err[0], err[1], err[2]));
    }

    /** Where the sprite (PNG, base64) is drawn on the top screen, searched around a place: top left corner and the pixels that differ (opaque ones only). */
    private static int[] locateSprite(int[] px, int w, String png, int nearX, int nearY) {
        BufferedImage spr;
        try {
            spr = ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(png)));
        } catch (IOException | RuntimeException e) {
            return null;
        }
        int best = Integer.MAX_VALUE;
        int[] r = null;
        int top = 99;
        int bottom = -1;
        int left = 99;
        int right = -1;
        for (int y = 0; y < spr.getHeight(); y++) {
            for (int x = 0; x < spr.getWidth(); x++) {
                if ((spr.getRGB(x, y) >>> 24) >= 200) {
                    top = Math.min(top, y);
                    bottom = Math.max(bottom, y);
                    left = Math.min(left, x);
                    right = Math.max(right, x);
                }
            }
        }
        System.out.println("SPRITE opaque rows " + top + ".." + bottom + " columns " + left + ".." + right + " of " + spr.getWidth() + "x" + spr.getHeight());
        for (int oy = -14; oy <= 14; oy++) {
            for (int ox = -14; ox <= 14; ox++) {
                int bad = 0;
                int used = 0;
                for (int y = 0; y < spr.getHeight(); y++) {
                    for (int x = 0; x < spr.getWidth(); x++) {
                        int c = spr.getRGB(x, y);
                        if ((c >>> 24) < 200) {
                            continue;
                        }
                        int sx = nearX + ox + x;
                        int sy = nearY + oy + y;
                        if (sx < 0 || sx >= 256 || sy < 0 || sy >= 192) {
                            bad += 3;
                            continue;
                        }
                        int p = px[sy * w + sx];
                        used++;
                        if (Math.abs(((p >> 16) & 0xFF) - ((c >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((c >> 8) & 0xFF))
                                + Math.abs((p & 0xFF) - (c & 0xFF)) > 60) {
                            bad++;
                        }
                    }
                }
                if (used > 0 && bad < best) {
                    best = bad;
                    r = new int[]{nearX + ox, nearY + oy, bad};
                }
            }
        }
        return r;
    }

    /** Where a world point lands on the top screen with a camera x projection (row vectors), null if behind. */
    private static double[] project(float[] m, double[] p) {
        double w = p[0] * m[3] + p[1] * m[7] + p[2] * m[11] + m[15];
        if (w <= 0.01) {
            return null;
        }
        return new double[]{((p[0] * m[0] + p[1] * m[4] + p[2] * m[8] + m[12]) / w + 1) * 128,
                (1 - (p[0] * m[1] + p[1] * m[5] + p[2] * m[9] + m[13]) / w) * 96};
    }

    /** How far the ground moved around a point between two images (window of 41 x 41), null when it did not move or is ambiguous. */
    private static int[] patchShift(int[] a, int[] b, int w, int cx, int cy) {
        long best = Long.MAX_VALUE;
        long second = Long.MAX_VALUE;
        long none = 0;
        int[] r = {0, 0};
        for (int dy = -7; dy <= 7; dy++) {
            for (int dx = -7; dx <= 7; dx++) {
                long sum = 0;
                for (int y = cy - 20; y <= cy + 20; y++) {
                    for (int x = cx - 20; x <= cx + 20; x++) {
                        int p = b[y * w + x];
                        int q = a[(y - dy) * w + x - dx];
                        sum += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                                + Math.abs((p & 0xFF) - (q & 0xFF));
                    }
                }
                if (dx == 0 && dy == 0) {
                    none = sum;
                }
                if (sum < best) {
                    second = best;
                    best = sum;
                    r[0] = dx;
                    r[1] = dy;
                } else if (sum < second) {
                    second = sum;
                }
            }
        }
        // the picture did not change there, or several shifts look alike (flat ground): nothing to learn
        if ((r[0] == 0 && r[1] == 0 && none == 0) || best * 10 > second * 9 && second > 0 && best > 0) {
            return null;
        }
        return r;
    }

    /** How far the picture moved between two images (the camera's step), outside the player in the centre. */
    private static int[] imageShift(int[] a, int[] b, int w, int h) {
        long best = Long.MAX_VALUE;
        int[] r = {0, 0};
        for (int dy = -6; dy <= 6; dy++) {
            for (int dx = -6; dx <= 6; dx++) {
                long sum = 0;
                for (int y = 12; y < h - 12; y += 2) {
                    for (int x = 12; x < w - 12; x += 2) {
                        if (Math.abs(x - w / 2) < 20 && Math.abs(y - h / 2) < 28) {
                            continue;
                        }
                        int p = b[y * w + x];
                        int q = a[(y + dy) * w + x + dx];
                        sum += Math.abs(((p >> 16) & 0xFF) - ((q >> 16) & 0xFF)) + Math.abs(((p >> 8) & 0xFF) - ((q >> 8) & 0xFF))
                                + Math.abs((p & 0xFF) - (q & 0xFF));
                    }
                }
                if (sum < best) {
                    best = sum;
                    r[0] = dx;
                    r[1] = dy;
                }
            }
        }
        return r;
    }

    private static int mask(String spec) {
        int m = 0;
        for (String b : spec.toUpperCase(Locale.ROOT).split("[,+]")) {
            switch (b) {
                case "A": m |= 1 << Retro.JOY_A; break;
                case "B": m |= 1 << Retro.JOY_B; break;
                case "X": m |= 1 << Retro.JOY_X; break;
                case "Y": m |= 1 << Retro.JOY_Y; break;
                case "L": m |= 1 << Retro.JOY_L; break;
                case "R": m |= 1 << Retro.JOY_R; break;
                case "START": m |= 1 << Retro.JOY_START; break;
                case "SELECT": m |= 1 << Retro.JOY_SELECT; break;
                case "UP": m |= 1 << Retro.JOY_UP; break;
                case "DOWN": m |= 1 << Retro.JOY_DOWN; break;
                case "LEFT": m |= 1 << Retro.JOY_LEFT; break;
                case "RIGHT": m |= 1 << Retro.JOY_RIGHT; break;
                case "NONE": break;
                default: throw new IllegalArgumentException("Bouton inconnu : " + b);
            }
        }
        return m;
    }

    private void shot(Path file) throws IOException {
        int w = core.frameWidth();
        int h = core.frameHeight();
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        img.setRGB(0, 0, w, h, core.pixels(), 0, w);
        ImageIO.write(img, "png", file.toFile());
    }

    private void dump(String name) throws IOException {
        java.nio.ByteBuffer ram = core.systemRam();
        if (ram != null) {
            Files.write(out.resolve(name + ".sysram.bin"), Ram.bytes(ram));
        }
        int i = 0;
        StringBuilder index = new StringBuilder();
        for (CoreHost.Region r : core.regions()) {
            if (r.length() > 4 << 20) {
                continue; // cartridge ROM mirrors
            }
            String f = String.format(Locale.ROOT, "%s.%02d_%08x.bin", name, i++, r.start());
            Files.write(out.resolve(f), Ram.bytes(r.data()));
            index.append(f).append(' ').append(Long.toHexString(r.start())).append(' ').append(r.length()).append('\n');
        }
        Files.writeString(out.resolve(name + ".regions.txt"), index.toString());
    }
}
