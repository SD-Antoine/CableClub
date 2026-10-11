package slt.client;

import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Settings of this SLT installation (data/reglages.json): server address,
 * session, theme, controls, live view quality, embedded server.
 */
final class Config {

    /** Keys the interface may change directly. */
    static final Set<String> UI_KEYS = Set.of("theme", "keys", "liveFps", "volume", "padIndex", "fastSpeed", "scale",
            "smooth", "sidePanel", "pauseHidden", "updateCheck");

    private final Path file;
    private final Map<String, Object> data;

    Config(Path dataDir) {
        this.file = dataDir.resolve("reglages.json");
        Map<String, Object> d = null;
        if (Files.isRegularFile(file)) {
            try {
                d = Json.parseObject(Files.readString(file));
            } catch (IOException | RuntimeException e) {
                Log.warn("Réglages illisibles, valeurs par défaut : " + e.getMessage());
            }
        }
        data = d == null ? new LinkedHashMap<>() : d;
        data.putIfAbsent("theme", "onyx");
        data.putIfAbsent("liveFps", 4L);
        data.putIfAbsent("volume", 80L);
    }

    synchronized Object get(String key) {
        return data.get(key);
    }

    synchronized String str(String key) {
        Object v = data.get(key);
        return v == null ? null : v.toString();
    }

    synchronized long num(String key, long def) {
        return Json.num(data, key, def);
    }

    synchronized void set(String key, Object value) {
        if (value == null) {
            data.remove(key);
        } else {
            data.put(key, value);
        }
        save();
    }

    /** Copy without the session secret (sent to the interface). */
    synchronized Map<String, Object> publicView() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : data.entrySet()) {
            if (!e.getKey().equals("token")) {
                m.put(e.getKey(), Json.copy(e.getValue()));
            }
        }
        return m;
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.stringify(data));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.warn("Réglages non enregistrés : " + e.getMessage());
        }
    }
}
