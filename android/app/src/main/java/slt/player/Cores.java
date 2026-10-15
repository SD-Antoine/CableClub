package slt.player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The emulator cores of the phone (libretro, built for Android): mGBA for the Game Boy, Color and Advance, as on
 * the PC; melonDS for the DS, much faster than DeSmuME on a phone's processor (DeSmuME has no fast translation of
 * the DS code for ARM processors).
 */
final class Cores {

    private Cores() {
    }

    /** The core of a ROM, among the native libraries of the app. */
    static Path forRom(Path rom, Path coresDir) {
        String name = rom.getFileName().toString().toLowerCase(Locale.ROOT);
        String lib = name.endsWith(".nds") ? "libmelondsds_libretro_android.so" : "libmgba_libretro_android.so";
        Path p = coresDir.resolve(lib);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("Émulateur intégré manquant : " + p);
        }
        return p;
    }

    /** Defaults that suit Cable Club better than the cores' own defaults. */
    static Map<String, String> overrides(Path core) {
        Map<String, String> o = new HashMap<>();
        String n = core.getFileName().toString().toLowerCase(Locale.ROOT);
        if (n.contains("melonds")) {
            // the two screens one above the other, as the PC shows them; the finger is the stylus
            o.put("melonds_screen_layout", "Top/Bottom");
            o.put("melonds_touch_mode", "Touch");
            o.put("melonds_boot_directly", "Enabled");
            o.put("melonds_jit_enable", "Enabled");
            o.put("melonds_threaded_renderer", "Enabled");
            o.put("melonds_console_mode", "DS");
        } else if (n.contains("mgba")) {
            o.put("mgba_gb_model", "Autodetect");
            o.put("mgba_sgb_borders", "OFF");
        }
        return o;
    }
}
