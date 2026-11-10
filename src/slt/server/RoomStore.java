package slt.server;

import slt.Json;
import slt.Log;
import slt.server.Room.Member;
import slt.server.Room.MonRec;
import slt.server.Room.Zone;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The JSON forms of a room (taken out of {@link Room}, point 8 of the follow-up of the audit of 2026-11-10): the
 * view sent to the members and the invited, and the room's file, written and read. The same keys in the same
 * order as before: the stored rooms and the golden references do not change.
 */
final class RoomStore {

    private RoomStore() {
    }

    /** Full state for the members (and a short view for invited users). */
    static Map<String, Object> view(Room room, Hub hub, boolean full) {
        List<Object> ms = new ArrayList<>();
        for (Member m : room.members) {
            Map<String, Object> x = Json.map("userId", m.userId, "name", m.name, "slot", (long) m.slot,
                    "ready", m.ready, "rom", m.rom, "status", m.status, "online", hub.online(m.userId),
                    "leader", room.isLeader(m.userId), "hasBalls", m.hasBalls, "avatar", hub.avatar(m.userId));
            if (full) {
                List<Object> mons = new ArrayList<>();
                for (MonRec r : m.mons.values()) {
                    mons.add(r.toJson());
                }
                x.put("mons", mons);
                x.put("live", m.live);
                x.put("stats", m.stats.toJson());
                x.put("refunds", (long) m.refunds.size());
                x.put("levelCap", (long) room.levelCap(m));
                x.put("hmNext", m.hmNext);
            }
            ms.add(x);
        }
        Map<String, Object> v = Json.map("id", room.id, "name", room.name, "mode", room.mode, "visibility", room.visibility,
                "leaderId", room.leaderId, "createdAt", room.createdAt,
                "launchedAt", room.launchedAt, "game", room.game, "maxPlayers", (long) room.maxPlayers, "state", room.state,
                "adventureOpen", room.adventureOpen, "members", ms, "ended", room.ended, "linkStarted", room.linkStarted);
        v.put("modeName", room.runName());
        v.put("family", room.spec().family());
        if (full) {
            v.put("code", room.code);
            v.put("rules", room.rules);
            if (room.customSpec != null) {
                v.put("modeFile", room.customSpec.toJson()); // the interface shows an imported room.mode's room.rules and objectives
                v.put("modePublic", Room.publishedMode.test(room.customSpec)); // in the gallery: the room may be public
            }
            v.put("rando", room.rando);
            List<Object> zs = new ArrayList<>();
            for (Zone z : room.zones.values()) {
                zs.add(z.toJson());
            }
            v.put("zones", zs);
            String active = room.namingQueue.peekFirst();
            Zone nz = active == null ? null : room.zones.get(active);
            v.put("naming", nz == null ? null : Json.map("zone", nz.key, "zoneName", nz.name, "queue", (long) room.namingQueue.size()));
            v.put("invites", new ArrayList<Object>(room.invites));
            v.put("trades", room.trades.view());
        }
        return v;
    }

    /** What the room's file holds. */
    static Map<String, Object> toStore(Room room) {
        List<Object> ms = new ArrayList<>();
        for (Member m : room.members) {
            ms.add(m.toStoreChecked());
        }
        List<Object> zs = new ArrayList<>();
        for (Zone z : room.zones.values()) {
            zs.add(z.toJson());
        }
        Map<String, Object> store = Json.map("version", 1L, "id", room.id, "code", room.code, "name", room.name, "mode", room.mode, "visibility", room.visibility,
                "leaderId", room.leaderId,
                "createdAt", room.createdAt, "launchedAt", room.launchedAt, "game", room.game, "maxPlayers", (long) room.maxPlayers,
                "state", room.state, "adventureOpen", room.adventureOpen, "linkStarted", room.linkStarted, "rules", room.rules, "rando", room.rando, "members", ms,
                "invites", new ArrayList<Object>(room.invites), "history", new ArrayList<Object>(room.history), "zones", zs,
                "namingQueue", new ArrayList<Object>(room.namingQueue), "ended", room.ended, "logSeq", room.logSeq,
                "refundSeq", room.refundSeq, "trades", room.trades.stored());
        if (room.customSpec != null) {
            store.put("modeFile", room.customSpec.toJson()); // the room keeps its own copy of an imported room.mode
        } else if (room.unreadMode != null) {
            store.put("modeFile", room.unreadMode); // kept as it was: a later version may read it again
        }
        return store;
    }

