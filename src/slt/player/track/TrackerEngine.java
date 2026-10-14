package slt.player.track;

import slt.Json;
import slt.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Turns successive memory readings into Soul Link events: captures (with
 * their zone), K.O., evolutions and levels, party changes, badges, location,
 * whiteout, wild encounters (caught, knocked out or left), trainer battles,
 * items used / bought / obtained, the Champion beaten, and a short live view
 * of the team for the other players.
 *
 * What was already reported is kept in {@code suivi.json} next to the game
 * save, so restarting the game never reports the same capture twice.
 */
public final class TrackerEngine {

    /** Number of readings (about 0.5 s each) a wild encounter may stay unresolved after its battle. */
    private static final int RESOLVE_GRACE = 6;
    /** More item changes than this at once = a save was loaded, not something the player did. */
    private static final int MAX_ITEM_CHANGES = 8;
    private static final long TEAM_INTERVAL_MS = 1500;

    private static final class Known {
        int species;
        int level;
        String nickname;
        boolean fainted;
    }

    private static final class Pending {
        Mon mon;
        String zone;
        int map;
        int idleReads;
        int balls;
        int lastHp;
        boolean sawBattle;
    }

    private final GameReader reader;
    private final GameInfo info;
    private final Consumer<Map<String, Object>> sink;
    private final Path stateFile;

    private final Map<Long, Known> known = new LinkedHashMap<>();
    private final Set<Long> seenWild = new HashSet<>();
    private int badges = -1;
    private int cleared = -1;
    private boolean dirty;

    private List<Long> lastParty;
    private int lastMap = -2;
    private String zone;
    private boolean wiped;
    private Pending pending;
    private int lastBattle;
    private int trainerSize;
    private Map<Integer, Integer> lastBag;
    private long lastMoney = -1;
    private String lastTeam;
    private long lastTeamAt;
    private int quietWildBattles;
    private int lastBalls = -1;
    private volatile List<Mon> party = List.of();
    private volatile Set<Long> doomed = Set.of();
    private volatile String status = "En attente du jeu…";
    private volatile int battleState = -1;
    private volatile boolean mapJustChanged;

    public TrackerEngine(GameReader reader, GameInfo info, Path stateFile, Consumer<Map<String, Object>> sink) {
        this.reader = reader;
        this.info = info;
        this.stateFile = stateFile;
        this.sink = sink;
        load();
    }

    public GameReader reader() {
        return reader;
    }

    public GameInfo info() {
        return info;
    }

    /** Party of the last reading (used to apply the room's decisions). */
    public List<Mon> party() {
        return party;
    }

    /** -1 unknown, 0 no battle, 1 wild, 2 trainer (last reading). */
    public int battleState() {
        return battleState;
    }

    /** No wild encounter in progress (last reading). */
    public boolean noWildBattle() {
        return pending == null;
    }

    /**
     * The player has just changed map (last reading): they are walking in the field, no battle
     * can be running. Used where the battle state is unknown (DS games) to change the party safely.
     */
    public boolean mapJustChanged() {
        return mapJustChanged;
    }

    /** Pokémon that must stay K.O. (dead in the Soul Link): set from the room's directives. */
    public void setDoomed(Set<Long> uids) {
        doomed = Set.copyOf(uids);
    }

    public Set<Long> doomed() {
        return doomed;
    }

    public String status() {
        return status;
    }

    // ---------------------------------------------------------------- update

