package slt.server;

import slt.Http;
import slt.Json;
import slt.server.Room.Member;
import slt.server.Room.MonRec;
import slt.server.Room.Out;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The trades between the players of a room (taken out of {@link Room}, point 8 of the follow-up of the audit of
 * 2026-11-10): offered, accepted with a Pokémon, read in both games, swapped, done. Switched off for now
 * ({@link #ENABLED}): no new offer, a trade already running finishes, the rooms stored with one still read it.
 */
final class RoomTrades {

    /** Trades between players: switched off for now (no new offer; a trade already running finishes). */
    static final boolean ENABLED = false;

    /**
     * A trade between two players of the room: offered, then the Pokémon are read in both games
     * ("extract"), then each game puts the partner's in place of its own ("swap"), then done.
     */
    static final class Trade {
        String id;
        String from;
        String to;
        String fromUid;
        String toUid;
        String state = "offered";  // offered, extract, swap, done, cancelled
        String fromBlob;
        String toBlob;
        boolean fromDone;
        boolean toDone;
        long at;

        Map<String, Object> toJson(boolean withData) {
            Map<String, Object> m = Json.map("id", id, "from", from, "to", to, "fromUid", fromUid, "toUid", toUid,
                    "state", state, "fromDone", fromDone, "toDone", toDone, "at", at);
            if (withData) {
                m.put("fromBlob", fromBlob);
                m.put("toBlob", toBlob);
            }
            return m;
        }

        static Trade fromJson(Map<String, Object> m) {
            Trade t = new Trade();
            t.id = Json.str(m, "id");
            t.from = Json.str(m, "from");
            t.to = Json.str(m, "to");
            t.fromUid = Json.str(m, "fromUid");
            t.toUid = Json.str(m, "toUid");
            t.state = Json.str(m, "state") == null ? "cancelled" : Json.str(m, "state");
            t.fromBlob = Json.str(m, "fromBlob");
            t.toBlob = Json.str(m, "toBlob");
            t.fromDone = Json.bool(m, "fromDone", false);
            t.toDone = Json.bool(m, "toDone", false);
            t.at = Json.num(m, "at", 0);
            return t;
        }

        boolean active() {
            return !"done".equals(state) && !"cancelled".equals(state);
        }
    }

    private final Room room;
    private final Map<String, Trade> byId = new LinkedHashMap<>();

    RoomTrades(Room room) {
        this.room = room;
    }

    /** The trades running or ended in the last two minutes, as the room's view shows them. */
    List<Object> view() {
        List<Object> ts = new ArrayList<>();
        for (Trade t : byId.values()) {
            if (t.active() || System.currentTimeMillis() - t.at < 120_000) {
                ts.add(t.toJson(false));
            }
        }
        return ts;
    }

    /** The trades of a room read from its file. */
    void load(List<Object> stored) {
        if (stored == null) {
            return;
        }
        for (Object o : stored) {
            Trade t = Trade.fromJson(Json.obj(o));
            if (t.id != null) {
                byId.put(t.id, t);
            }
        }
    }

    /** The trades still running, with their data (the room's file). */
    List<Object> stored() {
        List<Object> out = new ArrayList<>();
        for (Trade t : byId.values()) {
            if (t.active()) {
                out.add(t.toJson(true));
            }
        }
        return out;
    }

    private Trade activeOf(String userId) {
        for (Trade t : byId.values()) {
            if (t.active() && (userId.equals(t.from) || userId.equals(t.to))) {
                return t;
            }
        }
        return null;
    }

    /** A Pokémon of the player that may be traded right now (alive, theirs; shiny until both beat the League). */
    private MonRec tradable(Member m, Member partner, String uid) {
        MonRec r = uid == null ? null : m.mons.get(uid);
        if (r == null || !"alive".equals(r.status) || "existing".equals(r.origin)) {
            throw new Http.Fail(409, "Ce Pokémon ne peut pas être échangé (introuvable, mort ou relâché).");
        }
        boolean league = "champion".equals(m.status) && "champion".equals(partner.status);
        if (!league && !r.shiny) {
            throw new Http.Fail(409, "Tant que la Ligue n'est pas vaincue par les deux joueurs, seuls les Pokémon chromatiques s'échangent.");
        }
        return r;
    }

    /** A player offers one of their Pokémon to a partner. */
    void offer(Member m, String toUserId, String uid, Out out) {
        if (!ENABLED) {
            throw new Http.Fail(409, "Les échanges sont désactivés pour le moment.");
        }
        if (!"running".equals(room.state)) {
            throw new Http.Fail(409, "L'aventure n'est pas en cours.");
        }
        Member to = toUserId == null ? null : room.member(toUserId);
        if (to == null || to == m) {
            throw new Http.Fail(400, "Choisissez un partenaire de la salle.");
        }
        if (activeOf(m.userId) != null || activeOf(to.userId) != null) {
            throw new Http.Fail(409, "Un échange est déjà en cours pour l'un de vous deux.");
        }
        MonRec r = tradable(m, to, uid);
        Trade t = new Trade();
        t.id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        t.from = m.userId;
        t.to = to.userId;
        t.fromUid = uid;
        t.at = System.currentTimeMillis();
        byId.put(t.id, t);
        out.log(m, "trade", m.name + " propose " + r.label() + " en échange à " + to.name, null);
        out.notice(to, "info", m.name + " vous propose un échange : " + r.label() + ". Répondez depuis la salle.");
        out.changed = true;
    }

    /** The partner accepts (with one of their Pokémon) or declines. */
    void answer(Member m, String tradeId, boolean accept, String uid, Out out) {
        Trade t = byId.get(tradeId);
        if (t == null || !"offered".equals(t.state) || !m.userId.equals(t.to)) {
            throw new Http.Fail(409, "Cette proposition n'est plus valable.");
        }
        Member from = room.member(t.from);
        if (from == null) {
            t.state = "cancelled";
            out.changed = true;
            return;
        }
        if (!accept) {
            t.state = "cancelled";
            t.at = System.currentTimeMillis();
            out.log(m, "trade", m.name + " refuse l'échange proposé par " + from.name, null);
            out.notice(from, "warn", m.name + " a refusé votre échange.");
            return;
        }
        tradable(from, m, t.fromUid);
        MonRec mine = tradable(m, from, uid);
        t.toUid = uid;
        t.state = "extract";
        t.at = System.currentTimeMillis();
        out.log(m, "trade", m.name + " accepte et propose " + mine.label() + " : échange en cours (restez hors combat)", null);
        out.notice(from, "ok", m.name + " a accepté l'échange : restez hors combat le temps du transfert.");
        out.notice(m, "ok", "Échange accepté : restez hors combat le temps du transfert.");
    }

    void cancel(Member m, String tradeId, Out out) {
        Trade t = byId.get(tradeId);
        if (t == null || !(m.userId.equals(t.from) || m.userId.equals(t.to))) {
            throw new Http.Fail(404, "Échange inconnu.");
        }
        if ("swap".equals(t.state) || !t.active()) {
            throw new Http.Fail(409, "Le transfert a commencé : il ne peut plus être annulé.");
        }
        t.state = "cancelled";
        t.at = System.currentTimeMillis();
        out.log(m, "trade", m.name + " annule l'échange", null);
    }

    /** What a game sends during a trade: the Pokémon read ("extract"), then "done". */
    void data(Member m, String tradeId, String phase, String blob, Out out) {
        Trade t = byId.get(tradeId);
        if (t == null || !(m.userId.equals(t.from) || m.userId.equals(t.to))) {
            return;
        }
        boolean isFrom = m.userId.equals(t.from);
        if ("extract".equals(phase) && "extract".equals(t.state) && blob != null && blob.length() < 4000) {
            if (isFrom) {
                t.fromBlob = blob;
            } else {
                t.toBlob = blob;
            }
            if (t.fromBlob != null && t.toBlob != null) {
                t.state = "swap";
                swapOwners(t, out);
            }
            out.changed = true;
        } else if ("done".equals(phase) && "swap".equals(t.state)) {
            if (isFrom) {
                t.fromDone = true;
            } else {
                t.toDone = true;
            }
            if (t.fromDone && t.toDone) {
                t.state = "done";
                t.at = System.currentTimeMillis();
                Member a = room.member(t.from);
                Member b = room.member(t.to);
                out.log(null, "trade", "Échange terminé entre " + (a == null ? "?" : a.name) + " et " + (b == null ? "?" : b.name) + ".", null);
            }
            out.changed = true;
        }
    }

    /**
     * Each Pokémon changes hands in the Soul Link before the games swap them, so that the new
     * Pokémon seen in each party is recognised as this trade (not as a gift to release).
     */
    private void swapOwners(Trade t, Out out) {
        Member a = room.member(t.from);
        Member b = room.member(t.to);
        if (a == null || b == null) {
            return;
        }
        MonRec ra = a.mons.get(t.fromUid);
        MonRec rb = b.mons.get(t.toUid);
        if (ra == null || rb == null) {
            return;
        }
        b.mons.put(ra.uid, received(ra, a));
        a.mons.put(rb.uid, received(rb, b));
        ra.status = "traded";
        ra.reason = "échangé avec " + b.name;
        rb.status = "traded";
        rb.reason = "échangé avec " + a.name;
        out.log(a, "trade", a.name + " donne " + ra.label() + " à " + b.name + " contre " + rb.label(), null);
    }

    /** The receiver's record of a Pokémon arriving by trade: theirs, outside any link. */
    private static MonRec received(MonRec r, Member giver) {
        MonRec x = MonRec.fromJson(r.toJson());
        x.zoneKey = null;
        x.bonus = true;
        x.origin = "trade";
        x.status = "alive";
        x.reason = "reçu de " + giver.name;
        x.at = System.currentTimeMillis();
        return x;
    }

    /** What this player's game must do for a trade in progress, or null. */
    Map<String, Object> directive(Member m) {
        Trade t = activeOf(m.userId);
        if (t == null) {
            return null;
        }
        boolean isFrom = m.userId.equals(t.from);
        String mine = isFrom ? t.fromUid : t.toUid;
        if ("extract".equals(t.state)) {
            return Json.map("id", t.id, "phase", "extract", "uid", mine);
        }
        if ("swap".equals(t.state)) {
            return Json.map("id", t.id, "phase", "swap", "uid", mine, "blob", isFrom ? t.toBlob : t.fromBlob);
        }
        return null;
    }
}
