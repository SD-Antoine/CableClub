package slt.server;

import slt.Ghosts;
import slt.Http;
import slt.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where each player stands in their game (taken out of {@link Rooms}, point 8 of the follow-up of the audit of
 * 2026-11-10): relayed to the other members, the latest position and the sprites kept for newcomers, and "in battle"
 * following what the game shows. Kept in memory, never written.
 */
final class RoomGhosts {

    private final Hub hub;
    private final Map<String, Map<String, Object>> ghosts = new ConcurrentHashMap<>();

    RoomGhosts(Hub hub) {
        this.hub = hub;
    }

    /**
     * Where a player stands in their game: relayed at once to the other
     * members, who draw them in their own game. The way walked since the
     * previous message ("pts") lets them replay it smoothly; each sprite
     * travels once ("sprs"). The server keeps the latest position and every
     * sprite for newcomers.
     */
    void put(Room r, Accounts.User user, Map<String, Object> body) {
        String key = r.id + "/" + user.id;
        Map<String, Object> g = new LinkedHashMap<>();
        for (String k : List.of("map", "x", "y", "z", "ow", "st", "sid", "spr", "sw", "sh", "t", "pts", "sprs")) {
            if (body.containsKey(k)) {
                g.put(k, body.get(k));
            }
        }
        Object spr = g.get("spr");
        if (spr != null && spr.toString().length() > 20_000) {
            throw new Http.Fail(413, "Sprite trop grand");
        }
        List<Object> pts = Json.arr(g.get("pts"));
        if (pts != null) {
            g.put("pts", Ghosts.trimPoints(pts));
        } else {
            g.remove("pts");
        }
        Map<String, Object> sprs = Json.obj(g.get("sprs"));
        if (sprs != null && (sprs.size() > Ghosts.MAX_SPRITES || Json.stringify(sprs).length() > 400_000)) {
            throw new Http.Fail(413, "Sprites trop grands");
        } else if (sprs == null) {
            g.remove("sprs");
        }
        Map<String, Object> prev = ghosts.get(key);
        Map<String, Object> stored = new LinkedHashMap<>(g);
        stored.remove("pts");
        if (spr == null && prev != null) {
            stored.put("spr", prev.get("spr"));
            stored.put("sw", prev.get("sw"));
            stored.put("sh", prev.get("sh"));
        }
        Map<String, Object> known = prev == null ? null : Json.obj(prev.get("sprs"));
        if (known != null || sprs != null) {
            Map<String, Object> all = new LinkedHashMap<>(known == null ? Map.of() : known);
            if (sprs != null) {
                all.putAll(sprs);
            }
            stored.put("sprs", Ghosts.trimSprites(all));
        }
        stored.put("at", System.currentTimeMillis());
        ghosts.put(key, stored);
        Map<String, Object> msg = new LinkedHashMap<>(g);
        msg.put("roomId", r.id);
        msg.put("userId", user.id);
        List<String> others = new ArrayList<>();
        synchronized (r) {
            for (Room.Member m : r.members) {
                if (!m.userId.equals(user.id)) {
                    others.add(m.userId);
                }
            }
        }
        hub.send(others, "ghost", msg);
        battleFromGame(r, user.id, Json.str(g, "st"));
    }

    /**
     * "In battle" in the list of players follows what the game itself shows: the encounters seen by the tracker can be
     * false (a Pokémon of the player found in the memory of a DS game, no battle), and a DS game tells nothing of
     * trainer battles. A game on the field clears the mark, a game in battle sets it.
     */
    private void battleFromGame(Room r, String userId, String state) {
        if (!"battle".equals(state) && !"free".equals(state) && !"dialogue".equals(state)) {
            return;
        }
        synchronized (r) {
            Room.Member m = r.member(userId);
            if (m == null) {
                return;
            }
            boolean marked = m.live.containsKey("battle");
            if ("battle".equals(state) == marked) {
                return;
            }
            if (marked) {
                m.live.remove("battle");
            } else {
                m.live.put("battle", "game");
            }
            hub.send(Rooms.audience(r), "live", Json.map("roomId", r.id, "userId", m.userId, "live", m.live));
        }
    }

    /** Latest position (and sprite) of every player of the room. */
    List<Object> of(Room r) {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> e : ghosts.entrySet()) {
            if (e.getKey().startsWith(r.id + "/")) {
                Map<String, Object> g = new LinkedHashMap<>(e.getValue());
                g.put("userId", e.getKey().substring(r.id.length() + 1));
                out.add(g);
            }
        }
        return out;
    }

    /** The latest position a player sent (room id/user id), or null. */
    Map<String, Object> latest(String roomId, String userId) {
        return ghosts.get(roomId + "/" + userId);
    }

    /** A player's position forgotten (deleted account). */
    void forget(String roomId, String userId) {
        ghosts.remove(roomId + "/" + userId);
    }

    /** Every position of a deleted room forgotten. */
    void forgetRoom(String roomId) {
        ghosts.keySet().removeIf(k -> k.startsWith(roomId + "/"));
    }
}
