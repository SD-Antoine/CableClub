package slt.player;

import slt.Json;
import slt.Log;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Entry point of the game player ("CableClub.exe --player …"), started by the SLT
 * application. No window of its own: the game appears inside the SLT window.
 * libretro cores keep global state, hence one process per game.
 */
public final class PlayerMain {

    private PlayerMain() {
    }

    public static void main(String[] args) {
        Map<String, String> a = GameRun.parse(args);
        Path data = Path.of(a.getOrDefault("--data", "data")).toAbsolutePath();
        Log.init(data, "lecteur.log");
        try {
            if (a.containsKey("--script")) {
                runScript(a, data);
                return;
            }
            GameRun.start(a, data, System.getenv("SLT_PLAYER_SECRET"));
        } catch (Throwable t) {
            Log.error("Impossible de lancer le jeu", t);
            System.exit(1);
        }
        // the application is gone (closed, ended): the game does not stay behind without its window
        ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> {
            Log.info("L'application Cable Club est fermée : le jeu s'arrête.");
            System.exit(0);
        }));
    }

    /** Development: drives the game from a script, headless; tracker events go to the console. */
    private static void runScript(Map<String, String> a, Path data) throws Exception {
        Path rom = Path.of(GameRun.require(a, "--rom")).toAbsolutePath();
        if (!Files.isRegularFile(rom)) {
            throw new IllegalArgumentException("ROM introuvable : " + rom);
        }
        Path core = a.containsKey("--core") ? Path.of(a.get("--core")) : Cores.forRom(rom, Path.of(GameRun.require(a, "--cores")));
        Path saves = Path.of(a.getOrDefault("--saves", data.resolve("sauvegardes").toString()));
        Files.createDirectories(saves);
        ScriptRunner.ScriptInput scriptInput = new ScriptRunner.ScriptInput();
        CoreHost host = new CoreHost(core, saves, data.resolve("systeme"), a.getOrDefault("--user", "Dresseur"),
                GameRun.overrides(core, a), scriptInput);
        host.load(rom);
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
}
