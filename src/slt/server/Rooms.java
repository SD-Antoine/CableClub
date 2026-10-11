package slt.server;

import slt.Http;
import slt.Ids;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * All the rooms of the server: lobby (invitations, ROM status, ready,
 * launch), the running Soul Link (events, naming, directives), the journal,
 * the live screens and the cloud copy of each player's save.
 */
public final class Rooms {

    private static final Pattern GAME_ID = Pattern.compile("[a-z0-9]{2,16}");
    private static final int MAX_SCREEN = 400_000;
    private static final int MAX_SAVE = 4 * 1024 * 1024;

    private record Screen(long seq, byte[] jpeg, long at) {
    }

    private final Path dir;
    private final Path logDir;
    private final Path saveDir;
    private final Accounts accounts;
    private final Hub hub;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, Screen> screens = new ConcurrentHashMap<>();

    Rooms(Path base, Accounts accounts, Hub hub) throws IOException {
        this.dir = base.resolve("salles");
        this.logDir = base.resolve("journaux");
        this.saveDir = base.resolve("sauvegardes");
        this.accounts = accounts;
        this.hub = hub;
        Files.createDirectories(dir);
        Files.createDirectories(logDir);
        Files.createDirectories(saveDir);
        load();
    }

    // ------------------------------------------------------------- queries

    Room get(String id) {
        Room r = id == null ? null : rooms.get(id);
        if (r == null) {
            throw new Http.Fail(404, "Salle introuvable.");
        }
        return r;
    }

    Room byCode(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        for (Room r : rooms.values()) {
            if (c.equals(r.code)) {
                return r;
            }
        }
        throw new Http.Fail(404, "Aucune salle avec ce code.");
    }

    private Room memberRoom(String id, Accounts.User user) {
        Room r = get(id);
        if (r.member(user.id) == null) {
            throw new Http.Fail(403, "Vous ne faites pas partie de cette salle.");
        }
        return r;
    }

    private static void leaderOnly(Room r, Accounts.User user) {
        if (!r.isLeader(user.id)) {
            throw new Http.Fail(403, "Seul le chef de salle peut faire cela.");
        }
    }

    /** Rooms the user plays in or is invited to (active ones). */
    List<Object> mine(Accounts.User user) {
        List<Object> out = new ArrayList<>();
        for (Room r : rooms.values()) {
            synchronized (r) {
                boolean member = r.member(user.id) != null;
                boolean invited = r.invites.contains(user.id);
                if ((member && ("lobby".equals(r.state) || "running".equals(r.state))) || (invited && "lobby".equals(r.state))) {
                    Map<String, Object> v = r.view(hub, false);
                    v.put("invited", invited && !member);
                    if (invited && !member) {
                        Accounts.User leader = accounts.get(r.leaderId);
                        v.put("leaderName", leader == null ? "?" : leader.display);
                    }
                    out.add(v);
                }
            }
        }
        out.sort((a, b) -> Long.compare(Json.num(Json.obj(b), "createdAt", 0), Json.num(Json.obj(a), "createdAt", 0)));
        return out;
    }

    /** Every Soul Link the user took part in (history). */
    List<Object> history(Accounts.User user) {
        List<Object> out = new ArrayList<>();
        for (Room r : rooms.values()) {
            synchronized (r) {
                if (!r.history.contains(user.id) || "lobby".equals(r.state)) {
                    continue;
                }
                Map<String, Object> v = r.view(hub, false);
                v.put("summary", r.ended != null ? r.ended.get("summary") : r.summary());
                out.add(v);
            }
        }
        out.sort((a, b) -> Long.compare(Json.num(Json.obj(b), "launchedAt", 0), Json.num(Json.obj(a), "launchedAt", 0)));
        return out;
    }