    /** Processes one reading (tracker thread). */
    public void update(Mem m) {
        Snapshot s;
        try {
            s = reader.read(m);
        } catch (RuntimeException e) {
            Log.error("Suivi : lecture impossible", e);
            return;
        }
        if (s == null) {
            status = "En attente d'une partie chargée…";
            return;
        }
        List<Mon> mons = new ArrayList<>();
        for (Mon mon : s.party()) {
            if (!mon.egg()) {
                mons.add(mon);
            }
        }
        party = mons;
        battleState = s.battle();
        status = mons.isEmpty() ? "Partie détectée (équipe vide)" : "Suivi actif : " + mons.size() + " Pokémon dans l'équipe";

        // before the encounters: an encounter only counts once the player has Poké Balls
        if (s.balls() >= 0 && s.balls() != lastBalls) {
            lastBalls = s.balls();
            emit(Json.map("type", "balls", "has", lastBalls == 1));
        }
        location(s);
        wild(s, m);
        battle(s, mons);

        boolean first = lastParty == null;
        boolean fresh = known.isEmpty();
        for (Mon mon : mons) {
            Known k = known.get(mon.uid());
            if (k == null) {
                capture(mon, s, first && fresh ? "existing" : null, true, null);
            } else {
                changes(mon, k);
            }
        }
        List<Long> uids = mons.stream().map(Mon::uid).toList();
        if (!uids.equals(lastParty)) {
            lastParty = uids;
            List<Object> list = new ArrayList<>();
            for (long u : uids) {
                list.add(uidText(u));
            }
            emit(Json.map("type", "party", "uids", list));
        }
        team(mons, m);
        // a whiteout needs a Pokémon that fell in the game itself: a party only K.O. because of
        // Soul Link deaths can still be swapped with the PC
        Set<Long> dead = doomed;
        boolean allDown = !mons.isEmpty() && mons.stream().allMatch(Mon::fainted)
                && mons.stream().anyMatch(x -> !dead.contains(x.uid()));
        if (allDown && !wiped) {
            emit(Json.map("type", "whiteout"));
        }
        wiped = allDown;
        if (s.badges() >= 0 && s.badges() != badges) {
            boolean firstRead = badges < 0;
            badges = s.badges();
            dirty = true;
            if (!firstRead || badges > 0) {
                emit(Json.map("type", "badges", "count", (long) badges));
            }
        }
        items(s);
        champion(s);
        if (dirty) {
            save();
        }
    }

    private void location(Snapshot s) {
        mapJustChanged = false;
        if (s.map() == lastMap) {
            return;
        }
        mapJustChanged = s.map() >= 0 && lastMap >= 0;
        lastMap = s.map();
        String name = info.map(s.map());
        if (name != null) {
            zone = name;
        }
        emit(Json.map("type", "location", "map", (long) s.map(), "zone", name));
    }

    /** New wild Pokémon in battle, and resolution of the previous one. */
    private void wild(Snapshot s, Mem m) {
        Mon fresh = null;
        for (Mon w : s.wild()) {
            if (pending != null && w.uid() == pending.mon.uid()) {
                pending.lastHp = w.hp();
                if (s.battle() == 1) {
                    pending.sawBattle = true;
                }
            }
            if (!seenWild.contains(w.uid()) && !known.containsKey(w.uid())) {
                fresh = w;
                break;
            }
        }
        if (pending != null) {
            long uid = pending.mon.uid();
            boolean caught = s.party().stream().anyMatch(x -> x.uid() == uid);
            if (!caught) {
                // the battle state is known in some games only; otherwise the encounter is settled
                // when the player meets another Pokémon or changes map
                String here = info.map(s.map());
                boolean moved = here != null && pending.zone != null ? !here.equals(pending.zone)
                        : s.map() >= 0 && pending.map >= 0 && s.map() != pending.map;
                if (s.battle() == 0) {
                    pending.idleReads++;
                }
                if (fresh != null || moved || pending.idleReads > RESOLVE_GRACE) {
                    resolve(m);
                }
            }
        }
        if (fresh != null) {
            seenWild.add(fresh.uid());
            dirty = true;
            if (pending != null) {
                resolve(m);
            }
            pending = new Pending();
            pending.mon = fresh;
            pending.map = s.map();
            pending.zone = zone;
            pending.balls = s.balls();
            pending.lastHp = fresh.hp();
            pending.sawBattle = s.battle() == 1;
            emit(Json.map("type", "encounter", "phase", "start", "uid", uidText(fresh.uid()),
                    "species", (long) fresh.species(), "speciesName", info.speciesName(fresh.species()),
                    "level", (long) fresh.level(), "zone", pending.zone, "shiny", fresh.shiny(), "balls", (long) pending.balls));
        }
    }

    /** The wild Pokémon left: caught (party or PC), knocked out, or the player left the battle. */
    private void resolve(Mem m) {
        Pending p = pending;
        pending = null;
        if (p == null || known.containsKey(p.mon.uid())) {
            return;
        }
        Boolean stored = reader.stored(m, p.mon.uid());
        if (Boolean.TRUE.equals(stored)) {
            capture(p.mon, null, "wild", false, p.zone);
            return;
        }
        // Gen 3: the "in battle" bit must be seen during a wild battle, else it is not the right byte
        if (!p.sawBattle && battleState == 0 && reader instanceof Gen3Reader g3 && ++quietWildBattles >= 2) {
            Log.warn("Suivi : indicateur de combat non fiable, détection des combats de dresseurs désactivée.");
            g3.distrustBattleFlag();
        }
        emit(Json.map("type", "encounter", "phase", "end", "outcome", stored == null ? "unknown" : "failed",
                "wildFainted", p.lastHp == 0, "uid", uidText(p.mon.uid()), "species", (long) p.mon.species(),
                "speciesName", info.speciesName(p.mon.species()), "level", (long) p.mon.level(), "zone", p.zone,
                "shiny", p.mon.shiny(), "balls", (long) p.balls));
    }

