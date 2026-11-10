package slt.server;

import slt.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * How a room ended (point 9 of the follow-up of the audit of 2026-11-10): typed reading of the map stored in the
 * room's file (when, outcome, reason, and the summary of each player). The map is kept as it was read and written
 * back as it is: the stored rooms never change because of this type.
 */
final class RoomEnd {

    /** One player's line of the summary. */
    record PlayerEnd(String userId, String status, long captures, long deaths, long badges, long alive) {
    }

    private final Map<String, Object> raw;

    private RoomEnd(Map<String, Object> raw) {
        this.raw = raw;
    }

    /** The end read from a room's file, null when the room did not end. */
    static RoomEnd read(Map<String, Object> raw) {
        return raw == null ? null : new RoomEnd(raw);
    }

    /** A room ending now (the keys in this order, as the rooms have always been stored). */
    static RoomEnd of(long at, String outcome, String reason, List<Object> summary) {
        return new RoomEnd(Json.map("at", at, "outcome", outcome, "reason", reason, "summary", summary));
    }

    long at() {
        return Json.num(raw, "at", 0);
    }

    /** "victory", "defeat" or "abandon". */
    String outcome() {
        return Json.str(raw, "outcome");
    }

    boolean victory() {
        return "victory".equals(outcome());
    }

    /** The summary as stored and shown (the players' lines), null if none. */
    List<Object> summary() {
        return Json.arr(raw.get("summary"));
    }

    /** The players' lines that name a player. */
    List<PlayerEnd> players() {
        List<PlayerEnd> out = new ArrayList<>();
        List<Object> summary = summary();
        if (summary == null) {
            return out;
        }
        for (Object o : summary) {
            Map<String, Object> s = Json.obj(o);
            if (s == null || Json.str(s, "userId") == null) {
                continue;
            }
            out.add(new PlayerEnd(Json.str(s, "userId"), Json.str(s, "status"), Json.num(s, "captures", 0),
                    Json.num(s, "deaths", 0), Json.num(s, "badges", 0), Json.num(s, "alive", 0)));
        }
        return out;
    }

    /** The map stored in the room's file. */
    Map<String, Object> toJson() {
        return raw;
    }
}
