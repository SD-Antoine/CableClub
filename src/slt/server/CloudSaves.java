package slt.server;

import slt.Http;
import slt.Json;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * The cloud copy of each player's save in a room, in sauvegardes/room id/ (taken out of {@link Rooms}, point 8 of
 * the follow-up of the audit of 2026-11-10). Only the player's own save: never a ROM.
 */
final class CloudSaves {

    static final int MAX_SAVE = 4 * 1024 * 1024;

    private final Path dir;

    CloudSaves(Path dir) throws IOException {
        this.dir = dir;
        Files.createDirectories(dir);
    }

    void put(Room r, Accounts.User user, byte[] data, String name) {
        if (data.length == 0 || data.length > MAX_SAVE) {
            throw new Http.Fail(413, "Sauvegarde invalide");
        }
        try {
            Path d = dir.resolve(r.id);
            Files.createDirectories(d);
            Path tmp = d.resolve(user.id + ".tmp");
            Files.write(tmp, data);
            Files.move(tmp, d.resolve(user.id + ".sav"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(d.resolve(user.id + ".json"), Json.stringify(Json.map("name", name, "size", (long) data.length,
                    "at", System.currentTimeMillis())));
        } catch (IOException e) {
            throw new Http.Fail(500, "Sauvegarde non enregistrée : " + e.getMessage());
        }
    }

    byte[] get(Room r, Accounts.User user) {
        Path f = dir.resolve(r.id).resolve(user.id + ".sav");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return Files.readAllBytes(f);
        } catch (IOException e) {
            throw new Http.Fail(500, e.getMessage());
        }
    }

    Map<String, Object> info(Room r, Accounts.User user) {
        Path f = dir.resolve(r.id).resolve(user.id + ".json");
        try {
            return Files.isRegularFile(f) ? Json.parseObject(Files.readString(f)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    /** The save of a deleted account. */
    void forget(String roomId, String userId) throws IOException {
        Files.deleteIfExists(dir.resolve(roomId).resolve(userId + ".sav"));
        Files.deleteIfExists(dir.resolve(roomId).resolve(userId + ".json"));
    }

    /** Every save of a deleted room. */
    void forgetRoom(String roomId) throws IOException {
        Path saves = dir.resolve(roomId);
        if (Files.isDirectory(saves)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(saves)) {
                for (Path p : ds) {
                    Files.deleteIfExists(p);
                }
            }
            Files.deleteIfExists(saves);
        }
    }
}
