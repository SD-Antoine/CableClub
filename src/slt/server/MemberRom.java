package slt.server;

import slt.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Where the room's ROM of a player stands (point 9 of the follow-up of the audit of 2026-11-10): what their
 * application told the server about the copy it made locally (never the ROM itself). Typed reading of the map stored
 * in the room's file, kept as it was read and written back as it is: the stored rooms never change because of this
 * type.
 */
final class MemberRom {

    private final Map<String, Object> raw;

    MemberRom(Map<String, Object> raw) {
        this.raw = raw == null ? new LinkedHashMap<>() : raw;
    }

    /** No ROM yet. */
    static MemberRom missing() {
        return new MemberRom(new LinkedHashMap<>(Map.of("status", "missing")));
    }

    /** What the application reports (the keys in this order, as the rooms have always been stored). */
    static MemberRom of(String status, String crc, String sourceCrc, String lang, String gameId, String error, long at) {
        return new MemberRom(Json.map("status", status, "crc", crc, "sourceCrc", sourceCrc, "lang", lang, "gameId", gameId,
                "error", error, "at", at));
    }

    /** "missing", "making", "ready", "error"… */
    String status() {
        return Json.str(raw, "status");
    }

    boolean ready() {
        return "ready".equals(status());
    }

    /** The CRC of the room's ROM made by the player's application (the same for everyone unless one ROM per player). */
    String crc() {
        return Json.str(raw, "crc");
    }

    /** The map stored in the room's file. */
    Map<String, Object> toJson() {
        return raw;
    }
}
