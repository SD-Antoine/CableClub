package slt.server;

import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What the moderators decided about the runs (point 3e): a run flagged by the checks is « cleared » (it enters the
 * leaderboards) or « removed »; a run any player reported may be removed too. Kept in parties-verifiees.json, by
 * room and player.
 */
final class RunReviews {

    static final String CLEARED = "cleared";
    static final String REMOVED = "removed";

    /**
     * One decision (point 9 of the follow-up of the audit of 2026-11-10): typed reading of the map stored in the file,
     * kept as it was read and written back as it is.
     */
    static final class Verdict {
        private final Map<String, Object> raw;

        Verdict(Map<String, Object> raw) {
            this.raw = raw == null ? new LinkedHashMap<>() : raw;
        }

        static Verdict of(String verdict, String by, long at) {
            return new Verdict(Json.map("verdict", verdict, "by", by, "at", at));
        }

        /** CLEARED or REMOVED. */
        String verdict() {
            return Json.str(raw, "verdict");
        }

        Map<String, Object> toJson() {
            return raw;
        }
    }

    private final Path file;
    private final Map<String, Verdict> verdicts = new LinkedHashMap<>();

    RunReviews(Path base) {
        this.file = base.resolve("parties-verifiees.json");
        if (Files.isRegularFile(file)) {
            try {
                Map<String, Object> m = Json.obj(Json.parseObject(Files.readString(file, StandardCharsets.UTF_8)).get("runs"));
                if (m != null) {
                    m.forEach((k, v) -> verdicts.put(k, new Verdict(Json.obj(v))));
                }
            } catch (IOException | RuntimeException e) {
                Log.warn("Serveur : vérifications des parties illisibles : " + e.getMessage());
            }
        }
    }

    private static String key(String roomId, String userId) {
        return roomId + "/" + userId;
    }

    /** The decision about this run: CLEARED, REMOVED, or null (none yet). */
    synchronized String verdict(String roomId, String userId) {
        Verdict v = verdicts.get(key(roomId, userId));
        return v == null ? null : v.verdict();
    }

    synchronized void decide(String roomId, String userId, String verdict, Accounts.User moderator) {
        if (!CLEARED.equals(verdict) && !REMOVED.equals(verdict)) {
            throw new slt.Http.Fail(400, "Décision inconnue (cleared ou removed).");
        }
        verdicts.put(key(roomId, userId), Verdict.of(verdict, moderator.id, System.currentTimeMillis()));
        save();
        Log.info("Serveur : partie " + key(roomId, userId) + " " + (CLEARED.equals(verdict) ? "vérifiée" : "retirée des classements")
                + " par " + moderator.username);
    }

    /** An account is deleted: the decisions about its runs go too. */
    synchronized void forget(String userId) {
        if (verdicts.keySet().removeIf(k -> k.endsWith("/" + userId))) {
            save();
        }
    }

    private void save() {
        Map<String, Object> runs = new LinkedHashMap<>();
        verdicts.forEach((k, v) -> runs.put(k, v.toJson()));
        Accounts.write(file, Json.stringify(Json.map("version", 1L, "runs", runs)));
    }
}