    /** Trainer battles: start, then won (every opponent K.O.) or lost (whole team K.O.). */
    private void battle(Snapshot s, List<Mon> mons) {
        int b = s.battle();
        if (b < 0) {
            return;
        }
        if (b == 2 && lastBattle != 2) {
            trainerSize = s.enemy().size();
            emit(Json.map("type", "battle", "phase", "start", "kind", "trainer", "size", (long) trainerSize));
        } else if (b != 2 && lastBattle == 2) {
            boolean lost = !mons.isEmpty() && mons.stream().allMatch(Mon::fainted);
            emit(Json.map("type", "battle", "phase", "end", "kind", "trainer", "outcome", lost ? "lost" : "won"));
        }
        lastBattle = b;
    }

    /** Items: what went down was used (or sold), what went up was bought (money spent) or obtained. */
    private void items(Snapshot s) {
        Map<Integer, Integer> bag = s.bag();
        if (bag == null) {
            return;
        }
        Map<Integer, Integer> before = lastBag;
        long moneyBefore = lastMoney;
        lastBag = bag;
        lastMoney = s.money();
        if (before == null) {
            return;
        }
        Set<Integer> ids = new TreeSet<>(before.keySet());
        ids.addAll(bag.keySet());
        List<Object> changes = new ArrayList<>();
        for (int id : ids) {
            int delta = bag.getOrDefault(id, 0) - before.getOrDefault(id, 0);
            if (delta != 0) {
                String name = info.itemName(id);
                changes.add(Json.map("item", (long) id, "name", name == null ? "Objet #" + id : name, "delta", (long) delta));
            }
        }
        if (changes.isEmpty() || changes.size() > MAX_ITEM_CHANGES) {
            return;
        }
        long money = s.money() >= 0 && moneyBefore >= 0 ? s.money() - moneyBefore : 0;
        boolean inBattle = s.battle() > 0 || pending != null;
        emit(Json.map("type", "items", "changes", changes, "money", money, "battle", inBattle));
    }

    private void champion(Snapshot s) {
        if (s.cleared() < 0 || s.cleared() == cleared) {
            return;
        }
        boolean wasKnown = cleared >= 0;
        cleared = s.cleared();
        dirty = true;
        if (wasKnown && cleared == 1) {
            emit(Json.map("type", "champion"));
        }
    }

    /**
     * Short view of the team for the other players (species, level, HP), at most every 1.5 s, with what a
     * battle between players of the room needs (moves, stats…: the reader's battle set).
     */
    private void team(List<Mon> mons, Mem m) {
        StringBuilder sig = new StringBuilder();
        List<Object> list = new ArrayList<>();
        for (Mon x : mons) {
            Map<String, Object> set = null;
            try {
                set = reader.battleSet(m, x);
            } catch (RuntimeException e) {
                // the team stays shown without what battles need
            }
            sig.append(x.uid()).append(':').append(x.species()).append(':').append(x.level()).append(':')
                    .append(x.hp()).append(':').append(x.nickname()).append(':')
                    .append(set == null ? "" : String.valueOf(set.get("moves"))).append(';');
            list.add(Json.map("uid", uidText(x.uid()), "species", (long) x.species(), "speciesName", info.speciesName(x.species()),
                    "nickname", x.nickname(), "level", (long) x.level(), "hp", (long) x.hp(), "maxHp", (long) x.maxHp(),
                    "shiny", x.shiny(), "dead", doomed.contains(x.uid()), "set", set));
        }
        long now = System.currentTimeMillis();
        if (sig.toString().equals(lastTeam) || now - lastTeamAt < TEAM_INTERVAL_MS) {
            return;
        }
        lastTeam = sig.toString();
        lastTeamAt = now;
        emit(Json.map("type", "team", "mons", list));
    }

