package slt.client;

import slt.FileBrowser;
import slt.Http;
import slt.Ids;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * The player's own game files. They are never modified: each room works on
 * its own generated copy. Recognised by the randomizer engine (game,
 * generation, language).
 */
final class Library {

    private final Path file;
    private final List<Map<String, Object>> roms = new ArrayList<>();

    Library(Path dataDir) {
        this.file = dataDir.resolve("bibliotheque.json");
        if (Files.isRegularFile(file)) {
            try {
                List<Object> list = Json.arr(Json.parseObject(Files.readString(file)).get("roms"));
                if (list != null) {
                    for (Object o : list) {
                        roms.add(Json.obj(o));
                    }
                }
            } catch (IOException | RuntimeException e) {
                Log.warn("Bibliothèque illisible : " + e.getMessage());
            }
        }
    }

    synchronized List<Object> list() {
        List<Object> out = new ArrayList<>();
        for (Map<String, Object> r : roms) {
            Map<String, Object> x = Json.obj(Json.copy(r));
            x.put("present", Files.isRegularFile(Path.of(Json.str(r, "path"))));
            out.add(x);
        }
        return out;
    }

    /** Adds one ROM file (already known files are refreshed). */
    Map<String, Object> add(Path path) throws IOException {
        Path p = path.toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) {
            throw new Http.Fail(404, "Fichier introuvable : " + p);
        }
        Map<String, Object> info = Rando.inspect(p.toString());
        String gameId = Json.str(info, "gameId");
        if (gameId == null) {
            throw new Http.Fail(400, "Jeu non pris en charge : " + Json.str(info, "romName"));
        }
        String crc = crc32(p);
        Map<String, Object> entry = Json.map("id", Ids.id(8), "path", p.toString(), "file", p.getFileName().toString(),
                "size", Files.size(p), "crc", crc, "gameId", gameId, "gen", info.get("gen"), "lang", info.get("lang"),
                "platform", info.get("platform"), "romName", info.get("romName"), "romCode", info.get("romCode"),
                "addedAt", System.currentTimeMillis());
        synchronized (this) {
            for (int i = 0; i < roms.size(); i++) {
                Map<String, Object> r = roms.get(i);
                if (crc.equals(Json.str(r, "crc")) || p.toString().equalsIgnoreCase(Json.str(r, "path"))) {
                    entry.put("id", r.get("id"));
                    roms.set(i, entry);
                    save();
                    return entry;
                }
            }
            roms.add(entry);
            save();
        }
        return entry;
    }

    /** Adds every ROM of a folder (and sub-folders). */
    Map<String, Object> scan(Path dir, Consumer<String> progress) throws IOException {
        List<Path> files = FileBrowser.findRoms(dir, progress);
        int ok = 0;
        List<Object> skipped = new ArrayList<>();
        int i = 0;
        for (Path f : files) {
            i++;
            progress.accept("Lecture " + i + "/" + files.size() + " : " + f.getFileName());
            try {
                add(f);
                ok++;
            } catch (Http.Fail e) {
                skipped.add(Json.map("file", f.getFileName().toString(), "reason", e.getMessage()));
            } catch (RuntimeException e) {
                skipped.add(Json.map("file", f.getFileName().toString(), "reason", String.valueOf(e.getMessage())));
            }
        }
        return Json.map("added", (long) ok, "found", (long) files.size(), "skipped", skipped);
    }

    synchronized void remove(String id) {
        if (roms.removeIf(r -> id.equals(Json.str(r, "id")))) {
            save();
        }
    }

    /**
     * Best file for a game: same original file as the room (CRC), then same
     * language, then any copy of the game.
     */
    synchronized Map<String, Object> find(String gameId, String crc, String lang) {
        Map<String, Object> best = null;
        int bestScore = -1;
        for (Map<String, Object> r : roms) {
            if (!gameId.equals(Json.str(r, "gameId")) || !Files.isRegularFile(Path.of(Json.str(r, "path")))) {
                continue;
            }
            int score = 0;
            if (crc != null && crc.equalsIgnoreCase(Json.str(r, "crc"))) {
                score += 10;
            }
            if (lang != null && lang.equals(Json.str(r, "lang"))) {
                score += 5;
            }
            if ("fr".equals(Json.str(r, "lang"))) {
                score += 1;
            }
            if (score > bestScore) {
                best = r;
                bestScore = score;
            }
        }
        return best;
    }

    synchronized Map<String, Object> get(String id) {
        for (Map<String, Object> r : roms) {
            if (id.equals(Json.str(r, "id"))) {
                return r;
            }
        }
        return null;
    }

    static String crc32(Path p) throws IOException {
        CRC32 c = new CRC32();
        byte[] buf = new byte[1 << 16];
        try (InputStream in = Files.newInputStream(p)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                c.update(buf, 0, n);
            }
        }
        return String.format(Locale.ROOT, "%08X", c.getValue());
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, Json.stringify(Json.map("version", 1L, "roms", roms)));
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            Log.warn("Bibliothèque non enregistrée : " + e.getMessage());
        }
    }
}
