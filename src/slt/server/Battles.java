package slt.server;

import slt.Http;
import slt.Ids;
import slt.Json;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Battles between the players of a room (just for fun: nothing changes in
 * their games). The server does not simulate them: each player's interface
 * runs the same battle simulator (Pokémon Showdown's) with the same seed and
 * the same choices in the same order, so every screen shows the same battle.
 * The server keeps who fights whom, the two teams (taken from the players'
 * games when the challenge is accepted), the seed and the ordered list of
 * choices, and tells the members of the room each time it changes.
 */
final class Battles {

    private static final Pattern CHOICE = Pattern.compile(
            "move [1-4]( [+-]?[1-3])?|switch [1-6]|team [1-6]{1,6}|default|pass|forfeit");
    private static final int MAX_CHOICES = 2000;
    private static final int KEEP = 20;
    private static final long IDLE_MS = 60 * 60_000L;

    /** One battle. */
    static final class Battle {
        String id;
        String roomId;
        String p1;
        String p2;
        String p1Name;
        String p2Name;
        String state = "challenge";  // challenge, running, done, declined, cancelled
        String level = "real";       // "real" (the games' levels), "50", "100"
        int gen;
        List<Object> seed;
        Map<String, Object> teams = new LinkedHashMap<>();
        final List<Object> choices = new ArrayList<>();
        String winner;               // "p1", "p2", "tie"
        long createdAt;
        long at;

        Map<String, Object> view(boolean full) {
            Map<String, Object> v = Json.map("id", id, "roomId", roomId, "p1", p1, "p2", p2, "p1Name", p1Name,
                    "p2Name", p2Name, "state", state, "level", level, "gen", (long) gen, "winner", winner,
                    "createdAt", createdAt, "at", at, "choiceCount", (long) choices.size());
            if (full) {
                v.put("seed", seed);
                v.put("teams", teams);
                v.put("choices", new ArrayList<>(choices));
            }
            return v;
        }
    }

    private final Rooms rooms;
    private final Hub hub;
    private final Map<String, List<Battle>> byRoom = new ConcurrentHashMap<>();

    Battles(Rooms rooms, Hub hub) {
        this.rooms = rooms;
        this.hub = hub;
    }

    private Room room(String roomId, Accounts.User u) {
        Room r = rooms.find(roomId);
        if (r == null) {
            throw new Http.Fail(404, "Salle introuvable.");
        }
        synchronized (r) {
            if (r.member(u.id) == null) {
                throw new Http.Fail(403, "Vous ne faites pas partie de cette salle.");
            }
        }
        return r;
    }

    private Battle get(String roomId, String battleId) {
        for (Battle b : byRoom.getOrDefault(roomId, List.of())) {
            if (b.id.equals(battleId)) {
                return b;
            }
        }
        throw new Http.Fail(404, "Combat introuvable.");
    }

    /** The battles of a room, newest first (the running ones in full). */
    List<Object> list(String roomId, Accounts.User u) {
        room(roomId, u);
        List<Object> out = new ArrayList<>();
        List<Battle> list = byRoom.getOrDefault(roomId, List.of());
        synchronized (list) {
            expire(list);
            for (int i = list.size() - 1; i >= 0; i--) {
                Battle b = list.get(i);
                out.add(b.view("running".equals(b.state)));
            }
        }
        return out;
    }

    Map<String, Object> one(String roomId, String battleId, Accounts.User u) {
        room(roomId, u);
        Battle b = get(roomId, battleId);
        synchronized (b) {
            return b.view(true);
        }
    }

    /** challenge / accept / decline / cancel / choose / result. */
    Map<String, Object> act(String roomId, Accounts.User u, Map<String, Object> body) {
        Room r = room(roomId, u);
        String action = String.valueOf(Json.str(body, "action"));
        if ("challenge".equals(action)) {
            return challenge(r, u, Json.str(body, "to"), Json.str(body, "level"));
        }
        Battle b = get(roomId, Json.str(body, "battle"));
        synchronized (b) {
            switch (action) {
                case "accept" -> accept(r, b, u);
                case "decline", "cancel" -> {
                    if (!"challenge".equals(b.state) || (!u.id.equals(b.p1) && !u.id.equals(b.p2))) {
                        throw new Http.Fail(409, "Ce défi n'est plus en attente.");
                    }
                    b.state = u.id.equals(b.p2) ? "declined" : "cancelled";
                }
                case "choose" -> choose(b, u, Json.str(body, "choice"), (int) Json.num(body, "index", -1));
                case "result" -> result(r, b, u, Json.str(body, "winner"));
                default -> throw new Http.Fail(400, "Action inconnue");
            }
            b.at = System.currentTimeMillis();
            publish(r, b);
            return b.view(true);
        }
    }

    private Map<String, Object> challenge(Room r, Accounts.User u, String to, String level) {
        Room.Member other;
        String name;
        int gen;
        synchronized (r) {
            if (!"running".equals(r.state)) {
                throw new Http.Fail(409, "Les combats se font pendant l'aventure (les équipes viennent des jeux).");
            }
            other = to == null ? null : r.member(to);
            if (other == null || other.userId.equals(u.id)) {
                throw new Http.Fail(400, "Choisissez un adversaire de la salle.");
            }
            name = r.member(u.id).name;
            gen = (int) Json.num(r.game, "gen", 3);
            if (team(r.member(u.id)).isEmpty()) {
                throw new Http.Fail(409, "Votre équipe n'est pas encore connue : lancez votre jeu un instant.");
            }
        }
        List<Battle> list = byRoom.computeIfAbsent(r.id, k -> new ArrayList<>());
        Battle b = new Battle();
        synchronized (list) {
            expire(list);
            for (Battle x : list) {
                boolean busy = "challenge".equals(x.state) || "running".equals(x.state);
                if (busy && (x.p1.equals(u.id) || x.p2.equals(u.id))) {
                    throw new Http.Fail(409, "Vous avez déjà un combat ou un défi en cours dans cette salle.");
                }
            }
            b.id = Ids.id(8);
            b.roomId = r.id;
            b.p1 = u.id;
            b.p1Name = name;
            b.p2 = other.userId;
            b.p2Name = other.name;
            b.gen = gen;
            b.level = "50".equals(level) || "100".equals(level) ? level : "real";
            b.createdAt = b.at = System.currentTimeMillis();
            list.add(b);
            while (list.size() > KEEP) {
                list.remove(0);
            }
        }
        synchronized (b) {
            publish(r, b);
            hub.send(b.p2, "notice", Json.map("roomId", r.id, "level", "info",
                    "text", "⚔ " + name + " vous défie en combat ! Répondez depuis la salle (onglet Combats)."));
            return b.view(true);
        }
    }

    private void accept(Room r, Battle b, Accounts.User u) {
        if (!"challenge".equals(b.state) || !u.id.equals(b.p2)) {
            throw new Http.Fail(409, "Ce défi n'est plus en attente.");
        }
        synchronized (r) {
            List<Object> t1 = team(r.member(b.p1));
            List<Object> t2 = team(r.member(b.p2));
            if (t1.isEmpty() || t2.isEmpty()) {
                throw new Http.Fail(409, "Une des deux équipes n'est pas connue : chaque joueur doit lancer son jeu un instant.");
            }
            b.teams.put("p1", t1);
            b.teams.put("p2", t2);
        }
        // Showdown's generator: four 16-bit numbers
        List<Object> seed = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            seed.add(Ids.seed() & 0xFFFF);
        }
        b.seed = seed;
        b.state = "running";
    }

    /** The Pokémon of a member that may fight: their battle data from the game, alive in the Soul Link. */
    private static List<Object> team(Room.Member m) {
        List<Object> out = new ArrayList<>();
        if (m == null) {
            return out;
        }
        List<Object> mons = Json.arr(m.live.get("team"));
        for (Object o : mons == null ? List.of() : mons) {
            Map<String, Object> mon = Json.obj(o);
            Map<String, Object> set = mon == null ? null : Json.obj(mon.get("set"));
            if (set != null && !Json.bool(mon, "dead", false) && out.size() < 6) {
                Map<String, Object> s = new LinkedHashMap<>(set);
                s.put("speciesName", Json.str(mon, "speciesName"));
                out.add(s);
            }
        }
        return out;
    }

    private void choose(Battle b, Accounts.User u, String choice, int index) {
        if (!"running".equals(b.state)) {
            throw new Http.Fail(409, "Ce combat est terminé.");
        }
        String side = u.id.equals(b.p1) ? "p1" : u.id.equals(b.p2) ? "p2" : null;
        if (side == null) {
            throw new Http.Fail(403, "Vous ne participez pas à ce combat.");
        }
        String c = choice == null ? "" : choice.trim();
        if (!CHOICE.matcher(c).matches()) {
            throw new Http.Fail(400, "Choix invalide.");
        }
        if (b.choices.size() >= MAX_CHOICES) {
            throw new Http.Fail(409, "Combat trop long.");
        }
        // the choice answers what this player's screen saw: a choice sent twice (double click) counts once
        if (index >= 0 && index < b.choices.size()) {
            for (int i = index; i < b.choices.size(); i++) {
                Map<String, Object> x = Json.obj(b.choices.get(i));
                if (side.equals(Json.str(x, "side"))) {
                    return;
                }
            }
        }
        b.choices.add(Json.map("side", side, "choice", c, "at", System.currentTimeMillis()));
    }

    private void result(Room r, Battle b, Accounts.User u, String winner) {
        if (!u.id.equals(b.p1) && !u.id.equals(b.p2)) {
            throw new Http.Fail(403, "Vous ne participez pas à ce combat.");
        }
        if (!"running".equals(b.state)) {
            return;
        }
        b.winner = "p1".equals(winner) || "p2".equals(winner) ? winner : "tie";
        b.state = "done";
        String text = "tie".equals(b.winner) ? "⚔ Combat entre " + b.p1Name + " et " + b.p2Name + " : égalité."
                : "⚔ Combat : " + ("p1".equals(b.winner) ? b.p1Name + " bat " + b.p2Name : b.p2Name + " bat " + b.p1Name) + ".";
        rooms.journal(r, "duel", text);
    }

    /** Challenges left unanswered and battles nobody plays any more end by themselves. */
    private static void expire(List<Battle> list) {
        long now = System.currentTimeMillis();
        for (Battle b : list) {
            synchronized (b) {
                if (("challenge".equals(b.state) || "running".equals(b.state)) && now - b.at > IDLE_MS) {
                    b.state = "cancelled";
                }
            }
        }
    }

    private void publish(Room r, Battle b) {
        Collection<String> who = new ArrayList<>();
        synchronized (r) {
            for (Room.Member m : r.members) {
                who.add(m.userId);
            }
        }
        hub.send(who, "battle", b.view(true));
    }

    /** A room was deleted. */
    void roomDeleted(String roomId) {
        byRoom.remove(roomId);
    }
}
