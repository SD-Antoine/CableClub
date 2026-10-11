package slt.player;

import slt.Ids;
import slt.Json;
import slt.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Entry point of the game player ("SLT.exe --player …"), started by the SLT
 * application. No window of its own: the game appears inside the SLT window.
 * libretro cores keep global state, hence one process per game.
 */
public final class PlayerMain {

    private PlayerMain() {
    }

    public static void main(String[] args) {
        Map<String, String> a = parse(args);
        Path data = Path.of(a.getOrDefault("--data", "data")).toAbsolutePath();
        Log.init(data, "lecteur.log");
        try {
            run(a, data);
        } catch (Throwable t) {
            Log.error("Impossible de lancer le jeu", t);
            System.exit(1);
        }
    }

    private static void run(Map<String, String> a, Path data) throws Exception {
        Path rom = Path.of(require(a, "--rom")).toAbsolutePath();
        if (!Files.isRegularFile(rom)) {
            throw new IllegalArgumentException("ROM introuvable : " + rom);
        }
        Path core = a.containsKey("--core") ? Path.of(a.get("--core")) : coreFor(rom, Path.of(require(a, "--cores")));
        Path saves = Path.of(a.getOrDefault("--saves", data.resolve("sauvegardes").toString()));
        Files.createDirectories(saves);
        Controls controls = new Controls(Integer.parseInt(a.getOrDefault("--pad", "0")),
                Integer.parseInt(a.getOrDefault("--speed", "4")));

        Map<String, String> overrides = overridesFor(core);
        if (a.containsKey("--options")) {
            for (String kv : a.get("--options").split(",")) {
                int eq = kv.indexOf('=');
                if (eq > 0) {
                    overrides.put(kv.substring(0, eq).trim(), kv.substring(eq + 1).trim());
                }
            }
        }
        boolean scripted = a.containsKey("--script");
        ScriptRunner.ScriptInput scriptInput = new ScriptRunner.ScriptInput();
        CoreHost host = new CoreHost(core, saves, data.resolve("systeme"), a.getOrDefault("--user", "Dresseur"), overrides,
                scripted ? scriptInput : controls);
        host.load(rom);
        if (scripted) {
            runScript(a, data, rom, saves, host, scriptInput);
            return;
        }

        String secret = System.getenv("SLT_PLAYER_SECRET");
        if (secret == null || !a.containsKey("--api")) {
            throw new IllegalStateException("Le lecteur se lance depuis l'application SLT.");
        }
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
            // the players see each other in their games (generations 1 to 3 and 5)
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
    }

    /** Development: drives the game from a script, headless; tracker events go to the console. */
    private static void runScript(Map<String, String> a, Path data, Path rom, Path saves, CoreHost host,
                                  ScriptRunner.ScriptInput scriptInput) throws Exception {
        Path out = Path.of(a.getOrDefault("--out", data.resolve("script").toString()));
        TrackerHost tracker = a.containsKey("--game") || a.containsKey("--info")
                ? TrackerHost.create(a, rom, saves, ev -> System.out.println("SUIVI " + Json.stringify(ev)), true) : null;
        if (tracker != null) {
            tracker.setToast(s -> System.out.println("ECRAN " + s));
            tracker.setAck(ids -> System.out.println("ACK " + ids));
            if (a.containsKey("--directives")) {
                tracker.directives(Json.parseObject(Files.readString(Path.of(a.get("--directives")))));
            }
        }
        new ScriptRunner(host, scriptInput, out, tracker, rom).run(Path.of(a.get("--script")));
        host.close();
        System.exit(0);
    }

    /** mGBA for Game Boy / Color / Advance, DeSmuME for the DS. */
    static Path coreFor(Path rom, Path coresDir) {
        String name = rom.getFileName().toString().toLowerCase(Locale.ROOT);
        String dll = name.endsWith(".nds") ? "desmume_libretro.dll" : "mgba_libretro.dll";
        Path p = coresDir.resolve(dll);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("Émulateur intégré manquant : " + p);
        }
        return p;
    }

    /** Defaults that suit SLT better than the cores' own defaults. */
    private static Map<String, String> overridesFor(Path core) {
        Map<String, String> o = new HashMap<>();
        String n = core.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.startsWith("desmume")) {
            // the core reads the touch screen only when "pointer" input is enabled; "touch" = absolute position
            o.put("desmume_pointer_type", "touch");
            o.put("desmume_pointer_mouse", "enabled");
            o.put("desmume_screens_layout", "top/bottom");
            o.put("desmume_cpu_mode", "jit");
            o.put("desmume_num_cores", "2");
        } else if (n.startsWith("mgba")) {
            o.put("mgba_gb_model", "Autodetect");
            o.put("mgba_sgb_borders", "OFF");
        }
        return o;
    }

    private static Map<String, String> parse(String[] args) {
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

    private static String require(Map<String, String> a, String key) {
        String v = a.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("Paramètre manquant : " + key);
        }
        return v;
    }
}
