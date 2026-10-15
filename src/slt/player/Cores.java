package slt.player;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The emulator cores of the PC: mGBA for the Game Boy, Color and Advance, DeSmuME for the DS (libretro DLLs).
 * The Android version has its own class of this name (other files, another DS core).
 */
final class Cores {

    private Cores() {
    }

    /** The core of a ROM, in the folder of the cores. */
    static Path forRom(Path rom, Path coresDir) {
        String name = rom.getFileName().toString().toLowerCase(Locale.ROOT);
        String dll = name.endsWith(".nds") ? "desmume_libretro.dll" : "mgba_libretro.dll";
        Path p = coresDir.resolve(dll);
        if (!Files.isRegularFile(p)) {
            throw new IllegalStateException("Émulateur intégré manquant : " + p);
        }
        return p;
    }

    /** Defaults that suit Cable Club better than the cores' own defaults. */
    static Map<String, String> overrides(Path core) {
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
}
