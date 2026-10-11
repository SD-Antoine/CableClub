package slt.client;

import slt.Http;
import slt.Ids;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs the game of this player: a separate process without window (the
 * emulator core lives there), whose image and sound are shown inside the SLT
 * window. Relays what the game reports to the server and what the server
 * decides back to the game.
 */
final class Players {

    private final ClientApp app;
    private final ExecutorService relay = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "slt-relais");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean screenBusy = new AtomicBoolean();
    private volatile Process process;
    private volatile String roomId;
    private volatile Map<String, Object> state = Json.map("state", "stopped");
    private volatile String secret;
    private volatile long lastSaveUpload;
    private volatile boolean savePending;

    Players(ClientApp app) {
        this.app = app;
    }

    Map<String, Object> state() {
        return state;
    }

    boolean checkSecret(String s) {
        return secret != null && secret.equals(s);
    }

    /** Starts the game of a room (stops another game first). */
    synchronized Map<String, Object> launch(String room) throws IOException {
        Map<String, Object> rom = app.roms.status(room);
        if (rom == null || !Json.bool(rom, "present", false)) {
            throw new Http.Fail(409, "La ROM de cette salle n'est pas encore générée sur ce PC.");
        }
        Map<String, Object> d = app.link.directives(room);
        if (d != null && "lobby".equals(Json.str(d, "state"))) {
            throw new Http.Fail(409, "L'aventure n'est pas encore lancée.");
        }
        if (process != null && process.isAlive()) {
            if (room.equals(roomId)) {
                return state;
            }
            stop();
        }
        Path dir = app.roms.dir(room);
        Path saves = dir.resolve("sauvegardes");
        Files.createDirectories(saves);
        String gameId = Json.str(rom, "gameId");
        Path info = null;
        try {
            info = Rando.trackerInfo(Json.str(rom, "path"), app.dataDir.resolve("cache"));
        } catch (Exception e) {
            Log.warn("Suivi : données du jeu indisponibles (" + e.getMessage() + ")");
        }
        secret = Ids.token();
        List<String> cmd = new ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            cmd.add(appPath);
        } else {
            cmd.add(ProcessHandle.current().info().command().orElse("java"));
            cmd.addAll(List.of("--enable-preview", "--enable-native-access=ALL-UNNAMED",
                    "-cp", System.getProperty("java.class.path"), "slt.Main"));
        }
        boolean states = d != null && Json.bool(d, "saveStates", false);
        cmd.addAll(List.of("--player",
                "--rom", Json.str(rom, "path"),
                "--cores", coresDir().toString(),
                "--data", app.dataDir.toString(),
                "--saves", saves.toString(),
                "--api", app.apiBase(),
                "--room", room,
                "--states", states ? "on" : "off",
                "--live-fps", String.valueOf(app.config.num("liveFps", 4)),
                "--volume", String.valueOf(app.config.num("volume", 80)),
                "--speed", String.valueOf(app.config.num("fastSpeed", 4)),
                "--pad", String.valueOf(app.config.num("padIndex", 0))));
        if (gameId != null) {
            cmd.addAll(List.of("--game", gameId));
        }
        if (info != null) {
            cmd.addAll(List.of("--info", info.toString()));
        }
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        // the secret travels by environment, not on the command line
        pb.environment().put("SLT_PLAYER_SECRET", secret);
        Process p = pb.start();
        process = p;
        roomId = room;
        state = Json.map("state", "starting", "roomId", room, "gameId", gameId);
        app.hub.send("player", state);
        p.onExit().thenRun(() -> {
            synchronized (this) {
                if (process == p) {
                    process = null;
                    state = Json.map("state", "stopped", "roomId", room, "exitCode", (long) p.exitValue());
                    app.hub.send("player", state);
                }
            }
            uploadSave(room, true);
            try {
                app.link.call("POST", "/api/rooms/" + room + "/ghost", Json.map("st", "offline"));
            } catch (Http.Fail ignored) {
                // offline anyway
            }
        });
        Log.info("Jeu lancé pour la salle " + room + " (" + gameId + ")");
        return state;
    }

    synchronized void stop() {
        Process p = process;
        if (p != null && p.isAlive()) {
            p.destroy();
        }
    }

    /** Folder of the libretro cores (packaged in app/cores, build/cores in development). */
    Path coresDir() {
        String forced = System.getProperty("slt.cores");
        if (forced != null) {
            return Path.of(forced);
        }
        try {
            Path code = Path.of(ClientApp.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path parent = code.getParent();
            for (Path c : List.of(parent.resolve("cores"), code.resolve("cores"), parent.resolve("build").resolve("cores"))) {
                if (Files.isDirectory(c)) {
                    return c;
                }
            }
            return parent.resolve("cores");
        } catch (URISyntaxException e) {
            return Path.of("cores");
        }
    }

    // ------------------------------------------------- reports of the game

    void status(Map<String, Object> body) {
        Map<String, Object> s = Json.obj(Json.copy(body));
        s.put("roomId", roomId);
        state = s;
        app.hub.send("player", s);
    }

    /** Tracker events: forwarded in order to the server. */
    void events(List<Object> events) {
        String room = roomId;
        if (room == null || events == null || events.isEmpty()) {
            return;
        }
        relay.execute(() -> {
            try {
                app.link.call("POST", "/api/rooms/" + room + "/events", Json.map("events", events));
            } catch (Http.Fail e) {
                Log.warn("Événements non transmis au serveur : " + e.getMessage());
                app.hub.send("notice", Json.map("roomId", room, "level", "warn",
                        "text", "Serveur injoignable : un événement du jeu n'a pas été transmis."));
            }
        });
    }

    Map<String, Object> directives() {
        String room = roomId;
        Map<String, Object> d = room == null ? null : app.link.directives(room);
        return d == null ? Json.map("unknown", true) : d;
    }

    void ack(List<Object> refunds) {
        String room = roomId;
        if (room == null || refunds == null || refunds.isEmpty()) {
            return;
        }
        relay.execute(() -> {
            try {
                app.link.call("POST", "/api/rooms/" + room + "/ack", Json.map("refunds", refunds));
            } catch (Http.Fail e) {
                Log.warn("Accusé de remboursement non transmis : " + e.getMessage());
            }
        });
    }

    /** Trade data or result of this game: relayed to the room. */
    void trade(Map<String, Object> t) {
        String room = roomId;
        if (room == null || t == null) {
            return;
        }
        relay.execute(() -> {
            try {
                app.link.call("POST", "/api/rooms/" + room + "/trade-player", t);
            } catch (Http.Fail e) {
                Log.warn("Données d'échange non transmises : " + e.getMessage());
            }
        });
    }

    /** Live image of the game: sent to the server at most one at a time (older ones are dropped). */
    void screen(byte[] jpeg) {
        String room = roomId;
        if (room == null || !app.link.loggedIn() || !screenBusy.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                app.link.postBytes("/api/rooms/" + room + "/screen", jpeg);
            } finally {
                screenBusy.set(false);
            }
        });
    }

    private final Object ghostLock = new Object();
    private Map<String, Object> ghostPending;
    private boolean ghostBusy;

    /** Position of the player in the game, for the other players (only the latest one is worth sending). */
    void ghost(Map<String, Object> g) {
        String room = roomId;
        if (room == null || !app.link.loggedIn()) {
            return;
        }
        synchronized (ghostLock) {
            if (ghostBusy) {
                if (ghostPending != null && ghostPending.containsKey("spr") && !g.containsKey("spr")) {
                    g.put("spr", ghostPending.get("spr"));
                    g.put("sw", ghostPending.get("sw"));
                    g.put("sh", ghostPending.get("sh"));
                }
                ghostPending = g;
                return;
            }
            ghostBusy = true;
        }
        Thread.startVirtualThread(() -> {
            Map<String, Object> next = g;
            while (next != null) {
                try {
                    app.link.call("POST", "/api/rooms/" + room + "/ghost", next);
                } catch (Http.Fail e) {
                    // the next reading will try again
                }
                synchronized (ghostLock) {
                    next = ghostPending;
                    ghostPending = null;
                    if (next == null) {
                        ghostBusy = false;
                    }
                }
            }
        });
    }

    /** The game wrote its save: copy it to the server (at most once a minute, and when the game closes). */
    void saved() {
        savePending = true;
        String room = roomId;
        if (room != null && System.currentTimeMillis() - lastSaveUpload > 60_000) {
            Thread.startVirtualThread(() -> uploadSave(room, false));
        }
    }

    private void uploadSave(String room, boolean force) {
        if (!savePending && !force) {
            return;
        }
        Path f = app.roms.dir(room).resolve("sauvegardes").resolve("rom.srm");
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            byte[] data = Files.readAllBytes(f);
            if (app.link.postBytes("/api/rooms/" + room + "/save?name=rom.srm", data)) {
                savePending = false;
                lastSaveUpload = System.currentTimeMillis();
            }
        } catch (IOException e) {
            Log.warn("Sauvegarde non copiée sur le serveur : " + e.getMessage());
        }
    }

    /** Puts the server's copy of the save back on this PC (game closed). */
    void restoreSave(String room) throws IOException {
        if (process != null && process.isAlive() && room.equals(roomId)) {
            throw new Http.Fail(409, "Fermez d'abord le jeu.");
        }
        byte[] data = app.link.getBytes("/api/rooms/" + room + "/save");
        if (data == null) {
            throw new Http.Fail(404, "Aucune sauvegarde de cette salle sur le serveur.");
        }
        Path dir = app.roms.dir(room).resolve("sauvegardes");
        Files.createDirectories(dir);
        Path f = dir.resolve("rom.srm");
        if (Files.isRegularFile(f)) {
            Files.copy(f, dir.resolve("rom.srm.avant-restauration"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(f, data);
        Log.info("Sauvegarde restaurée depuis le serveur pour la salle " + room);
    }
}