    Map<String, Object> view(String id, Accounts.User user) {
        Room r = get(id);
        synchronized (r) {
            boolean member = r.member(user.id) != null;
            if (!member && !r.invites.contains(user.id) && !r.history.contains(user.id)) {
                throw new Http.Fail(403, "Vous ne faites pas partie de cette salle.");
            }
            Map<String, Object> v = r.view(hub, member || r.history.contains(user.id));
            if (member) {
                v.put("directives", r.directives(r.member(user.id)));
            }
            return v;
        }
    }

    // --------------------------------------------------------------- lobby

    Map<String, Object> create(Accounts.User user, Map<String, Object> body) {
        Map<String, Object> game = Json.obj(body.get("game"));
        String gameId = Json.str(game, "gameId");
        if (gameId == null || !GAME_ID.matcher(gameId).matches()) {
            throw new Http.Fail(400, "Choisissez le jeu de la salle.");
        }
        Room r = new Room();
        r.id = Ids.id(10);
        r.code = Ids.code(6);
        r.name = cleanName(Json.str(body, "name"), user.display + " — " + Json.str(game, "name"));
        r.leaderId = user.id;
        r.createdAt = System.currentTimeMillis();
        r.game = Json.map("gameId", gameId, "name", Json.str(game, "name"), "gen", Json.num(game, "gen", 0),
                "platform", Json.str(game, "platform"), "romCode", Json.str(game, "romCode"), "lang", Json.str(game, "lang"),
                "crc", Json.str(game, "crc"));
        r.maxPlayers = (int) Math.max(1, Math.min(4, Json.num(body, "maxPlayers", 4)));
        Map<String, Object> rules = Json.obj(body.get("rules"));
        if (rules != null) {
            r.rules.putAll(rules);
        }
        Map<String, Object> rando = Json.obj(body.get("rando"));
        r.rando = rando == null ? new LinkedHashMap<>() : rando;
        if (Json.num(r.rando, "seed", -1) < 0) {
            r.rando.put("seed", String.valueOf(Ids.seed()));
        } else {
            r.rando.put("seed", String.valueOf(Json.num(r.rando, "seed", 0)));
        }
        Room.Member m = newMember(user, 1);
        r.members.add(m);
        r.history.add(user.id);
        rooms.put(r.id, r);
        save(r);
        Log.info("Serveur : salle " + r.id + " créée par " + user.username + " (" + gameId + ")");
        return r.view(hub, true);
    }

    private static Room.Member newMember(Accounts.User user, int slot) {
        Room.Member m = new Room.Member();
        m.userId = user.id;
        m.name = user.display;
        m.joinedAt = System.currentTimeMillis();
        m.slot = slot;
        m.rom.put("status", "missing");
        return m;
    }

    private static String cleanName(String name, String fallback) {
        String n = name == null ? "" : name.replaceAll("[\\p{Cntrl}<>]", "").trim();
        if (n.isEmpty()) {
            n = fallback;
        }
        return n.length() > 48 ? n.substring(0, 48) : n;
    }

