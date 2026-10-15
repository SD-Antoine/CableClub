package slt.client;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * The game of a room runs in a process of its own (the emulator core keeps global state): on the PC, the same
 * CableClub.exe started with "--player". The Android version has its own class of this name.
 */
final class GameProcess {

    private final Process process;

    private GameProcess(Process process) {
        this.process = process;
    }

    /**
     * @param args   the player's arguments (see {@link slt.player.GameRun})
     * @param secret proves that the requests come from this game (travels by environment, not on the command line)
     */
    static GameProcess start(List<String> args, String secret) throws IOException {
        List<String> cmd = new ArrayList<>();
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            cmd.add(appPath);
        } else {
            cmd.add(ProcessHandle.current().info().command().orElse("java"));
            cmd.addAll(List.of("--enable-preview", "--enable-native-access=ALL-UNNAMED",
                    "-cp", System.getProperty("java.class.path"), "slt.Main"));
        }
        cmd.addAll(args);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.environment().put("SLT_PLAYER_SECRET", secret);
        return new GameProcess(pb.start());
    }

    boolean alive() {
        return process.isAlive();
    }

    void stop() {
        if (process.isAlive()) {
            process.destroy();
        }
    }

    /** Runs once the game has ended, with its exit code. */
    void onExit(IntConsumer done) {
        process.onExit().thenRun(() -> done.accept(process.exitValue()));
    }

    /** The cartridge save file of the emulator: DeSmuME keeps DS saves in a file of its own (see {@link DsSaves}). */
    static String saveName(boolean ds) {
        return ds ? "rom.dsv" : "rom.srm";
    }

    /** Folder of the libretro cores (packaged in app/cores, build/cores in development). */
    static Path coresDir() {
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
}