    /** A room read from its file (rules, mode and fields added since take their defaults). */
    static Room fromStore(Map<String, Object> m) {
        Room r = new Room();
        r.id = Json.str(m, "id");
        r.code = Json.str(m, "code");
        r.name = Json.str(m, "name");
        r.leaderId = Json.str(m, "leaderId");
        r.mode = Json.str(m, "mode") == null ? "soullink" : Json.str(m, "mode");
        Map<String, Object> modeFile = Json.obj(m.get("modeFile"));
        if (modeFile != null) {
            // read again (ModeSpec.stored: a later, stricter validator does not make the room unreadable); a mode
            // that no longer reads at all gives way to the official mode of its family, its file kept as it was
            r.customSpec = ModeSpec.stored(Json.stringify(modeFile));
            if (r.customSpec == null) {
                Map<String, Object> engine = Json.obj(modeFile.get("engine"));
                r.mode = ModeSpec.family(Json.str(engine, "links"), Json.str(engine, "encounters"), Json.str(engine, "team"));
                r.unreadMode = modeFile;
                Log.warn("Salle " + r.id + " : mode importé illisible, la salle suit le mode " + r.mode + ".");
            }
        }
        r.visibility = "public".equals(Json.str(m, "visibility")) ? "public" : "private";
        r.createdAt = Json.num(m, "createdAt", 0);
        r.launchedAt = Json.num(m, "launchedAt", 0);
        Map<String, Object> game = Json.obj(m.get("game"));
        if (game != null) {
            r.game = game;
        }
        r.maxPlayers = (int) Json.num(m, "maxPlayers", 4);
        r.state = Json.str(m, "state");
        r.adventureOpen = Json.bool(m, "adventureOpen", false);
        // rooms started before this rule existed: already under way
        r.linkStarted = Json.bool(m, "linkStarted", "running".equals(r.state) || "finished".equals(r.state));
        // the rules of the room's own mode (a rule added since takes its default), never those of another mode
        Map<String, Object> rules = r.spec().defaults();
        Map<String, Object> saved = Json.obj(m.get("rules"));
        if (saved != null) {
            for (Map.Entry<String, Object> e : saved.entrySet()) {
                if (rules.containsKey(e.getKey())) {
                    rules.put(e.getKey(), e.getValue());
                }
            }
        }
        r.rules = rules;
        Map<String, Object> rando = Json.obj(m.get("rando"));
        if (rando != null) {
            r.rando = rando;
        }
        List<Object> ms = Json.arr(m.get("members"));
        if (ms != null) {
            for (Object o : ms) {
                r.members.add(Member.fromStore(Json.obj(o)));
            }
        }
        Accounts.strings(m.get("invites"), r.invites);
        Accounts.strings(m.get("history"), r.history);
        List<Object> zs = Json.arr(m.get("zones"));
        if (zs != null) {
            for (Object o : zs) {
                Zone z = Zone.fromJson(Json.obj(o));
                r.zones.put(z.key, z);
            }
        }
        List<Object> q = Json.arr(m.get("namingQueue"));
        if (q != null) {
            for (Object o : q) {
                r.namingQueue.add(String.valueOf(o));
            }
        }
        r.ended = Json.obj(m.get("ended"));
        r.trades.load(Json.arr(m.get("trades")));
        r.logSeq = Json.num(m, "logSeq", 0);
        r.refundSeq = Json.num(m, "refundSeq", 0);
        return r;
    }
}
