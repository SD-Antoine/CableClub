package slt.player;

import slt.Ghosts;
import slt.Json;
import slt.Log;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;

/**
 * Runs the emulation loop on its own thread. There is no window: images go
 * to the SLT window through the WebSocket, sound goes straight to the
 * speakers, buttons come back through the WebSocket (or the gamepad).
 */
final class Session {

    /** Observes the running game (the automatic Soul Link tracker). */
    interface Tracker {
        void tick(CoreHost core, long frame);
    }

    private final CoreHost core;
    private final Controls controls;
    private final Path stateDir;
    private final Path captureDir;
    private final Bridge bridge;
    private final WsServer ws;
    private final int liveFps;
    private final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
    private final ExecutorService encoder = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "slt-apercu");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private final AtomicBoolean encoding = new AtomicBoolean();
    private volatile boolean running = true;
    private volatile boolean userPaused;
    private volatile boolean roomPaused;
    private volatile boolean statesAllowed;
    private volatile boolean fastHeld;
    private volatile boolean fastToggled;
    private volatile Tracker tracker;
    private volatile int volume;
    private volatile boolean muted;
    /** Volume of the settings last applied: a change there is applied to the running game. */
    private volatile int settingsVolume = -1;
    private AudioOut audio;
    private long frameCount;
    private long startedAt;
    private long lastPresent;
    private long lastCast;
    private volatile Presence presence;
    private volatile Presence.Sample sample;
    private long lastGhostAt;
    private String lastGhostKey;
    private String lastGhostSprite;
    private long lastSpriteAt;
    // the way walked since the last message to the others: [time, map, x, y, sprite] on each image where it changed
    private final List<Object> ghostPath = new ArrayList<>();
    private List<Object> lastPoint;
    private long lastPointAt;
    private long lastSampleAt;
    // every sprite of the player seen (walking frames, bike…): sent once to the others, then named by its id
    private final Map<String, List<Object>> sprites = new LinkedHashMap<>();
    private final Map<String, Long> spriteSentAt = new HashMap<>();
    // the player's own sprites sent to the window (3D games), by number: it redraws the player over the others behind them
    private final Map<String, Integer> selfIds = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicInteger selfCount = new java.util.concurrent.atomic.AtomicInteger();

    Session(CoreHost core, Controls controls, Path stateDir, Path captureDir, boolean statesAllowed, Bridge bridge,
            String wsToken, int liveFps, int volume) throws IOException {
        this.core = core;
        this.controls = controls;
        this.stateDir = stateDir;
        this.captureDir = captureDir;
        this.statesAllowed = statesAllowed;
        this.bridge = bridge;
        this.liveFps = Math.max(0, Math.min(10, liveFps));
        this.volume = Math.max(0, Math.min(100, volume));
        this.ws = new WsServer(wsToken, this::command, () -> {
            selfIds.clear(); // a new window knows none of the player's sprites
            sendInfo();
        });
    }

    int wsPort() {
        return ws.port();
    }

    void setTracker(Tracker t) {
        tracker = t;
    }

    /** Position of the player for the other players' games (generation 3). */
    void setPresence(Presence p) {
        presence = p;
    }

    /** Short message shown over the game in the SLT window. */
    void message(String text) {
        ws.text(Json.stringify(Json.map("t", "osd", "text", text)));
    }

    /** Pause decided by the room (leader naming a link, adventure closed, end of the Soul Link). */
    void setRoomPause(boolean paused) {
        if (roomPaused != paused) {
            roomPaused = paused;
            if (paused) {
                controls.releaseAll();
                commands.add(core::saveSram);
            }
            sendInfo();
        }
    }

    void setStatesAllowed(boolean allowed) {
        if (statesAllowed != allowed) {
            statesAllowed = allowed;
            sendInfo();
        }
    }

    void start() {
        Thread t = new Thread(this::loop, "slt-emulation");
        t.setPriority(Thread.MAX_PRIORITY - 1);
        t.start();
    }

    private void loop() {
        audio = AudioOut.open(core.sampleRate);
        if (audio != null) {
            audio.setVolume(volume / 100f);
        }
        Log.info("Lecteur : " + core.describe() + (audio == null ? " (sans son)" : ""));
        bridge.status("running", core.libraryName, ws.port());
        long frameNanos = (long) (1_000_000_000L / core.fps);
        long next = System.nanoTime();
        long frames = 0;
        startedAt = System.nanoTime();
        try {
            while (running) {
                Runnable c;
                while ((c = commands.poll()) != null) {
                    c.run();
                }
                if (core.shutdownRequested) {
                    break;
                }
                if (userPaused || roomPaused) {
                    LockSupport.parkNanos(30_000_000L);
                    next = System.nanoTime();
                    continue;
                }
                boolean fast = fastHeld || fastToggled || controls.padWantsFastForward();
                int speed = controls.fastSpeed(); // 0 = no limit
                core.fastForwarding = fast;
                core.runFrame();
                frames++;
                frameCount = frames;
                if (presence != null) {
                    samplePresence();
                }
                long now = System.nanoTime();
                // in fast-forward, show at most ~60 images per second: emulation is the bottleneck, not the screen
                if (core.frameReady() && (!fast || now - lastPresent > 15_000_000L)) {
                    lastPresent = now;
                    present();
                }
                if (liveFps > 0 && core.frameReady() && now - lastCast > 1_000_000_000L / liveFps) {
                    lastCast = now;
                    cast();
                }
                String msg = core.lastMessage;
                if (msg != null) {
                    core.lastMessage = null;
                    message(msg);
                }
                if (!fast && audio != null) {
                    audio.write(core.audio(), core.audioLength());
                    next = System.nanoTime();
                } else if (fast && speed == 0) {
                    next = System.nanoTime(); // unlimited: as fast as the computer allows
                } else {
                    next += fast ? frameNanos / Math.max(1, speed) : frameNanos;
                    long wait = next - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    } else if (wait < -200_000_000L) {
                        next = System.nanoTime();
                    }
                }
                if (frames % 300 == 0 && core.saveSram()) {
                    bridge.saved();
                }
                Tracker tr = tracker;
                if (tr != null && frames % 30 == 0) {
                    try {
                        tr.tick(core, frames);
                    } catch (RuntimeException e) {
                        Log.error("Suivi automatique en erreur, désactivé", e);
                        tracker = null;
                    }
                }
            }
        } catch (Throwable t) {
            Log.error("Le lecteur s'est arrêté sur une erreur", t);
        } finally {
            shutdown();
        }
    }

    /**
     * Sends the current image to the window: [2, width (LE16), height (LE16), map (LE16, 0xFFFF = unknown),
     * x, y (LE16 signed, position of the player in pixels), anchor x, anchor y (bottom centre of the
     * player's sprite on the screen), flags (1 = overworld, 2 = free: the other players may be drawn)],
     * then RGBA pixels. 3D games: [3, width, height, map, flags, camera x projection of this image
     * (16 LE floats, row vectors), depth of the player's feet, where sprites are drawn one texel per pixel (LE float),
     * feet x and y on the image (LE floats), rows of a sprite under the ground point it stands on (LE float),
     * number of the player's own sprite sent as {"t":"self",…} (LE16, 0 = unknown), number n of other characters
     * drawn on the image (LE16), then n times [feet x, feet y, depth of the feet (LE floats)]], then the pixels.
     * 3D flags: 1 = overworld, 2 = free, 4 = message box on the top, 8 = on the bottom.
     */
    private void present() {
        if (!ws.hasClients()) {
            return;
        }
        int w = core.frameWidth();
        int h = core.frameHeight();
        int[] px = core.pixels();
        if (w <= 0 || h <= 0 || px.length < w * h) {
            return;
        }
        Presence.Sample s = sample;
        float[] view = s == null ? null : s.view();
        float[] others = view == null || s.others() == null ? new float[0] : s.others();
        int fixed = view != null ? 8 + 4 * view.length + 2 : 14;
        int head = view != null ? fixed + 2 + 4 * others.length : fixed;
        byte[] msg = new byte[head + w * h * 4];
        msg[0] = (byte) (view != null ? 3 : 2);
        msg[1] = (byte) w;
        msg[2] = (byte) (w >> 8);
        msg[3] = (byte) h;
        msg[4] = (byte) (h >> 8);
        int map = s == null ? 0xFFFF : s.map();
        msg[5] = (byte) map;
        msg[6] = (byte) (map >> 8);
        if (view != null) {
            msg[7] = (byte) ((s.overworld() ? 1 : 0) | (s.free() ? 2 : 0) | (s.box() == 1 ? 4 : 0) | (s.box() == 2 ? 8 : 0));
            java.nio.ByteBuffer.wrap(msg, 8, 4 * view.length).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(view);
            int self = selfNumber(s);
            msg[fixed - 2] = (byte) self;
            msg[fixed - 1] = (byte) (self >> 8);
            int count = others.length / 4;
            msg[fixed] = (byte) count;
            msg[fixed + 1] = (byte) (count >> 8);
            java.nio.ByteBuffer.wrap(msg, fixed + 2, 4 * others.length).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer().put(others);
        } else if (s != null) {
            msg[7] = (byte) s.x();
            msg[8] = (byte) (s.x() >> 8);
            msg[9] = (byte) s.y();
            msg[10] = (byte) (s.y() >> 8);
            msg[11] = (byte) Math.max(0, Math.min(255, s.anchorX()));
            msg[12] = (byte) Math.max(0, Math.min(255, s.anchorY()));
            msg[13] = (byte) ((s.overworld() ? 1 : 0) | (s.free() ? 2 : 0));
        }
        int o = head;
        for (int i = 0, n = w * h; i < n; i++) {
            int p = px[i];
            msg[o] = (byte) (p >> 16);
            msg[o + 1] = (byte) (p >> 8);
            msg[o + 2] = (byte) p;
            msg[o + 3] = (byte) 0xFF;
            o += 4;
        }
        ws.frame(msg);
    }

    /** Number of the player's own sprite, sent to the window the first time; 0 while it is not the game's own drawing. */
    private int selfNumber(Presence.Sample s) {
        Presence presence = this.presence;
        String id = s.spriteId();
        if (presence == null || id == null || !presence.spriteExact()) {
            return 0;
        }
        Integer n = selfIds.get(id);
        if (n == null) {
            String png = presence.spritePng();
            if (png == null) {
                return 0;
            }
            n = selfCount.updateAndGet(c -> c >= 0xFFFF ? 1 : c + 1);
            selfIds.put(id, n);
            ws.text(Json.stringify(Json.map("t", "self", "n", (long) n, "png", png,
                    "w", (long) presence.spriteW(), "h", (long) presence.spriteH())));
        }
        return n;
    }

    /**
     * Reads the player's position on every image: the window draws the other players with it on that very
     * image. Tells the other players 10 times per second the way walked meanwhile, image by image, so that
     * they replay it as it was walked (or every 3 s at rest, at once when the state changes).
     */
    private void samplePresence() {
        Presence.Sample s;
        try {
            s = presence.sample(core);
        } catch (RuntimeException e) {
            Log.error("Présence en jeu désactivée", e);
            presence = null;
            if (tracker instanceof TrackerHost th && th.engine().reader() instanceof slt.player.track.NdsReader nds) {
                nds.fieldShown(false);
            }
            return;
        }
        sample = s;
        if (tracker instanceof TrackerHost th && th.engine().reader() instanceof slt.player.track.NdsReader nds) {
            nds.fieldShown(s != null && ("free".equals(s.status()) || "dialogue".equals(s.status())));
        }
        long now = System.currentTimeMillis();
        long previousSample = lastSampleAt;
        lastSampleAt = now;
        if (s == null) {
            return;
        }
        String sid = s.spriteId();
        if (sid != null && !sprites.containsKey(sid) && presence.spritePng() != null) {
            rememberSprite(sid, List.of(presence.spritePng(), (long) presence.spriteW(), (long) presence.spriteH()));
        }
        Presence.Pet pet = s.pet();
        if (pet != null && pet.png() != null && !sprites.containsKey(pet.spriteId())) {
            rememberSprite(pet.spriteId(), List.of(pet.png(), (long) pet.w(), (long) pet.h()));
        }
        boolean depth = presence.world3d();
        if (s.overworld()) {
            // [time, map, x, y, sprite, (3D games: z, and the Pokémon following: x, y, z, sprite)]
            List<Object> point = new ArrayList<>(List.of(now, (long) s.map(), (long) s.x(), (long) s.y(), sid == null ? "" : sid));
            if (depth) {
                point.add((long) s.z());
                if (pet != null) {
                    point.add((long) pet.x());
                    point.add((long) pet.y());
                    point.add((long) pet.z());
                    point.add(pet.spriteId());
                }
            }
            if (lastPoint == null || !point.subList(1, point.size()).equals(lastPoint.subList(1, lastPoint.size()))) {
                if (lastPoint != null && previousSample > lastPointAt + 40) {
                    // it moves again after a rest: it stood there until the previous image (no slow glide over the rest)
                    List<Object> held = new ArrayList<>(lastPoint);
                    held.set(0, previousSample);
                    ghostPath.add(held);
                }
                lastPoint = point;
                lastPointAt = now;
                ghostPath.add(point);
                if (ghostPath.size() > Ghosts.MAX_POINTS) {
                    ghostPath.subList(0, ghostPath.size() - Ghosts.MAX_POINTS).clear();
                }
            }
        }
        String key = s.overworld() + ":" + s.status();
        boolean changed = !key.equals(lastGhostKey);
        if (!changed && (ghostPath.isEmpty() || now - lastGhostAt < 100) && now - lastGhostAt < 3000) {
            return;
        }
        lastGhostKey = key;
        lastGhostAt = now;
        Map<String, Object> g = Json.map("map", (long) s.map(), "x", (long) s.x(), "y", (long) s.y(),
                "ow", s.overworld(), "st", s.status(), "sid", sid, "t", now);
        if (depth) {
            g.put("z", (long) s.z());
        }
        // the Pokémon following only travels inside the points of the way (the server relays only known fields): a
        // message at rest carries one too, so that the others keep seeing it
        if (ghostPath.isEmpty() && pet != null && lastPoint != null) {
            List<Object> rest = new ArrayList<>(lastPoint);
            rest.set(0, now);
            ghostPath.add(rest);
        }
        Map<String, Object> sprs = new LinkedHashMap<>();
        for (Object o : ghostPath) {
            List<Object> p = Json.arr(o);
            addSprite(sprs, String.valueOf(p.get(4)), now);
            if (p.size() >= 10) {
                addSprite(sprs, String.valueOf(p.get(9)), now);
            }
        }
        addSprite(sprs, sid, now);
        if (!ghostPath.isEmpty()) {
            g.put("pts", new ArrayList<>(ghostPath));
            ghostPath.clear();
        }
        if (!sprs.isEmpty()) {
            g.put("sprs", sprs);
        }
        // the current sprite on its own: for the server (players who arrive later) and older versions of SLT
        if (sid != null && ((!sid.equals(lastGhostSprite) && now - lastSpriteAt > 1000) || now - lastSpriteAt > 10_000)) {
            lastSpriteAt = now;
            g.put("spr", presence.spritePng());
            g.put("sw", (long) presence.spriteW());
            g.put("sh", (long) presence.spriteH());
            lastGhostSprite = sid;
        }
        bridge.ghost(g);
    }

    /** A sprite of the player (or of the Pokémon following) to send to the others, the oldest forgotten beyond the limit. */
    private void rememberSprite(String id, List<Object> sprite) {
        if (sprites.size() >= Ghosts.MAX_SPRITES) {
            String oldest = sprites.keySet().iterator().next();
            sprites.remove(oldest);
            spriteSentAt.remove(oldest);
        }
        sprites.put(id, sprite);
    }

    /** A sprite named in a message, joined to it if it was not sent lately. */
    private void addSprite(Map<String, Object> out, String id, long now) {
        List<Object> spr = id == null ? null : sprites.get(id);
        Long at = spriteSentAt.get(id);
        if (spr != null && !out.containsKey(id) && (at == null || now - at > 20_000)) {
            out.put(id, spr);
            spriteSentAt.put(id, now);
        }
    }

    /** Small JPEG of the game for the other players of the room. */
    private void cast() {
        if (!encoding.compareAndSet(false, true)) {
            return;
        }
        int w = core.frameWidth();
        int h = core.frameHeight();
        int[] px = core.pixels();
        if (w <= 0 || h <= 0 || px.length < w * h) {
            encoding.set(false);
            return;
        }
        int[] copy = java.util.Arrays.copyOf(px, w * h);
        encoder.execute(() -> {
            try {
                BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                img.setRGB(0, 0, w, h, copy, 0, w);
                ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(16384);
                try (MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
                    writer.setOutput(out);
                    ImageWriteParam p = writer.getDefaultWriteParam();
                    p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    p.setCompressionQuality(0.7f);
                    writer.write(null, new IIOImage(img, null, null), p);
                } finally {
                    writer.dispose();
                }
                bridge.screen(bytes.toByteArray());
            } catch (IOException | RuntimeException e) {
                // a missed image is not a problem
            } finally {
                encoding.set(false);
            }
        });
    }

    private void shutdown() {
        try {
            core.saveSram();
            core.close();
        } catch (Throwable t) {
            Log.error("Fermeture du cœur", t);
        }
        if (audio != null) {
            audio.close();
        }
        bridge.status("stopped", core.libraryName, 0);
        bridge.saved();
        bridge.flush();
        ws.text(Json.stringify(Json.map("t", "closed")));
        ws.close();
        double secs = (System.nanoTime() - startedAt) / 1e9;
        Log.info(String.format(java.util.Locale.ROOT, "Lecteur fermé : %d images en %.1f s (%.1f i/s)", frameCount, secs,
                secs > 0 ? frameCount / secs : 0));
        System.exit(0);
    }

    // ------------------------------------------------------------ commands

    /** The volume of the settings (polled every second): applied when it changes. */
    void settingsVolume(int v) {
        int value = Math.max(0, Math.min(100, v));
        if (settingsVolume == -1) {
            settingsVolume = value; // the game started with it
            return;
        }
        if (value == settingsVolume) {
            return;
        }
        settingsVolume = value;
        volume = value;
        AudioOut a = audio;
        if (a != null) {
            a.setVolume(volume / 100f);
        }
        sendInfo();
    }

    private void sendInfo() {
        ws.text(Json.stringify(Json.map("t", "info", "w", (long) core.frameWidth(), "h", (long) core.frameHeight(),
                "baseW", (long) core.baseWidth, "baseH", (long) core.baseHeight, "paused", userPaused,
                "roomPaused", roomPaused, "fast", fastToggled, "speed", (long) controls.fastSpeed(),
                "volume", (long) volume, "muted", muted, "states", statesAllowed, "core", core.libraryName)));
    }

    /** A message of the window: {"t":"keys","m":mask}, {"t":"touch",…}, {"t":"cmd","c":…}. */
    private void command(String text) {
        Map<String, Object> m = Json.parseObject(text);
        String t = Json.str(m, "t");
        if ("keys".equals(t)) {
            controls.setKeys((int) Json.num(m, "m", 0));
            return;
        }
        if ("touch".equals(t)) {
            controls.pointer((int) Json.num(m, "x", 0), (int) Json.num(m, "y", 0), Json.bool(m, "d", false));
            return;
        }
        if (!"cmd".equals(t)) {
            return;
        }
        switch (String.valueOf(m.get("c"))) {
            case "pause" -> {
                userPaused = Json.bool(m, "on", !userPaused);
                controls.releaseAll();
                if (userPaused) {
                    commands.add(core::saveSram);
                }
                sendInfo();
            }
            case "ff-hold" -> fastHeld = Json.bool(m, "on", false);
            case "ff-toggle" -> {
                fastToggled = !fastToggled;
                message(fastToggled ? "Avance rapide ▶▶ " + speedLabel(controls.fastSpeed()) : "Vitesse normale");
                sendInfo();
            }
            case "speed" -> {
                controls.setFastSpeed((int) Json.num(m, "v", 4));
                message("Avance rapide : " + speedLabel(controls.fastSpeed()));
                sendInfo();
            }
            case "volume" -> {
                volume = (int) Math.max(0, Math.min(100, Json.num(m, "v", volume)));
                muted = Json.bool(m, "muted", muted);
                AudioOut a = audio;
                if (a != null) {
                    a.setVolume(volume / 100f);
                    a.setMuted(muted);
                }
                sendInfo();
            }
            case "pad" -> controls.setPad((int) Json.num(m, "v", 0));
            case "reset" -> commands.add(() -> {
                core.saveSram();
                core.reset();
                message("Console redémarrée");
            });
            case "save-state" -> saveState((int) Json.num(m, "slot", 1));
            case "load-state" -> loadState((int) Json.num(m, "slot", 1));
            case "screenshot" -> screenshot();
            case "quit" -> running = false;
            default -> {
                // unknown command
            }
        }
    }

    static String speedLabel(int speed) {
        return speed == 0 ? "sans limite" : "×" + speed;
    }

    private void saveState(int slot) {
        if (!statesAllowed) {
            message("Sauvegardes d'état interdites par les règles de la salle");
            return;
        }
        commands.add(() -> {
            byte[] data = core.saveState();
            if (data == null) {
                message("Sauvegarde d'état impossible");
                return;
            }
            try {
                Files.createDirectories(stateDir);
                Files.write(stateDir.resolve("etat" + slot + ".state"), data);
                message("État sauvegardé (emplacement " + slot + ")");
            } catch (IOException e) {
                message("Erreur : " + e.getMessage());
            }
        });
    }

    private void loadState(int slot) {
        if (!statesAllowed) {
            message("Sauvegardes d'état interdites par les règles de la salle");
            return;
        }
        commands.add(() -> {
            Path f = stateDir.resolve("etat" + slot + ".state");
            if (!Files.isRegularFile(f)) {
                message("Emplacement " + slot + " vide");
                return;
            }
            try {
                boolean ok = core.loadState(Files.readAllBytes(f));
                message(ok ? "État chargé (emplacement " + slot + ")" : "État incompatible");
            } catch (IOException e) {
                message("Erreur : " + e.getMessage());
            }
        });
    }

    private void screenshot() {
        commands.add(() -> {
            int w = core.frameWidth();
            int h = core.frameHeight();
            if (w <= 0 || h <= 0) {
                return;
            }
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            img.setRGB(0, 0, w, h, core.pixels(), 0, w);
            try {
                Files.createDirectories(captureDir);
                Path f = captureDir.resolve("capture-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".png");
                ImageIO.write(img, "png", f.toFile());
                message("Capture enregistrée");
            } catch (IOException e) {
                message("Erreur : " + e.getMessage());
            }
        });
    }
}
