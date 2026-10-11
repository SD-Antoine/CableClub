package slt.client;

import slt.Http;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * The game file of a room, generated on this computer from the player's own
 * copy of the game: same options and same seed for everybody, so every
 * player gets the very same randomized game (checked with its CRC). The ROM
 * never travels over the network; it can be regenerated at any time.
 */
final class RoomRoms {

    private final Path root;
    private final Library library;
    private final ServerLink link;

    RoomRoms(Path dataDir, Library library, ServerLink link) {
        this.root = dataDir.resolve("salles");
        this.library = library;
        this.link = link;
    }

    Path dir(String roomId) {
        if (!roomId.matches("[a-z0-9]{4,32}")) {
            throw new Http.Fail(400, "Salle invalide");
        }
        return root.resolve(roomId);
    }

    /** Local state of the room's ROM (null when it was never generated here). */
    Map<String, Object> status(String roomId) {
        Path f = dir(roomId).resolve("rom.json");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            Map<String, Object> m = Json.parseObject(Files.readString(f));
            m.put("present", Files.isRegularFile(Path.of(Json.str(m, "path"))));
            return m;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Seed of this player's ROM: the room's seed, or a seed of its own when each player has a different ROM. */
    static long seedFor(Map<String, Object> rando, int slot) {
        long seed = Json.num(rando, "seed", 0);
        if (Json.bool(rando, "perPlayer", false)) {
            long z = seed + slot * 0x9E3779B97F4A7C15L;
            z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
            z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
            return (z ^ (z >>> 31)) & Long.MAX_VALUE;
        }
        return seed;
    }

    /** Generates (or regenerates) the ROM of a room and tells the server it is ready. */
    Map<String, Object> prepare(String roomId, Consumer<String> progress) throws IOException {
        Map<String, Object> room = Json.obj(link.call("GET", "/api/rooms/" + roomId, null));
        Map<String, Object> game = Json.obj(room.get("game"));
        String gameId = Json.str(game, "gameId");
        Map<String, Object> rando = Json.obj(room.get("rando"));
        if (rando == null) {
            rando = new LinkedHashMap<>();
        }
        int slot = 1;
        String me = Json.str(Json.obj(link.status().get("user")), "id");
        List<Object> members = Json.arr(room.get("members"));
        if (members != null) {
            for (Object o : members) {
                Map<String, Object> m = Json.obj(o);
                if (me != null && me.equals(Json.str(m, "userId"))) {
                    slot = (int) Json.num(m, "slot", 1);
                }
            }
        }
        Map<String, Object> source = library.find(gameId, Json.str(game, "crc"), Json.str(game, "lang"));
        if (source == null) {
            String msg = "Ajoutez votre ROM de " + Json.str(game, "name") + " dans « Mes ROMs ».";
            report(roomId, Json.map("status", "missing", "error", msg));
            throw new Http.Fail(409, msg);
        }
        report(roomId, Json.map("status", "building"));
        try {
            Map<String, Object> result = build(roomId, source, rando, seedFor(rando, slot), progress);
            report(roomId, Json.map("status", "ready", "crc", result.get("crc"), "sourceCrc", source.get("crc"),
                    "lang", source.get("lang"), "gameId", gameId, "families", result.get("families")));
            result.remove("families");
            return result;
        } catch (IOException | RuntimeException e) {
            report(roomId, Json.map("status", "error", "error", e.getMessage()));
            throw e;
        }
    }

    private Map<String, Object> build(String roomId, Map<String, Object> source, Map<String, Object> rando, long seed,
                                      Consumer<String> progress) throws IOException {
        Path dir = dir(roomId);
        Files.createDirectories(dir);
        String src = Json.str(source, "path");
        String ext = src.substring(src.lastIndexOf('.') + 1).toLowerCase(java.util.Locale.ROOT);
        Path out;
        if (Json.bool(rando, "enabled", true)) {
            Map<String, Object> r = Rando.randomize(src, dir.resolve("rom").toString(), rando, seed, progress);
            out = Path.of(Json.str(r, "outputPath"));
        } else {
            progress.accept("Copie du jeu original…");
            out = dir.resolve("rom." + ext);
            Files.copy(Path.of(src), out, StandardCopyOption.REPLACE_EXISTING);
        }
        Map<String, Object> patches = SltPatches.apply(out.toString(), rando, seed, progress);
        progress.accept("Vérification…");
        String crc = Library.crc32(out);
        Map<String, Object> info = Json.map("roomId", roomId, "path", out.toString(), "crc", crc,
                "sourceCrc", source.get("crc"), "sourcePath", src, "gameId", source.get("gameId"), "lang", source.get("lang"),
                "platform", source.get("platform"), "seed", String.valueOf(seed), "notes", patches.get("notes"),
                "at", System.currentTimeMillis());
        Files.writeString(dir.resolve("rom.json"), Json.stringify(info));
        Log.info("ROM de la salle " + roomId + " prête : " + out + " (CRC " + crc + ")");
        Map<String, Object> result = new LinkedHashMap<>(info);
        result.put("families", patches.get("families"));
        return result;
    }

    private void report(String roomId, Map<String, Object> body) {
        try {
            link.call("POST", "/api/rooms/" + roomId + "/rom", body);
        } catch (Http.Fail e) {
            Log.warn("État de la ROM non transmis : " + e.getMessage());
        }
    }
}