    /**
     * A Pokémon joins the player: zone = where the game says it was met (gens 3-5),
     * else where its wild battle took place, else the current map.
     */
    private void capture(Mon mon, Snapshot s, String origin, boolean inParty, String zoneHint) {
        Known k = new Known();
        k.species = mon.species();
        k.level = mon.level();
        k.nickname = mon.nickname();
        k.fainted = mon.fainted();
        known.put(mon.uid(), k);
        dirty = true;
        String battleZone = zoneHint;
        String how = origin;
        if (pending != null && pending.mon.uid() == mon.uid()) {
            battleZone = pending.zone;
            how = "wild";
            pending = null;
        }
        String where = info.metName(mon.met());
        if (where == null) {
            where = battleZone;
        }
        if (where == null) {
            where = zone;
        }
        if (how == null) {
            how = s != null && s.playerOt() >= 0 && mon.otId() != s.playerOt() ? "trade" : "new";
        }
        emit(Json.map("type", "capture", "uid", uidText(mon.uid()), "species", (long) mon.species(),
                "speciesName", info.speciesName(mon.species()), "level", (long) mon.level(),
                "nickname", mon.nickname(), "zone", where, "origin", how, "inParty", inParty,
                "fainted", mon.fainted(), "shiny", mon.shiny()));
        if (mon.fainted()) {
            emit(faint(mon));
        }
    }

    private void changes(Mon mon, Known k) {
        boolean changed = false;
        if (mon.species() != k.species || mon.level() != k.level || !same(mon.nickname(), k.nickname)) {
            boolean evolved = mon.species() != k.species;
            k.species = mon.species();
            k.level = mon.level();
            k.nickname = mon.nickname();
            changed = true;
            emit(Json.map("type", "update", "uid", uidText(mon.uid()), "species", (long) mon.species(),
                    "speciesName", info.speciesName(mon.species()), "level", (long) mon.level(),
                    "nickname", mon.nickname(), "evolved", evolved));
        }
        if (mon.fainted() != k.fainted) {
            k.fainted = mon.fainted();
            changed = true;
            if (mon.fainted()) {
                emit(faint(mon));
            }
        }
        if (changed) {
            dirty = true;
        }
    }

    private Map<String, Object> faint(Mon mon) {
        return Json.map("type", "faint", "uid", uidText(mon.uid()), "species", (long) mon.species(),
                "speciesName", info.speciesName(mon.species()), "level", (long) mon.level(),
                "nickname", mon.nickname(), "zone", zone, "doomed", doomed.contains(mon.uid()));
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /** Identifiers travel as text (JSON numbers would lose precision in the browser). */
    public static String uidText(long uid) {
        return Long.toString(uid);
    }

    private void emit(Map<String, Object> ev) {
        try {
            sink.accept(ev);
        } catch (RuntimeException e) {
            Log.error("Suivi : envoi impossible", e);
        }
    }

    // ----------------------------------------------------------- persistence

    private void load() {
        if (stateFile == null || !Files.isRegularFile(stateFile)) {
            return;
        }
        try {
            Map<String, Object> o = Json.obj(Json.parse(Files.readString(stateFile)));
            Map<String, Object> ks = Json.obj(o.get("known"));
            if (ks != null) {
                for (Map.Entry<String, Object> e : ks.entrySet()) {
                    Map<String, Object> v = Json.obj(e.getValue());
                    Known k = new Known();
                    k.species = (int) Json.num(v, "species", 0);
                    k.level = (int) Json.num(v, "level", 0);
                    k.nickname = Json.str(v, "nickname");
                    k.fainted = Json.bool(v, "fainted", false);
                    known.put(Long.parseLong(e.getKey()), k);
                }
            }
            List<Object> w = Json.arr(o.get("seenWild"));
            if (w != null) {
                for (Object x : w) {
                    seenWild.add(Long.parseLong(String.valueOf(x)));
                }
            }
            badges = (int) Json.num(o, "badges", -1);
            cleared = (int) Json.num(o, "cleared", -1);
        } catch (IOException | RuntimeException e) {
            Log.warn("Suivi : état précédent illisible (" + e.getMessage() + "), il repart de zéro.");
        }
    }

    private void save() {
        dirty = false;
        if (stateFile == null) {
            return;
        }
        Map<String, Object> ks = new LinkedHashMap<>();
        for (Map.Entry<Long, Known> e : known.entrySet()) {
            Known k = e.getValue();
            ks.put(uidText(e.getKey()), Json.map("species", (long) k.species, "level", (long) k.level,
                    "nickname", k.nickname, "fainted", k.fainted));
        }
        List<Object> w = new ArrayList<>();
        for (long u : seenWild) {
            w.add(uidText(u));
        }
        try {
            Files.createDirectories(stateFile.getParent());
            Path tmp = stateFile.resolveSibling(stateFile.getFileName() + ".tmp");
            Files.writeString(tmp, Json.stringify(Json.map("version", 2L, "game", info.game, "known", ks,
                    "seenWild", w, "badges", (long) badges, "cleared", (long) cleared)));
            Files.move(tmp, stateFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Log.warn("Suivi : enregistrement impossible : " + e.getMessage());
        }
    }
}
