package slt.server;

import slt.Ghosts;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
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
    /** Rooms a player may lead that are not over (lobby or running): each one is a file on the server's disk. */
    static final int MAX_OPEN_ROOMS = 30;
    /** Rooms a player may create in a window of time. */
    static final int MAX_CREATIONS = 10;
    static final long CREATION_WINDOW_MS = 10 * 60_000L;

    private record Screen(long seq, byte[] jpeg, long at) {
    }

    private final Path dir;
    private final Path logDir;
    private final Path saveDir;
    private final Accounts accounts;
    private final Hub hub;
    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final Map<String, Screen> screens = new ConcurrentHashMap<>();
    /**
     * Finished rooms not used lately, kept as a summary only (point 6b of the follow-up of the audit of 2026-11-10):
     * a finished run weighs about 265 KB in memory, and nobody plays in it any more. Its file is read again when it
     * is asked for ({@link #find}), and it goes back to sleep {@link #SLEEP_AFTER_MS} after its last use. Its results
     * stay here for the statistics and the leaderboards. A room is in rooms or here (both an instant while it moves:
     * then rooms counts).
     */
    record Asleep(String id, String code, String outcome, Set<String> history, Set<String> members, Set<String> invites,
                  List<Records.Run> runs, Map<String, Object> card) {
    }

    private final Map<String, Asleep> asleep = new ConcurrentHashMap<>();
    /** Finished rooms awake: their last use (ms), from the first sweep after they ended. */
    private final Map<String, Long> used = new ConcurrentHashMap<>();
    static final long SLEEP_AFTER_MS = 30 * 60_000L;
    /** Taken after the lock of a room, never before: a room is woken without holding any other. */
    private final Object wakeLock = new Object();

    /** When the image of a player's game was last asked for by another member (room id/user id → ms). */
    final Map<String, Long> watched = new ConcurrentHashMap<>();
    /** An image asked for in this time is watched: the interface asks 4 times a second while it shows it. */
    static final long WATCH_MS = 3_000;
    final Map<String, Deque<Long>> creations = new ConcurrentHashMap<>();
    ModeGallery gallery; // the public modes (set by the server; null: none)   // by user: when they created a room
    /** A room was deleted (its chat goes with it). */
    volatile java.util.function.Consumer<String> onDeleted = id -> { };

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
        Room r = find(id);
        if (r == null) {
            throw new Http.Fail(404, "Salle introuvable.");
        }
        return r;
    }

    /** A room, or null; a finished room asleep is read again from its file. */
    Room find(String id) {
        if (id == null) {
            return null;
        }
        Room r = rooms.get(id);
        if (r == null) {
            return asleep.containsKey(id) ? wake(id) : null;
        }
        used.computeIfPresent(id, (k, t) -> System.currentTimeMillis());
        return r;
    }

    private static boolean over(Room r) {
        return !"lobby".equals(r.state) && !"running".equals(r.state);
    }

    /** What a finished room keeps asleep: its players, its results and its card in the history (a few KB). */
    private Asleep summary(Room r) {
        Set<String> members = new java.util.LinkedHashSet<>();
        for (Room.Member m : r.members) {
            members.add(m.userId);
        }
        return new Asleep(r.id, r.code, r.ended == null ? null : Json.str(r.ended, "outcome"), Set.copyOf(r.history),
                Set.copyOf(members), Set.copyOf(r.invites), List.copyOf(Records.of(r)),
                Json.obj(Json.copy(historyCard(r)))); // a copy: nothing of the room is kept
    }

    /** A room as the history shows it. */
    private Map<String, Object> historyCard(Room r) {
        Map<String, Object> v = r.view(hub, false);
        v.put("summary", r.ended != null ? r.ended.get("summary") : r.summary());
        return v;
    }

    /** A finished room read from its file, kept awake (its last use: now). Null if it cannot be read. */
    private Room wake(String id) {
        synchronized (wakeLock) {
            Room r = rooms.get(id);
            if (r != null || !asleep.containsKey(id)) {
                return r;
            }
            r = read(id);
            if (r == null) {
                return null;
            }
            used.put(id, System.currentTimeMillis());
            rooms.put(id, r);
            asleep.remove(id);
            return r;
        }
    }

    /** A room as its file holds it (not kept). */
    private Room read(String id) {
        try {
            return Room.fromStore(Json.parseObject(Files.readString(dir.resolve(id + ".json"))));
        } catch (IOException | RuntimeException e) {
            Log.warn("Serveur : salle illisible " + id + " : " + e.getMessage());
            return null;
        }
    }

    /** Whether a room is asleep (for the tests). */
    boolean isAsleep(String id) {
        return asleep.containsKey(id) && !rooms.containsKey(id);
    }

    /**
     * Puts to sleep the finished rooms not used for {@link #SLEEP_AFTER_MS}: called every few minutes by the server.
     * A room that just ended starts its time at the first call.
     */
    void sweep(long now) {
        for (Room r : new ArrayList<>(rooms.values())) {
            synchronized (r) {
                if (!over(r)) {
                    used.remove(r.id);
                    continue;
                }
                Long t = used.putIfAbsent(r.id, now);
                if (t == null || now - t < SLEEP_AFTER_MS) {
                    continue;
                }
                synchronized (wakeLock) {
                    if (rooms.get(r.id) != r) {
                        continue;
                    }
                    asleep.put(r.id, summary(r));
                    rooms.remove(r.id);
                    used.remove(r.id);
                }
            }
        }
    }

    /** Rooms this user plays in that are not over (lobby or running). */
    List<Room> activeOf(String userId) {
        List<Room> out = new ArrayList<>();
        for (Room r : rooms.values()) {
            synchronized (r) {
                if (r.member(userId) != null && ("lobby".equals(r.state) || "running".equals(r.state))) {
                    out.add(r);
                }
            }
        }
        out.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    /**
     * Public rooms still in their lobby, newest first: optionally a game mode,
     * words of the name (or of the game, or of the leader), and the number of
     * players of the room (its size).
     */
    List<Object> publicRooms(Accounts.User user, String mode, String query, int size, boolean withFull) {
        String q = query == null ? "" : query.trim().toLowerCase(java.util.Locale.ROOT);
        List<Room> found = new ArrayList<>();
        for (Room r : rooms.values()) {
            synchronized (r) {
                if (!"public".equals(r.visibility) || !"lobby".equals(r.state) || r.maxPlayers < 2) {
                    continue;
                }
                // an imported mode is shown to strangers only once it is in the gallery (and not taken out of it)
                if (!mayBePublic(r)) {
                    continue;
                }
                // the tab of a mode lists the official modes of its family (the Nuzlocke Hardcore with the Nuzlocke)
                if (mode != null && !mode.isBlank() && !mode.equals(r.mode) && !mode.equals(r.spec().family())) {
                    continue;
                }
                if (size > 0 && r.maxPlayers != size) {
                    continue;
                }
                if (!withFull && r.members.size() >= r.maxPlayers && r.member(user.id) == null) {
                    continue;
                }
                if (!q.isEmpty()) {
                    Accounts.User leader = accounts.get(r.leaderId);
                    String hay = (r.name + " " + r.gameName() + " " + (leader == null ? "" : leader.display))
                            .toLowerCase(java.util.Locale.ROOT);
                    boolean all = true;
                    for (String w : q.split("\\s+")) {
                        if (!hay.contains(w)) {
                            all = false;
                            break;
                        }
                    }
                    if (!all) {
                        continue;
                    }
                }
                found.add(r);
            }
        }
        found.sort((a, b) -> Long.compare(b.createdAt, a.createdAt));
        List<Object> out = new ArrayList<>();
        for (Room r : found.subList(0, Math.min(found.size(), 100))) {
            synchronized (r) {
                Map<String, Object> v = r.view(hub, false);
                Accounts.User leader = accounts.get(r.leaderId);
                v.put("leaderName", leader == null ? "?" : leader.display);
                v.put("member", r.member(user.id) != null);
                v.put("rando", r.rando);
                v.put("rules", r.rules);
                out.add(v);
            }
        }
        return out;
    }

    /** Soul Links a user took part in, and how they ended (profile card). */
    Map<String, Object> stats(String userId) {
        long played = 0;
        long won = 0;
        long running = 0;
        Set<String> seen = new java.util.HashSet<>();
        for (Room r : rooms.values()) {
            seen.add(r.id);
            synchronized (r) {
                if (!r.history.contains(userId) || "lobby".equals(r.state)) {
                    continue;
                }
                played++;
                if ("running".equals(r.state)) {
                    running++;
                } else if (r.ended != null && "victory".equals(Json.str(r.ended, "outcome"))) {
                    won++;
                }
            }
        }
        for (Asleep a : asleep.values()) {
            if (!seen.contains(a.id()) && a.history().contains(userId)) {
                played++;
                if ("victory".equals(a.outcome())) {
                    won++;
                }
            }
        }
        return Json.map("played", played, "won", won, "running", running);
    }

    /** A line in the journal of a room (results of the battles between its players). */
    void journal(Room r, String type, String text) {
        synchronized (r) {
            Room.Out out = new Room.Out();
            out.log(null, type, text, null);
            commit(r, out);
        }
    }

    /** A player changed their name: their place in the rooms that are not over shows it. */
    void renamed(Accounts.User user) {
        for (Room r : activeOf(user.id)) {
            synchronized (r) {
                Room.Member m = r.member(user.id);
                if (m != null && !user.display.equals(m.name)) {
                    m.name = user.display;
                    commit(r, new Room.Out());
                }
            }
        }
    }

    Room byCode(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        for (Room r : rooms.values()) {
            if (c.equals(r.code)) {
                return r;
            }
        }
        for (Asleep a : asleep.values()) {
            if (c.equals(a.code())) {
                Room r = wake(a.id());
                if (r != null) {
                    return r;
                }
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

    /** Every finished run on this server (statistics and leaderboards). */
    List<Records.Run> runs() {
        List<Records.Run> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Room r : rooms.values()) {
            seen.add(r.id);
            synchronized (r) {
                out.addAll(Records.of(r));
            }
        }
        for (Asleep a : asleep.values()) {
            if (!seen.contains(a.id())) {
                out.addAll(a.runs());
            }
        }
        return out;
    }

    /** Every Soul Link the user took part in (history). */
    List<Object> history(Accounts.User user) {
        List<Object> out = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (Room r : rooms.values()) {
            seen.add(r.id);
            synchronized (r) {
                if (!r.history.contains(user.id) || "lobby".equals(r.state)) {
                    continue;
                }
                out.add(historyCard(r));
            }
        }
        // the finished rooms asleep: their card kept, with who is online and their pictures of now
        for (Asleep a : asleep.values()) {
            if (seen.contains(a.id()) || !a.history().contains(user.id)) {
                continue;
            }
            Map<String, Object> v = Json.obj(Json.copy(a.card()));
            List<Object> ms = Json.arr(v.get("members"));
            if (ms != null) {
                for (Object o : ms) {
                    Map<String, Object> m = Json.obj(o);
                    String uid = Json.str(m, "userId");
                    m.put("online", hub.online(uid));
                    m.put("avatar", hub.avatar(uid));
                }
            }
            out.add(v);
        }
        out.sort((a, b) -> Long.compare(Json.num(Json.obj(b), "launchedAt", 0), Json.num(Json.obj(a), "launchedAt", 0)));
        return out;
    }

    Map<String, Object> view(String id, Accounts.User user) {
        Room r = get(id);
        synchronized (r) {
            boolean member = r.member(user.id) != null;
            boolean open = "public".equals(r.visibility) && "lobby".equals(r.state) && r.maxPlayers >= 2;
            if (!member && !r.invites.contains(user.id) && !r.history.contains(user.id) && !open) {
                throw new Http.Fail(403, "Vous ne faites pas partie de cette salle.");
            }
            Map<String, Object> v = r.view(hub, member || r.history.contains(user.id));
            if (!member && open) {
                // a public room seen from outside: what is needed to decide to join it
                Accounts.User leader = accounts.get(r.leaderId);
                v.put("leaderName", leader == null ? "?" : leader.display);
                v.put("rando", r.rando);
                v.put("rules", r.rules);
                v.put("visitor", true);
            }
            if (member) {
                v.put("directives", r.directives(r.member(user.id)));
            }
            return v;
        }
    }

    // --------------------------------------------------------------- lobby

    /**
     * A player creates rooms within bounds (security audit, R-4): at most {@link #MAX_OPEN_ROOMS} of their rooms not
     * over, and {@link #MAX_CREATIONS} creations in {@link #CREATION_WINDOW_MS}.
     */
    void limitCreation(String userId, long now) {
        int open = 0;
        for (Room r : rooms.values()) {
            synchronized (r) {
                if (userId.equals(r.leaderId) && ("lobby".equals(r.state) || "running".equals(r.state))) {
                    open++;
                }
            }
        }
        if (open >= MAX_OPEN_ROOMS) {
            throw new Http.Fail(409, "Vous menez déjà " + open + " salles en cours : terminez ou supprimez-en une avant d'en créer une autre.");
        }
        Deque<Long> times = creations.computeIfAbsent(userId, k -> new ArrayDeque<>());
        synchronized (times) {
            while (!times.isEmpty() && times.peekFirst() < now - CREATION_WINDOW_MS) {
                times.pollFirst();
            }
            if (times.size() >= MAX_CREATIONS) {
                throw new Http.Fail(429, "Doucement : vous avez créé beaucoup de salles. Réessayez dans quelques minutes.");
            }
            times.addLast(now);
        }
    }

    /** Whether strangers may see the room: an official mode, or an imported one published in the gallery. */
    boolean mayBePublic(Room r) {
        return r.customSpec == null || (gallery != null && gallery.published(r.customSpec));
    }

    /** A mode file brought by a player: checked like any mode file, and never under the name of an official mode. */
    static ModeSpec importMode(String text) {
        ModeSpec spec = ModeSpec.parse(text, false);
        if (ModeSpec.official(spec.id) != null) {
            throw new Http.Fail(400, "Mode invalide : « " + spec.id + " » est l'identifiant d'un mode officiel, choisissez-en un autre.");
        }
        return spec;
    }

    Map<String, Object> create(Accounts.User user, Map<String, Object> body) {
        Map<String, Object> game = Json.obj(body.get("game"));
        String gameId = Json.str(game, "gameId");
        if (gameId == null || !GAME_ID.matcher(gameId).matches()) {
            throw new Http.Fail(400, "Choisissez le jeu de la salle.");
        }
        limitCreation(user.id, System.currentTimeMillis());
        Room r = new Room();
        r.id = Ids.id(10);
        r.code = Ids.code(6);
        r.name = cleanName(Json.str(body, "name"), user.display + " — " + Json.str(game, "name"));
        r.leaderId = user.id;
        r.createdAt = System.currentTimeMillis();
        r.game = Json.map("gameId", gameId, "name", Json.str(game, "name"), "gen", Json.num(game, "gen", 0),
                "platform", Json.str(game, "platform"), "romCode", Json.str(game, "romCode"), "lang", Json.str(game, "lang"),
                "crc", Json.str(game, "crc"));
        Object modeFile = body.get("modeFile");
        if (modeFile != null) {
            // an imported mode: checked like any mode file, kept in the room (its own copy)
            ModeSpec imported = importMode(modeFile instanceof String s ? s : Json.stringify(modeFile));
            r.customSpec = imported;
            r.mode = imported.id;
        } else {
            String mode = Json.str(body, "mode");
            r.mode = mode != null && Room.modes().contains(mode) ? mode : "soullink";
        }
        ModeSpec spec = r.spec();
        // the mode needs what the game can do (Shinylocke: shinies, none in generation 1)
        String missing = Capabilities.missingForSpec(spec, gameId, (int) Json.num(game, "gen", 0));
        if (missing != null) {
            throw new Http.Fail(400, "Le " + r.runName() + " n'est pas disponible avec " + r.gameName() + " : " + missing + ".");
        }
        r.maxPlayers = (int) Math.max(spec.minPlayers, Math.min(spec.maxPlayers,
                Json.num(body, "maxPlayers", Math.max(2, spec.minPlayers))));
        // a room for one player is never listed: nobody could join it
        r.visibility = "public".equals(Json.str(body, "visibility")) && r.maxPlayers >= 2 && mayBePublic(r) ? "public" : "private";
        r.rules = spec.defaults();
        Map<String, Object> rules = Json.obj(body.get("rules"));
        if (rules != null) {
            // a rule the mode fixes keeps its value, as does a value the rule does not allow
            for (Map.Entry<String, Object> e : rules.entrySet()) {
                if (r.rules.containsKey(e.getKey()) && !spec.locked(e.getKey()) && ModeSpec.allows(e.getKey(), e.getValue())) {
                    r.rules.put(e.getKey(), e.getValue());
                }
            }
        }
        Map<String, Object> rando = Json.obj(body.get("rando"));
        r.rando = rando == null ? new LinkedHashMap<>() : rando;
        r.rando.put("shinyRam", true); // DS games: the rate is applied while playing, nothing of it in the ROM
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
        if (r.customSpec != null && gallery != null) {
            gallery.used(r.customSpec);
        }
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
                List<String> changed = r.changeRules(r.member(user.id), user.display, rules, out);
                if (changed.stream().anyMatch(Room.ROM_RULES::contains)) {
                    // a rule of the ROM (no evolution): every generated ROM is now outdated
                    for (Room.Member m : r.members) {
                        m.rom = new LinkedHashMap<>(Map.of("status", "missing"));
                        m.ready = false;
                    }
                }
            }
            if (body.containsKey("maxPlayers")) {
                if (!"lobby".equals(r.state)) {
                    throw new Http.Fail(409, "Le nombre de joueurs ne peut plus changer une fois l'aventure lancée.");
                }
                int asked = (int) Json.num(body, "maxPlayers", r.maxPlayers);
                if (asked < r.members.size()) {
                    throw new Http.Fail(409, "Il y a déjà " + r.members.size() + " joueur(s) dans la salle.");
                }
                r.maxPlayers = Math.max(r.spec().minPlayers, Math.min(r.spec().maxPlayers, asked));
            }
            if (body.containsKey("visibility")) {
                boolean open = "public".equals(Json.str(body, "visibility"));
                if (open && !mayBePublic(r)) {
                    throw new Http.Fail(409, "Une salle d'un mode importé devient publique quand son mode est publié dans la galerie.");
                }
                r.visibility = open ? "public" : "private";
            }
            if (r.maxPlayers < 2) {
                r.visibility = "private"; // a room for one player is never listed
            }
            Map<String, Object> rando = Json.obj(body.get("rando"));
            if (rando != null) {
                if (!"lobby".equals(r.state)) {
                    throw new Http.Fail(409, "La randomisation ne peut plus changer une fois l'aventure lancée.");
                }
                r.rando = rando;
                r.rando.put("shinyRam", true); // every ROM is generated again: none of them has the rate any more
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
            if (!byCode && !r.invites.contains(user.id) && !("public".equals(r.visibility) && r.maxPlayers >= 2)) {
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
            onDeleted.accept(r.id);
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
            asleep.remove(r.id);
            used.remove(r.id);
            deleteFiles(r);
            hub.send(before, "room-deleted", Json.map("roomId", r.id));
            onDeleted.accept(r.id);
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
            if (r.members.size() < r.spec().minPlayers) {
                throw new Http.Fail(409, "Il faut au moins " + r.spec().minPlayers + " joueurs pour lancer un " + r.runName() + ".");
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
            Room.Out out = new Room.Out();
            r.launch(out);
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
            // what the game shows right now (field, dialogue, battle), from the position it sends to the room
            Map<String, Object> seen = ghosts.get(r.id + "/" + m.userId);
            Object shown = seen == null || System.currentTimeMillis() - Json.num(seen, "at", 0) > 10_000 ? null : seen.get("st");
            m.shown = shown == null ? null : shown.toString();
            for (Object o : events) {
                Map<String, Object> ev = Json.obj(o);
                if (ev != null) {
                    r.event(m, ev, out);
                }
            }
            // an encounter the tracker believes in while the game shows the field is no battle (see battleFromGame)
            if ("free".equals(shown) || "dialogue".equals(shown)) {
                m.live.remove("battle");
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

    /** Rule "Exception pour les CS": the player's next forbidden capture is kept for the HMs (or no longer). */
    void hm(String id, Accounts.User user, boolean on) {
        Room r = memberRoom(id, user);
        synchronized (r) {
            Room.Out out = new Room.Out();
            r.hmNext(r.member(user.id), on, out);
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
        if (rooms.get(r.id) != r) {
            asleep.computeIfPresent(r.id, (k, a) -> summary(r));
        }
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
        if (!Files.isRegularFile(f)) {
            return new ArrayList<>();
        }
        try {
            return tail(f, after, limit);
        } catch (IOException | RuntimeException e) {
            throw new Http.Fail(500, "Journal illisible : " + e.getMessage());
        }
    }

    /**
     * The last {@code limit} lines of a journal numbered after {@code after}, in their order. Read from the end by
     * blocks: a request costs at most {@code limit} lines, however long the journal (a long run has thousands). The
     * lines are numbered in order, so the reading stops at the first one not after {@code after}.
     */
    static List<Object> tail(Path f, long after, int limit) throws IOException {
        java.util.ArrayDeque<Object> out = new java.util.ArrayDeque<>();
        try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(f, StandardOpenOption.READ)) {
            long pos = ch.size();
            byte[] rest = new byte[0]; // the start of a line cut by the beginning of the block read
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocate(64 * 1024);
            while (out.size() < limit) {
                if (pos == 0) {
                    if (rest.length > 0) {
                        add(out, rest, 0, rest.length, after); // the first line of the journal
                    }
                    break;
                }
                int n = (int) Math.min(buf.capacity(), pos);
                pos -= n;
                buf.clear().limit(n);
                while (buf.hasRemaining()) {
                    if (ch.read(buf, pos + buf.position()) < 0) {
                        throw new IOException("fin du journal inattendue");
                    }
                }
                byte[] block = new byte[n + rest.length];
                System.arraycopy(buf.array(), 0, block, 0, n);
                System.arraycopy(rest, 0, block, n, rest.length);
                int end = block.length;
                boolean more = true;
                for (int i = block.length - 1; i >= 0 && more && out.size() < limit; i--) {
                    if (block[i] == '\n') {
                        more = add(out, block, i + 1, end, after);
                        end = i;
                    }
                }
                if (!more || out.size() >= limit) {
                    break;
                }
                rest = java.util.Arrays.copyOf(block, end);
            }
        }
        return new ArrayList<>(out);
    }

    /** One line of a journal read from the end: added in front if numbered after {@code after}; false to stop. */
    private static boolean add(java.util.ArrayDeque<Object> out, byte[] b, int from, int to, long after) {
        String line = new String(b, from, to - from, StandardCharsets.UTF_8);
        if (line.isBlank()) {
            return true;
        }
        Map<String, Object> l = Json.parseObject(line);
        long seq = Json.num(l, "seq", 0);
        if (seq > after) {
            out.addFirst(l);
            return true;
        }
        return seq <= 0; // a line without a number does not stop the reading
    }

    /** How many rooms in each state, players online in a running one, live screens kept and watched (numbers only). */
    Map<String, Object> metrics(Hub hub) {
        long lobby = 0;
        long running = 0;
        long over = 0;
        long playing = 0;
        Set<String> seen = new java.util.HashSet<>();
        for (Room r : rooms.values()) {
            seen.add(r.id);
            synchronized (r) {
                switch (r.state == null ? "" : r.state) {
                    case "lobby" -> lobby++;
                    case "running" -> {
                        running++;
                        for (Room.Member m : r.members) {
                            if (hub.online(m.userId)) {
                                playing++;
                            }
                        }
                    }
                    default -> over++;
                }
            }
        }
        long sleeping = asleep.keySet().stream().filter(id -> !seen.contains(id)).count();
        long now = System.currentTimeMillis();
        long watching = watched.values().stream().filter(t -> now - t < WATCH_MS).count();
        return Json.map("lobby", lobby, "running", running, "over", over + sleeping, "asleep", sleeping,
                "playersInRunning", playing, "screens", (long) screens.size(), "screensWatched", watching);
    }

    // ------------------------------------------------------ live screens

    /**
     * Keeps the latest image of a player's game. Returns whether another member asked for it lately: the
     * application then sends one image every few seconds only while nobody looks (an older one ignores it).
     */
    boolean putScreen(String id, Accounts.User user, byte[] jpeg) {
        Room r = memberRoom(id, user);
        if (jpeg.length > MAX_SCREEN) {
            throw new Http.Fail(413, "Image trop grande");
        }
        String key = r.id + "/" + user.id;
        Screen prev = screens.get(key);
        long now = System.currentTimeMillis();
        screens.put(key, new Screen(prev == null ? 1 : prev.seq() + 1, jpeg, now));
        Long seen = watched.get(key);
        return seen != null && now - seen < WATCH_MS;
    }

    /** Latest image of a player's game, or null if nothing newer than {@code since}; the image is watched. */
    Map<String, Object> screen(String id, Accounts.User user, String target, long since) {
        Room r = memberRoom(id, user);
        if (!user.id.equals(target) && r.member(target) != null) {
            watched.put(r.id + "/" + target, System.currentTimeMillis());
        }
        Screen s = screens.get(r.id + "/" + target);
        if (s == null || s.seq() <= since || System.currentTimeMillis() - s.at() > 20_000) {
            return null;
        }
        return Json.map("seq", s.seq(), "jpeg", s.jpeg());
    }

    // ------------------------------------------------- presence in game

    private final Map<String, Map<String, Object>> ghosts = new ConcurrentHashMap<>();

    /**
     * Where a player stands in their game: relayed at once to the other
     * members, who draw them in their own game. The way walked since the
     * previous message ("pts") lets them replay it smoothly; each sprite
     * travels once ("sprs"). The server keeps the latest position and every
     * sprite for newcomers.
     */
    void ghost(String id, Accounts.User user, Map<String, Object> body) {
        Room r = memberRoom(id, user);
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
            hub.send(audience(r), "live", Json.map("roomId", r.id, "userId", m.userId, "live", m.live));
        }
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

    // ------------------------------------------------- account deletion

    /** The name left in the rooms (journal, players) by a deleted account. */
    static final String DELETED = "Compte supprimé";

    /**
     * An account is deleted: it leaves the rooms not launched; in the others its place stays (the adventure of the
     * other players depends on it) under {@link #DELETED}, without its save, its screen and its name in the journal.
     * A room nobody else played in goes with it.
     */
    void accountDeleted(Accounts.User user) {
        for (Asleep a : new ArrayList<>(asleep.values())) {
            if (a.history().contains(user.id) || a.members().contains(user.id) || a.invites().contains(user.id)) {
                wake(a.id());
            }
        }
        for (Room r : new ArrayList<>(rooms.values())) {
            synchronized (r) {
                Room.Member m = r.member(user.id);
                if (m == null && !r.history.contains(user.id) && !r.invites.contains(user.id)) {
                    continue;
                }
                r.invites.remove(user.id);
                if (m != null && "lobby".equals(r.state)) {
                    removeMember(r, user.id);
                    continue;
                }
                boolean others = false;
                for (Room.Member o : r.members) {
                    others |= !o.userId.equals(user.id);
                }
                if (!others) {
                    Collection<String> before = audience(r);
                    rooms.remove(r.id);
                    used.remove(r.id);
                    deleteFiles(r);
                    hub.send(before, "room-deleted", Json.map("roomId", r.id));
                    onDeleted.accept(r.id);
                    continue;
                }
                r.history.remove(user.id);
                if (m != null) {
                    m.name = DELETED;
                    if (r.isLeader(user.id)) {
                        for (Room.Member o : r.members) {
                            if (!o.userId.equals(user.id)) {
                                r.leaderId = o.userId;
                                break;
                            }
                        }
                    }
                }
                anonymizeLog(r, user);
                try {
                    Files.deleteIfExists(saveDir.resolve(r.id).resolve(user.id + ".sav"));
                    Files.deleteIfExists(saveDir.resolve(r.id).resolve(user.id + ".json"));
                } catch (IOException e) {
                    Log.warn("Serveur : sauvegarde de " + user.username + " non supprimée : " + e.getMessage());
                }
                screens.remove(r.id + "/" + user.id);
                watched.remove(r.id + "/" + user.id);
                ghosts.remove(r.id + "/" + user.id);
                commit(r, new Room.Out());
            }
        }
    }

    /** The journal of a room without the name of a deleted account. */
    private void anonymizeLog(Room r, Accounts.User user) {
        Path f = logDir.resolve(r.id + ".jsonl");
        if (!Files.isRegularFile(f)) {
            return;
        }
        try {
            StringBuilder sb = new StringBuilder();
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                Map<String, Object> m = Json.parseObject(line);
                if (user.id.equals(Json.str(m, "user"))) {
                    m.put("name", DELETED);
                }
                String text = Json.str(m, "text");
                if (text != null && user.display.length() >= 2) {
                    m.put("text", text.replace(user.display, DELETED));
                }
                sb.append(Json.stringify(m)).append('\n');
            }
            Accounts.write(f, sb.toString());
        } catch (IOException | RuntimeException e) {
            Log.warn("Serveur : journal de la salle " + r.id + " non anonymisé : " + e.getMessage());
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
        watched.keySet().removeIf(k -> k.startsWith(r.id + "/"));
        ghosts.keySet().removeIf(k -> k.startsWith(r.id + "/"));
    }

    private void load() throws IOException {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                try {
                    Room r = Room.fromStore(Json.parseObject(Files.readString(p)));
                    if (over(r)) {
                        asleep.put(r.id, summary(r));
                    } else {
                        rooms.put(r.id, r);
                    }
                } catch (IOException | RuntimeException e) {
                    Log.warn("Serveur : salle illisible " + p.getFileName() + " : " + e.getMessage());
                }
            }
        }
        Log.info("Serveur : " + rooms.size() + " salle(s) chargée(s), " + asleep.size() + " partie(s) terminée(s) en résumé");
    }

    Set<String> ids() {
        return rooms.keySet();
    }
}
