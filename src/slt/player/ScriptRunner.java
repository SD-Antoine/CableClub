package slt.player;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static java.lang.foreign.ValueLayout.JAVA_BYTE;

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
 * poke 20244e9 02          copy 2024744 2024550 100     peek 2024550 16   (tests)
 * sram 75ea 16            (cartridge save memory)
 * presence on             presence          (players drawn in game: last reading)
 * trace LEFT 40            (holds a button, compares the position read with the scrolling of the image)
 * trace3d DOWN 60 t/d      (3D games: where the position lands with the camera sent, + screenshots)
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
                    MemorySegment seg = seg(addr, b.length);
                    MemorySegment.copy(b, 0, seg, JAVA_BYTE, 0, b.length);
                    break;
                }
                case "copy": {
                    // copy SRC DST LEN (hex addresses, decimal length)
                    int len = Integer.parseInt(t[3]);
                    byte[] b = seg(Long.parseLong(t[1], 16), len).toArray(JAVA_BYTE);
                    MemorySegment.copy(b, 0, seg(Long.parseLong(t[2], 16), len), JAVA_BYTE, 0, len);
                    break;
                }
                case "peek": {
                    long addr = Long.parseLong(t[1], 16);
                    int len = Integer.parseInt(t[2]);
                    System.out.println("MEM " + t[1] + " " + java.util.HexFormat.of().formatHex(seg(addr, len).toArray(JAVA_BYTE)));
                    break;
                }
                case "sram": {
                    // sram OFFSET LEN: cartridge save memory (hex offset)
                    MemorySegment sr = core.saveRam();
                    long off = Long.parseLong(t[1], 16);
                    int len = Integer.parseInt(t[2]);
                    System.out.println("SRAM " + t[1] + " " + (sr == null ? "?" : java.util.HexFormat.of().formatHex(sr.asSlice(off, len).toArray(JAVA_BYTE))));
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
    private MemorySegment seg(long addr, int len) {
        MemorySegment s = core.bus(addr, len);
        if (s == null && addr >= 0x02000000L && addr + len <= 0x02400000L && core.systemRam() != null) {
            s = core.systemRam().asSlice(addr - 0x02000000L, len);
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
        MemorySegment ram = core.systemRam();
        if (ram != null) {
            Files.write(out.resolve(name + ".sysram.bin"), ram.toArray(JAVA_BYTE));
        }
        int i = 0;
        StringBuilder index = new StringBuilder();
        for (CoreHost.Region r : core.regions()) {
            if (r.length() > 4 << 20) {
                continue; // cartridge ROM mirrors
            }
            String f = String.format(Locale.ROOT, "%s.%02d_%08x.bin", name, i++, r.start());
            Files.write(out.resolve(f), r.data().toArray(JAVA_BYTE));
            index.append(f).append(' ').append(Long.toHexString(r.start())).append(' ').append(r.length()).append('\n');
        }
        Files.writeString(out.resolve(name + ".regions.txt"), index.toString());
    }
}
