package slt.player;

import slt.Ids;
import slt.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The game of a room, started by the application in a process of its own: the emulator core, the session
 * (image, sound, buttons), the automatic tracker, the presence in the others' games and the room's directives.
 * The same on the PC ({@link PlayerMain}) and on Android (the game's process of the app).
 */
public final class GameRun {

    private GameRun() {
    }

    /**
     * Starts the game; it then runs on its own threads, until the window says "quit" (the process then ends).
     *
     * @param a      the arguments given by the application (--rom, --cores, --saves, --api, --room…)
     * @param secret proves to the application that the requests come from its game
     * @return ends the game the way the window's "quit" does (the save is written first)
     */
    public static Runnable start(Map<String, String> a, Path data, String secret) throws Exception {
        Path rom = Path.of(require(a, "--rom")).toAbsolutePath();
        if (!Files.isRegularFile(rom)) {
            throw new IllegalArgumentException("ROM introuvable : " + rom);
        }
        if (secret == null || !a.containsKey("--api")) {
            throw new IllegalStateException("Le lecteur se lance depuis l'application Cable Club.");
        }
        Path core = a.containsKey("--core") ? Path.of(a.get("--core")) : Cores.forRom(rom, Path.of(require(a, "--cores")));
        Path saves = Path.of(a.getOrDefault("--saves", data.resolve("sauvegardes").toString()));
        Files.createDirectories(saves);
        Controls controls = new Controls(Integer.parseInt(a.getOrDefault("--pad", "0")),
                Integer.parseInt(a.getOrDefault("--speed", "4")));
        CoreHost host = new CoreHost(core, saves, data.resolve("systeme"), a.getOrDefault("--user", "Dresseur"),
                overrides(core, a), controls);
        host.load(rom);

        String wsToken = Ids.token();
        Bridge bridge = new Bridge(a.get("--api"), secret, wsToken);
        Session session = new Session(host, controls, saves.resolve("etats"), data.resolve("captures"),
                "on".equals(a.get("--states")), bridge, wsToken, Integer.parseInt(a.getOrDefault("--live-fps", "4")),
                Integer.parseInt(a.getOrDefault("--volume", "80")));
        Presence[] presence = new Presence[1];
        TrackerHost tracker = TrackerHost.create(a, rom, saves, ev -> {
            bridge.event(ev);
            if (presence[0] != null && "capture".equals(ev.get("type")) && "trade".equals(ev.get("origin"))) {
                presence[0].tradeHappened();
            }
        }, false);
        if (tracker != null) {
            tracker.setToast(text -> {
                session.message(text);
                bridge.notice("info", text);
            });
            tracker.setAck(bridge::ack);
            tracker.setTradeSink(bridge::trade);
            session.setTracker(tracker);
            // the players see each other in their games (generations 1 to 5)
            presence[0] = Presence.forReader(tracker.engine().reader(), saves, rom);
            if (presence[0] != null) {
                session.setPresence(presence[0]);
            }
        }
        session.start();

        // the room's directives: shared deaths, nicknames, refunds, pauses
        ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "slt-directives");
            t.setDaemon(true);
            return t;
        });
        poller.scheduleWithFixedDelay(() -> {
            Map<String, Object> d = bridge.directives();
            if (d != null && d.get("volume") instanceof Number v) {
                session.settingsVolume(v.intValue());
            }
            if (d == null || Json.bool(d, "unknown", false)) {
                return;
            }
            session.setRoomPause(Json.obj(d.get("pause")) != null);
            session.setStatesAllowed(Json.bool(d, "saveStates", false));
            if (tracker != null) {
                tracker.directives(d);
            }
        }, 0, 1, TimeUnit.SECONDS);
        return session::quit;
    }

    /** The core's options: the defaults that suit Cable Club, then the ones given with --options k=v,k=v (tests). */
    static Map<String, String> overrides(Path core, Map<String, String> a) {
        Map<String, String> overrides = Cores.overrides(core);
        if (a.containsKey("--options")) {
            for (String kv : a.get("--options").split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    overrides.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }
        return overrides;
    }

    /** "--key value" pairs; a key without value is "true". */
    public static Map<String, String> parse(String[] args) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--")) {
                continue;
            }
            if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                m.put(k, args[++i]);
            } else {
                m.put(k, "true");
            }
        }
        return m;
    }

    static String require(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Paramètre manquant : " + key);
        }
        return v;
    }
}