    void settings(String id, Accounts.User user, Map<String, Object> body) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            leaderOnly(r, user);
            Room.Out out = new Room.Out();
            if (body.containsKey("name")) {
                r.name = cleanName(Json.str(body, "name"), r.name);
            }
            Map<String, Object> rules = Json.obj(body.get("rules"));
            if (rules != null) {
                List<String> changed = new ArrayList<>();
                for (Map.Entry<String, Object> e : rules.entrySet()) {
                    if (!Room.defaultRules().containsKey(e.getKey())) {
                        continue;
                    }
                    Object before = r.rules.get(e.getKey());
                    if (before == null ? e.getValue() != null : !before.equals(e.getValue())) {
                        r.rules.put(e.getKey(), e.getValue());
                        changed.add(e.getKey());
                    }
                }
                if (!changed.isEmpty() && !"lobby".equals(r.state)) {
                    out.log(r.member(user.id), "rules", user.display + " modifie les règles : " + String.join(", ", changed), null);
                }
            }
            if (body.containsKey("maxPlayers") && "lobby".equals(r.state)) {
                r.maxPlayers = (int) Math.max(Math.max(1, r.members.size()), Math.min(4, Json.num(body, "maxPlayers", r.maxPlayers)));
            }
            Map<String, Object> rando = Json.obj(body.get("rando"));
            if (rando != null) {
                if (!"lobby".equals(r.state)) {
                    throw new Http.Fail(409, "La randomisation ne peut plus changer une fois l'aventure lancée.");
                }
                r.rando = rando;
                if (Json.num(rando, "seed", -1) < 0) {
                    r.rando.put("seed", String.valueOf(Ids.seed()));
                } else {
                    r.rando.put("seed", String.valueOf(Json.num(rando, "seed", 0)));
                }
                // every generated ROM is now outdated
                for (Room.Member m : r.members) {
                    m.rom = new LinkedHashMap<>(Map.of("status", "missing"));
                    m.ready = false;
                }
            }
            commit(r, out);
        }
    }

    void invite(String id, Accounts.User user, String targetId) {
        Room r = memberRoom(id, user);
        Accounts.User target = accounts.get(targetId);
        if (target == null) {
            throw new Http.Fail(404, "Joueur introuvable.");
        }
        synchronized (r) {
            if (!"lobby".equals(r.state)) {
                throw new Http.Fail(409, "L'aventure est déjà lancée : on ne peut plus rejoindre cette salle.");
            }
            if (r.member(target.id) != null) {
                throw new Http.Fail(409, target.display + " est déjà dans la salle.");
            }
            if (r.members.size() >= r.maxPlayers) {
                throw new Http.Fail(409, "La salle est complète.");
            }
            r.invites.add(target.id);
            commit(r, new Room.Out());
        }
        hub.send(target.id, "invite", Json.map("roomId", r.id, "roomName", r.name, "from", user.display,
                "game", r.gameName()));
    }

    Map<String, Object> join(Room r, Accounts.User user, boolean byCode) {
        synchronized (r) {
            if (r.member(user.id) != null) {
                return r.view(hub, true);
            }
            if (!byCode && !r.invites.contains(user.id)) {
                throw new Http.Fail(403, "Vous n'êtes pas invité dans cette salle.");
            }
            if (!"lobby".equals(r.state)) {
                throw new Http.Fail(409, "L'aventure est déjà lancée : on ne peut plus rejoindre cette salle.");
            }
            if (r.members.size() >= r.maxPlayers) {
                throw new Http.Fail(409, "La salle est complète.");
            }
            r.invites.remove(user.id);
            int slot = 1;
            while (true) {
                final int s = slot;
                if (r.members.stream().noneMatch(m -> m.slot == s)) {
                    break;
                }
                slot++;
            }
            r.members.add(newMember(user, slot));
            r.history.add(user.id);
            Room.Out out = new Room.Out();
            commit(r, out);
            return r.view(hub, true);
        }
    }

    void decline(String id, Accounts.User user) {
        Room r = get(id);
        synchronized (r) {
            if (r.invites.remove(user.id)) {
                commit(r, new Room.Out());
            }
        }
    }

    void leave(String id, Accounts.User user) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            if (!"lobby".equals(r.state)) {
                throw new Http.Fail(409, "L'aventure est lancée : le chef de salle peut y mettre fin depuis la salle.");
            }
            removeMember(r, user.id);
        }
    }

    void kick(String id, Accounts.User user, String targetId) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            leaderOnly(r, user);
            if (user.id.equals(targetId)) {
                throw new Http.Fail(400, "Utilisez « Supprimer la salle ».");
            }
            if (!"lobby".equals(r.state)) {
                throw new Http.Fail(409, "L'aventure est lancée.");
            }
            r.invites.remove(targetId);
            removeMember(r, targetId);
            hub.send(targetId, "kicked", Json.map("roomId", r.id, "roomName", r.name));
        }
    }

    private void removeMember(Room r, String userId) {
        Collection<String> before = audience(r);
        r.members.removeIf(m -> m.userId.equals(userId));
        r.history.remove(userId);
        if (r.members.isEmpty()) {
            rooms.remove(r.id);
            deleteFiles(r);
            hub.send(before, "room-deleted", Json.map("roomId", r.id));
            return;
        }
        if (r.isLeader(userId)) {
            r.leaderId = r.members.get(0).userId;
        }
        commit(r, new Room.Out());
        hub.send(userId, "room-deleted", Json.map("roomId", r.id));
    }

    void delete(String id, Accounts.User user) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            leaderOnly(r, user);
            if ("running".equals(r.state)) {
                throw new Http.Fail(409, "Mettez d'abord fin à l'aventure (elle restera dans l'historique).");
            }
            Collection<String> before = audience(r);
            rooms.remove(r.id);
            deleteFiles(r);
            hub.send(before, "room-deleted", Json.map("roomId", r.id));
        }
    }

    void rom(String id, Accounts.User user, Map<String, Object> body) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            Room.Member m = r.member(user.id);
            String status = Json.str(body, "status");
            Map<String, Object> rom = Json.map("status", status == null ? "missing" : status,
                    "crc", Json.str(body, "crc"), "sourceCrc", Json.str(body, "sourceCrc"), "lang", Json.str(body, "lang"),
                    "gameId", Json.str(body, "gameId"), "error", Json.str(body, "error"), "at", System.currentTimeMillis());
            m.rom = rom;
            List<Object> fam = Json.arr(body.get("families"));
            if (fam != null && fam.size() < 2000) {
                m.families = new int[fam.size()];
                for (int i = 0; i < m.families.length; i++) {
                    m.families[i] = fam.get(i) instanceof Number n ? n.intValue() : 0;
                }
            }
            if (!"ready".equals(status)) {
                m.ready = false;
            }
            commit(r, new Room.Out());
        }
    }

    void ready(String id, Accounts.User user, boolean ready) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            if (!"lobby".equals(r.state)) {
                return;
            }
            Room.Member m = r.member(user.id);
            if (ready && !"ready".equals(Json.str(m.rom, "status"))) {
                throw new Http.Fail(409, "Votre ROM n'est pas encore prête.");
            }
            m.ready = ready;
            commit(r, new Room.Out());
        }
    }

    void launch(String id, Accounts.User user) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            leaderOnly(r, user);
            if (!"lobby".equals(r.state)) {
                throw new Http.Fail(409, "L'aventure est déjà lancée.");
            }
            List<String> waiting = new ArrayList<>();
            for (Room.Member m : r.members) {
                if (!m.ready) {
                    waiting.add(m.name);
                }
            }
            if (!waiting.isEmpty()) {
                throw new Http.Fail(409, "En attente de : " + String.join(", ", waiting) + ".");
            }
            if (!Json.bool(r.rando, "perPlayer", false)) {
                String crc = null;
                for (Room.Member m : r.members) {
                    String c = Json.str(m.rom, "crc");
                    if (crc != null && c != null && !crc.equals(c)) {
                        throw new Http.Fail(409, "Les ROMs générées ne sont pas identiques (versions ou langues différentes ?). "
                                + "Chaque joueur doit utiliser la même version du jeu, ou activez « une ROM par joueur ».");
                    }
                    crc = c;
                }
            }
            r.invites.clear();
            r.state = "running";
            r.adventureOpen = true;
            r.launchedAt = System.currentTimeMillis();
            Room.Out out = new Room.Out();
            out.log(null, "launch", "L'aventure commence ! " + r.members.size() + " joueur(s) sur " + r.gameName() + ".", null);
            if (r.rule("startWithBalls")) {
                out.log(null, "info", "Le Soul Link commencera vraiment quand chaque joueur aura une Poké Ball : "
                        + "d'ici là, un Pokémon K.O. ne meurt pas.", null);
            }
            r.checkStart(out);
            commit(r, out);
        }
    }

    void adventure(String id, Accounts.User user, boolean open) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            leaderOnly(r, user);
            if (!"running".equals(r.state) || r.adventureOpen == open) {
                return;
            }
            r.adventureOpen = open;
            Room.Out out = new Room.Out();
            out.log(r.member(user.id), open ? "open" : "close", open ? "Le chef de salle ouvre l'aventure : à vos manettes !"
                    : "Le chef de salle ferme l'aventure : pause pour tout le monde.", null);
            commit(r, out);
        }
    }

    void end(String id, Accounts.User user, String outcome) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            leaderOnly(r, user);
            Room.Out out = new Room.Out();
            String o = "victory".equals(outcome) ? "victory" : "defeat".equals(outcome) ? "defeat" : "abandon";
            String reason = switch (o) {
                case "victory" -> "Victoire déclarée par le chef de salle.";
                case "defeat" -> "Défaite déclarée par le chef de salle.";
                default -> "Aventure arrêtée par le chef de salle.";
            };
            r.finish(o, reason, out);
            commit(r, out);
        }
    }

    // ------------------------------------------------------------- trades

    /** Trade actions from the interface: offer, answer, cancel. */
    void trade(String id, Accounts.User user, Map<String, Object> body) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            Room.Member m = r.member(user.id);
            Room.Out out = new Room.Out();
            switch (String.valueOf(Json.str(body, "action"))) {
                case "offer" -> r.offerTrade(m, Json.str(body, "to"), Json.str(body, "uid"), out);
                case "answer" -> r.answerTrade(m, Json.str(body, "trade"), Json.bool(body, "accept", false), Json.str(body, "uid"), out);
                case "cancel" -> r.cancelTrade(m, Json.str(body, "trade"), out);
                default -> throw new Http.Fail(400, "Action inconnue");
            }
            out.changed = true;
            commit(r, out);
        }
    }

    /** Trade data from a player's game. */
    void tradePlayer(String id, Accounts.User user, Map<String, Object> body) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            Room.Member m = r.member(user.id);
            Room.Out out = new Room.Out();
            r.tradeData(m, Json.str(body, "id"), Json.str(body, "phase"), Json.str(body, "blob"), out);
            if (out.changed || !out.logs.isEmpty()) {
                commit(r, out);
            }
        }
    }

    // ----------------------------------------------------------- gameplay

    /** Tracker events of a player's game; returns the messages to show in that game. */
    List<Object> events(String id, Accounts.User user, List<Object> events) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            Room.Member m = r.member(user.id);
            Room.Out out = new Room.Out();
            for (Object o : events) {
                Map<String, Object> ev = Json.obj(o);
                if (ev != null) {
                    r.event(m, ev, out);
                }
            }
            if (!out.changed && out.notices.isEmpty() && !out.ended) {
                // only the live view moved (team, place): no need to send the whole room
                hub.send(audience(r), "live", Json.map("roomId", r.id, "userId", m.userId, "live", m.live));
                return List.of();
            }
            commit(r, out);
            List<Map<String, Object>> mine = out.notices.get(user.id);
            return mine == null ? List.of() : new ArrayList<>(mine);
        }
    }

    void name(String id, Accounts.User user, String zone, String nickname) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            leaderOnly(r, user);
            Room.Out out = new Room.Out();
            r.name(zone, nickname, out);
            commit(r, out);
        }
    }

    void ack(String id, Accounts.User user, List<Object> refundIds) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            r.ackRefunds(r.member(user.id), refundIds == null ? List.of() : refundIds);
            commit(r, new Room.Out());
        }
    }

    /** Saves and broadcasts after a change: room state, journal lines, directives and notices. */
    private void commit(Room r, Room.Out out) {
        for (Map<String, Object> l : out.logs) {
            l.put("seq", ++r.logSeq);
            l.put("at", System.currentTimeMillis());
            appendLog(r, l);
        }
        save(r);
        Collection<String> who = audience(r);
        for (Map<String, Object> l : out.logs) {
            Map<String, Object> msg = new LinkedHashMap<>(l);
            msg.put("roomId", r.id);
            hub.send(who, "log", msg);
        }
        for (Room.Member m : r.members) {
            Map<String, Object> v = r.view(hub, true);
            v.put("directives", r.directives(m));
            hub.send(m.userId, "room", v);
        }
        for (String invited : r.invites) {
            hub.send(invited, "room", r.view(hub, false));
        }
        for (Map.Entry<String, List<Map<String, Object>>> e : out.notices.entrySet()) {
            for (Map<String, Object> n : e.getValue()) {
                Map<String, Object> msg = new LinkedHashMap<>(n);
                msg.put("roomId", r.id);
                hub.send(e.getKey(), "notice", msg);
            }
        }
    }

    private Collection<String> audience(Room r) {
        List<String> out = new ArrayList<>();
        for (Room.Member m : r.members) {
            out.add(m.userId);
        }
        out.addAll(r.invites);
        return out;
    }

    /** Presence changed: members of the rooms of this user see it. */
    void presence(String userId) {
        for (Room r : rooms.values()) {
            synchronized (r) {
                if (r.member(userId) != null && ("lobby".equals(r.state) || "running".equals(r.state))) {
                    for (Room.Member m : r.members) {
                        if (!m.userId.equals(userId)) {
                            hub.send(m.userId, "presence", Json.map("roomId", r.id, "userId", userId, "online", hub.online(userId)));
                        }
                    }
                }
            }
        }
    }

    // ----------------------------------------------------------- journal

    private void appendLog(Room r, Map<String, Object> line) {
        try {
            Files.writeString(logDir.resolve(r.id + ".jsonl"), Json.stringify(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            Log.warn("Serveur : journal non écrit : " + e.getMessage());
        }
    }

    List<Object> logs(String id, Accounts.User user, long after, int limit) {
        Room r = get(id);
        synchronized (r) {
            if (r.member(user.id) == null && !r.history.contains(user.id)) {
                throw new Http.Fail(403, "Vous ne faites pas partie de cette salle.");
            }
        }
        Path f = logDir.resolve(r.id + ".jsonl");
        List<Object> out = new ArrayList<>();
        if (!Files.isRegularFile(f)) {
            return out;
        }
        try {
            List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
            for (String line : lines) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> l = Json.parseObject(line);
                if (Json.num(l, "seq", 0) > after) {
                    out.add(l);
                }
            }
        } catch (IOException | RuntimeException e) {
            throw new Http.Fail(500, "Journal illisible : " + e.getMessage());
        }
        if (out.size() > limit) {
            return new ArrayList<>(out.subList(out.size() - limit, out.size()));
        }
        return out;
    }

    // ------------------------------------------------------ live screens

    void putScreen(String id, Accounts.User user, byte[] jpeg) {
        Room r = memberRoom(id, user);
        if (jpeg.length > MAX_SCREEN) {
            throw new Http.Fail(413, "Image trop grande");
        }
        String key = r.id + "/" + user.id;
        Screen prev = screens.get(key);
        screens.put(key, new Screen(prev == null ? 1 : prev.seq() + 1, jpeg, System.currentTimeMillis()));
    }

    /** Latest image of a player's game, or null if nothing newer than {@code since}. */
    Map<String, Object> screen(String id, Accounts.User user, String target, long since) {
        Room r = memberRoom(id, user);
        Screen s = screens.get(r.id + "/" + target);
        if (s == null || s.seq() <= since || System.currentTimeMillis() - s.at() > 20_000) {
            return null;
        }
        return Json.map("seq", s.seq(), "jpeg", s.jpeg());
    }

    // ------------------------------------------------- presence in game

    private final Map<String, Map<String, Object>> ghosts = new ConcurrentHashMap<>();

    /**
     * Where a player stands in their game (generation 3): relayed at once to
     * the other members, who draw them in their own game. The sprite travels
     * only when it changes; the server keeps the latest one for newcomers.
     */
    void ghost(String id, Accounts.User user, Map<String, Object> body) {
        Room r = memberRoom(id, user);
        String key = r.id + "/" + user.id;
        Map<String, Object> g = new LinkedHashMap<>();
        for (String k : List.of("map", "x", "y", "ow", "st", "sid", "spr", "sw", "sh")) {
            if (body.containsKey(k)) {
                g.put(k, body.get(k));
            }
        }
        Object spr = g.get("spr");
        if (spr != null && spr.toString().length() > 20_000) {
            throw new Http.Fail(413, "Sprite trop grand");
        }
        Map<String, Object> prev = ghosts.get(key);
        Map<String, Object> stored = new LinkedHashMap<>(g);
        if (spr == null && prev != null) {
            stored.put("spr", prev.get("spr"));
            stored.put("sw", prev.get("sw"));
            stored.put("sh", prev.get("sh"));
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
    }

    /** Latest position (and sprite) of every player of the room. */
    List<Object> ghosts(String id, Accounts.User user) {
        Room r = memberRoom(id, user);
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

    // ------------------------------------------------------ cloud saves

    void putSave(String id, Accounts.User user, byte[] data, String name) {
        Room r = memberRoom(id, user);
        if (data.length == 0 || data.length > MAX_SAVE) {
            throw new Http.Fail(413, "Sauvegarde invalide");
        }
        try {
            Path d = saveDir.resolve(r.id);
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

    byte[] getSave(String id, Accounts.User user) {
        Room r = memberRoom(id, user);
        Path f = saveDir.resolve(r.id).resolve(user.id + ".sav");
        if (!Files.isRegularFile(f)) {
            return null;
        }
        try {
            return Files.readAllBytes(f);
        } catch (IOException e) {
            throw new Http.Fail(500, e.getMessage());
        }
    }

    Map<String, Object> saveInfo(String id, Accounts.User user) {
        Room r = memberRoom(id, user);
        Path f = saveDir.resolve(r.id).resolve(user.id + ".json");
        try {
            return Files.isRegularFile(f) ? Json.parseObject(Files.readString(f)) : null;
        } catch (IOException e) {
            return null;
        }
    }

    // ------------------------------------------------------- persistence

    private void save(Room r) {
        Accounts.write(dir.resolve(r.id + ".json"), Json.stringify(r.toStore()));
    }

    private void deleteFiles(Room r) {
        try {
            Files.deleteIfExists(dir.resolve(r.id + ".json"));
            Files.deleteIfExists(logDir.resolve(r.id + ".jsonl"));
            Path saves = saveDir.resolve(r.id);
            if (Files.isDirectory(saves)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(saves)) {
                    for (Path p : ds) {
                        Files.deleteIfExists(p);
                    }
                }
                Files.deleteIfExists(saves);
            }
        } catch (IOException e) {
            Log.warn("Serveur : fichiers de la salle " + r.id + " non supprimés : " + e.getMessage());
        }
        screens.keySet().removeIf(k -> k.startsWith(r.id + "/"));
        ghosts.keySet().removeIf(k -> k.startsWith(r.id + "/"));
    }

    private void load() throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                try {
                    Room r = Room.fromStore(Json.parseObject(Files.readString(p)));
                    rooms.put(r.id, r);
                } catch (IOException | RuntimeException e) {
                    Log.warn("Serveur : salle illisible " + p.getFileName() + " : " + e.getMessage());
                }
            }
        }
        Log.info("Serveur : " + rooms.size() + " salle(s) chargée(s)");
    }

    Set<String> ids() {
        return rooms.keySet();
    }
}
