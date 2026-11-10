package slt.server;

import slt.Json;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The game of a room, as its leader chose it (point 9 of the follow-up of the audit of 2026-11-10): typed reading of
 * the map stored in the room's file. The map is kept as it was read and written back as it is, keys of another
 * version included: the stored rooms never change because of this type.
 */
final class RoomGame {

    private final Map<String, Object> raw;

    RoomGame(Map<String, Object> raw) {
        this.raw = raw == null ? new LinkedHashMap<>() : raw;
    }

    /** The game of a new room (the keys in this order, as the rooms have always been stored). */
    static RoomGame of(String gameId, String name, long gen, String platform, String romCode, String lang, String crc) {
        return new RoomGame(Json.map("gameId", gameId, "name", name, "gen", gen, "platform", platform, "romCode", romCode,
                "lang", lang, "crc", crc));
    }

    /** The randomizer's id of the game ("emerald", "rubisfr"…), null if unknown. */
    String gameId() {
        return Json.str(raw, "gameId");
    }

    String name() {
        return Json.str(raw, "name");
    }

    /** The generation, 0 if unknown. */
    int gen() {
        return gen(0);
    }

    int gen(int otherwise) {
        return (int) Json.num(raw, "gen", otherwise);
    }

    /** The map stored in the room's file. */
    Map<String, Object> toJson() {
        return raw;
    }
}
