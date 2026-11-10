package slt.server;

import slt.Http;
import slt.Json;
import slt.Log;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * A room and its run (Soul Link, or each player's own Nuzlocke, Sololocke or Shinylocke): the server is the referee.
 * Every player's game reports what happens (tracker events); the rules below
 * decide which encounter counts, which Pokémon are linked, who dies with whom,
 * and what each game must apply (deaths, nicknames, refunds, pauses).
 *
 * All methods are called with the room locked (see {@link Rooms}).
 */
public final class Room {

    // ------------------------------------------------------------------ rules

    /** The official game modes (their files: web/js/data/modes, see {@link ModeSpec}). */
    static Set<String> modes() {
        return ModeSpec.officials().keySet();
    }

    /** The official mode of this id, or the Soul Link for an unknown one. */
    private static ModeSpec officialOrSoulLink(String mode) {
        ModeSpec s = ModeSpec.official(mode);
        return s != null ? s : ModeSpec.official("soullink");
    }

    /**
     * The classic rules of the Nuzlocke, applied by Cable Club in the games (their keys keep the "honor" of the time the
     * players kept to them on their honour): items taken out of the bag, held items back in it, Fly forgotten, battle
     * style "Set", no evolution (in the room's ROM), a Pokémon kept for the HMs, no capture with a full team.
     */
    static final List<String> CLASSIC_RULES = List.of("honorNoHealItems", "honorNoHeldItems", "honorNoMultiExp", "honorNoMasterBall",
            "honorNoEvolution", "honorNoFly", "honorNoRepel", "honorSetMode", "honorCsException", "honorNotepad");

    /** Rules written in the room's ROM: they change only while the room is a lobby (the ROMs are generated again). */
    static final Set<String> ROM_RULES = Set.of("honorNoEvolution");

    /** Rules of a new Soul Link room; the leader may change them at any time. */
    public static Map<String, Object> defaultRules() {
        return defaultRules("soullink");
    }

    /** Rules of a new room of an official mode (from its file); the leader may change them at any time. */
    public static Map<String, Object> defaultRules(String mode) {
        return officialOrSoulLink(mode).defaults();
    }

    static final Set<Integer> LEGENDARIES = Set.of(144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251,
            377, 378, 379, 380, 381, 382, 383, 384, 385, 386, 480, 481, 482, 483, 484, 485, 486, 487, 488, 489, 490,
            491, 492, 493, 494, 638, 639, 640, 641, 642, 643, 644, 645, 646, 647, 648, 649);

    static final String STARTER = "depart";

    // ------------------------------------------------------------------ model

    /** A Pokémon of one player, as the referee knows it. */
    static final class MonRec {
        String uid;
        int species;
        String speciesName;
        int level;
        String nickname;
        String zoneKey;      // null: not linked (bonus shiny, existing Pokémon)
        String zone;
        String origin;
        boolean shiny;
        boolean bonus;
        String status = "alive";  // alive, dead, released
        String reason;
        long at;
        /** Kept for the HMs (rule "Exception pour les CS"): it may never gain experience. */
        boolean hm;
        /** Its experience points when first seen (-1: not yet). */
        long hmExp = -1;

        Map<String, Object> toJson() {
            return Json.map("uid", uid, "species", (long) species, "speciesName", speciesName, "level", (long) level,
                    "nickname", nickname, "zoneKey", zoneKey, "zone", zone, "origin", origin, "shiny", shiny,
                    "bonus", bonus, "status", status, "reason", reason, "at", at, "hm", hm, "hmExp", hmExp);
        }

        static MonRec fromJson(Map<String, Object> m) {
            MonRec r = new MonRec();
            r.uid = Json.str(m, "uid");
            r.species = (int) Json.num(m, "species", 0);
            r.speciesName = Json.str(m, "speciesName");
            r.level = (int) Json.num(m, "level", 0);
            r.nickname = Json.str(m, "nickname");
            r.zoneKey = Json.str(m, "zoneKey");
            r.zone = Json.str(m, "zone");
            r.origin = Json.str(m, "origin");
            r.shiny = Json.bool(m, "shiny", false);
            r.bonus = Json.bool(m, "bonus", false);
            r.status = Json.str(m, "status") == null ? "alive" : Json.str(m, "status");
            r.reason = Json.str(m, "reason");
            r.at = Json.num(m, "at", 0);
            r.hm = Json.bool(m, "hm", false);
            r.hmExp = Json.num(m, "hmExp", -1);
            return r;
        }

        String label() {
            if (nickname != null && !nickname.isBlank()) {
                return nickname + " (" + (speciesName == null ? "#" + species : speciesName) + ")";
            }
            return speciesName == null ? "Pokémon #" + species : speciesName;
        }
    }

    /** What one player did in a zone. */
    static final class Entry {
        String result;   // caught, killed
        String uid;
        int species;
        String speciesName;
        int level;
        boolean shiny;
        long at;
        final Map<String, Integer> items = new LinkedHashMap<>(); // item id -> quantity used in that battle
        final Map<String, String> itemNames = new LinkedHashMap<>();

        Map<String, Object> toJson() {
            return Json.map("result", result, "uid", uid, "species", (long) species, "speciesName", speciesName,
                    "level", (long) level, "shiny", shiny, "at", at, "items", intMap(items), "itemNames", itemNames);
        }

        static Entry fromJson(Map<String, Object> m) {
            Entry e = new Entry();
            e.result = Json.str(m, "result");
            e.uid = Json.str(m, "uid");
            e.species = (int) Json.num(m, "species", 0);
            e.speciesName = Json.str(m, "speciesName");
            e.level = (int) Json.num(m, "level", 0);
            e.shiny = Json.bool(m, "shiny", false);
            e.at = Json.num(m, "at", 0);
            readInts(m.get("items"), e.items);
            readStrings(m.get("itemNames"), e.itemNames);
            return e;
        }
    }

    /** One encounter zone shared by the room. */
    static final class Zone {
        String key;
        String name;
        String status = "open";   // open, waiting, naming, linked, dead, cancelled
        String nickname;
        long firstAt;
        final Map<String, Entry> entries = new LinkedHashMap<>();

        Map<String, Object> toJson() {
            Map<String, Object> e = new LinkedHashMap<>();
            for (Map.Entry<String, Entry> x : entries.entrySet()) {
                e.put(x.getKey(), x.getValue().toJson());
            }
            return Json.map("key", key, "name", name, "status", status, "nickname", nickname, "firstAt", firstAt,
                    "entries", e);
        }

        static Zone fromJson(Map<String, Object> m) {
            Zone z = new Zone();
            z.key = Json.str(m, "key");
            z.name = Json.str(m, "name");
            z.status = Json.str(m, "status");
            z.nickname = Json.str(m, "nickname");
            z.firstAt = Json.num(m, "firstAt", 0);
            Map<String, Object> e = Json.obj(m.get("entries"));
            if (e != null) {
                for (Map.Entry<String, Object> x : e.entrySet()) {
                    z.entries.put(x.getKey(), Entry.fromJson(Json.obj(x.getValue())));
                }
            }
            return z;
        }
    }

    /** The wild battle a player is in. */
    static final class Encounter {
        String uid;
        String zoneKey;
        String zone;
        String kind;    // zone, shiny, used, dupe, legendary, cancelled
        int species;
        String speciesName;
        int level;
        boolean shiny;
        long at;
        final Map<String, Integer> items = new LinkedHashMap<>();
        final Map<String, String> itemNames = new LinkedHashMap<>();
    }

    /** Totals shown at the end of the Soul Link. */
    static final class Stats {
        int captures;
        int deaths;
        int wildKills;
        int shinies;
        int encounters;
        int trainersBeaten;
        int badges;
        int fouls;           // Hardcore: items used in battle
        final Map<String, Integer> itemsBattle = new TreeMap<>();
        final Map<String, Integer> itemsField = new TreeMap<>();
        final Map<String, Integer> bought = new TreeMap<>();

        Map<String, Object> toJson() {
            return Json.map("captures", (long) captures, "deaths", (long) deaths, "wildKills", (long) wildKills,
                    "shinies", (long) shinies, "encounters", (long) encounters, "trainersBeaten", (long) trainersBeaten,
                    "badges", (long) badges, "fouls", (long) fouls, "itemsBattle", intMap(itemsBattle),
                    "itemsField", intMap(itemsField), "bought", intMap(bought));
        }

        static Stats fromJson(Map<String, Object> m) {
            Stats s = new Stats();
            if (m == null) {
                return s;
            }
            s.captures = (int) Json.num(m, "captures", 0);
            s.deaths = (int) Json.num(m, "deaths", 0);
            s.wildKills = (int) Json.num(m, "wildKills", 0);
            s.shinies = (int) Json.num(m, "shinies", 0);
            s.encounters = (int) Json.num(m, "encounters", 0);
            s.trainersBeaten = (int) Json.num(m, "trainersBeaten", 0);
            s.badges = (int) Json.num(m, "badges", 0);
            s.fouls = (int) Json.num(m, "fouls", 0);
            readInts(m.get("itemsBattle"), s.itemsBattle);
            readInts(m.get("itemsField"), s.itemsField);
            readInts(m.get("bought"), s.bought);
            return s;
        }
    }

    /** One player of the room. */
    static final class Member {
        String userId;
        String name;
        long joinedAt;
        int slot;
        boolean ready;
        MemberRom rom = new MemberRom(null);
        int[] families = new int[0];
        String status = "playing";  // playing, champion, wiped
        boolean hasBalls;           // has had Poké Balls (the Soul Link starts when everybody has)
        final Map<String, MonRec> mons = new LinkedHashMap<>();
        final List<Map<String, Object>> refunds = new ArrayList<>();
        Stats stats = new Stats();
        Encounter encounter;
        String lastZone;
        /** Sololocke: the Pokémon alone in the team. */
        String soloUid;
        /** Living Pokémon its game must send to the PC (Sololocke, Hardcore level cap), with the reason: solo, cap. */
        final Map<String, String> park = new LinkedHashMap<>();
        /** Hardcore: the levels of the gym leaders (8) and of the League in this player's ROM; null unknown. */
        int[] capGyms;
        int capLeague;
        /** Rule "Exception pour les CS": the next capture the rules forbid is kept, for the HMs. */
        boolean hmNext;
        // live view (not saved)
        Map<String, Object> live = new LinkedHashMap<>();
        /** What the game shows (field, dialogue, battle: from the position sent to the room), null when unknown. */
        String shown;
        /** Hardcore: the whole team is above the level cap (told once). */
        boolean allOver;
        /** The run checked (point 3e): the flags the checks raised, and their memory of this run. */
        final List<Map<String, Object>> flags = new ArrayList<>();
        final Map<String, Object> checkState = new LinkedHashMap<>();

        Map<String, Object> toStore() {
            Map<String, Object> mons = new LinkedHashMap<>();
            for (MonRec r : this.mons.values()) {
                mons.put(r.uid, r.toJson());
            }
            return Json.map("userId", userId, "name", name, "joinedAt", joinedAt, "slot", (long) slot, "ready", ready,
                    "rom", rom.toJson(), "families", families, "status", status, "mons", mons, "refunds", refunds,
                    "stats", stats.toJson(), "lastZone", lastZone, "hasBalls", hasBalls, "soloUid", soloUid,
                    "capGyms", capGyms == null ? null : java.util.Arrays.stream(capGyms).mapToObj(v -> (Object) (long) v).toList(),
                    "capLeague", (long) capLeague, "hmNext", hmNext);
        }

        /** What is stored, with the checks' flags and memory when there are some. */
        Map<String, Object> toStoreChecked() {
            Map<String, Object> m = toStore();
            if (!flags.isEmpty()) {
                m.put("flags", new ArrayList<Object>(flags));
            }
            if (!checkState.isEmpty()) {
                m.put("checkState", checkState);
            }
            return m;
        }

        static Member fromStore(Map<String, Object> m) {
            Member x = new Member();
            x.userId = Json.str(m, "userId");
            x.name = Json.str(m, "name");
            x.joinedAt = Json.num(m, "joinedAt", 0);
            x.slot = (int) Json.num(m, "slot", 1);
            x.ready = Json.bool(m, "ready", false);
            Map<String, Object> rom = Json.obj(m.get("rom"));
            if (rom != null) {
                x.rom = new MemberRom(rom);
            }
            List<Object> fam = Json.arr(m.get("families"));
            if (fam != null) {
                x.families = new int[fam.size()];
                for (int i = 0; i < x.families.length; i++) {
                    x.families[i] = fam.get(i) instanceof Number n ? n.intValue() : 0;
                }
            }
            x.status = Json.str(m, "status") == null ? "playing" : Json.str(m, "status");
            x.hasBalls = Json.bool(m, "hasBalls", false);
            Map<String, Object> mons = Json.obj(m.get("mons"));
            if (mons != null) {
                for (Object o : mons.values()) {
                    MonRec r = MonRec.fromJson(Json.obj(o));
                    x.mons.put(r.uid, r);
                }
            }
            List<Object> refunds = Json.arr(m.get("refunds"));
            if (refunds != null) {
                for (Object o : refunds) {
                    x.refunds.add(Json.obj(o));
                }
            }
            x.stats = Stats.fromJson(Json.obj(m.get("stats")));
            x.lastZone = Json.str(m, "lastZone");
            x.soloUid = Json.str(m, "soloUid");
            x.capGyms = ints(Json.arr(m.get("capGyms")), 8);
            x.capLeague = (int) Json.num(m, "capLeague", 0);
            x.hmNext = Json.bool(m, "hmNext", false);
            List<Object> flags = Json.arr(m.get("flags"));
            if (flags != null) {
                for (Object f : flags) {
                    x.flags.add(Json.obj(f));
                }
            }
            Map<String, Object> checkState = Json.obj(m.get("checkState"));
            if (checkState != null) {
                x.checkState.putAll(checkState);
            }
            return x;
        }

        int family(int species) {
            return species > 0 && species < families.length && families[species] > 0 ? families[species] : species;
        }
    }

    /** What an operation produced: log lines, messages to players, state changes. */
    static final class Out {
        final List<Map<String, Object>> logs = new ArrayList<>();
        final Map<String, List<Map<String, Object>>> notices = new LinkedHashMap<>();
        boolean changed;
        boolean ended;

        void log(Member m, String type, String text, Map<String, Object> data) {
            logs.add(Json.map("user", m == null ? null : m.userId, "name", m == null ? null : m.name, "type", type,
                    "text", text, "data", data));
            changed = true;
        }

        void notice(Member m, String level, String text) {
            notices.computeIfAbsent(m.userId, k -> new ArrayList<>()).add(Json.map("level", level, "text", text));
        }
    }

    // ------------------------------------------------------------------ state

    String id;
    String code;
    String name;
    String leaderId;
    /** Game mode of the room (each mode has its tab in the interface). */
    String mode = "soullink";
    /** The file of a mode imported for this room (point 2b), or null: the official mode of {@link #mode}. */
    ModeSpec customSpec;
    /** Whether an imported mode is in the gallery of public modes (set by the server). */
    static volatile java.util.function.Predicate<ModeSpec> publishedMode = s -> false;
    /** The checks of the runs (point 3e), loaded by the server: none on a server published with Cable Club. */
    static volatile List<RunCheck> checks = List.of();
    /** Flags one run keeps at most (the first ones tell enough). */
    static final int MAX_FLAGS = 50;
    Map<String, Object> unreadMode;   // an imported mode this version cannot read (the room follows its family)
    /** "public": listed in the public rooms, anybody may join while it is a lobby; "private": invitation or code. */
    String visibility = "private";
    long createdAt;
    long launchedAt;
    RoomGame game = new RoomGame(null);
    int maxPlayers = 4;
    String state = "lobby";     // lobby, running, finished, abandoned
    boolean adventureOpen;
    boolean linkStarted;        // every player has had a Poké Ball: deaths now count
    Map<String, Object> rules = defaultRules();
    Map<String, Object> rando = new LinkedHashMap<>();
    final List<Member> members = new ArrayList<>();
    final Set<String> invites = new LinkedHashSet<>();
    final Set<String> history = new LinkedHashSet<>();   // everybody who played in the room
    final Map<String, Zone> zones = new LinkedHashMap<>();
    /** Trades between players (switched off: see {@link RoomTrades#ENABLED}). */
    final RoomTrades trades = new RoomTrades(this);
    final Deque<String> namingQueue = new ArrayDeque<>();
    RoomEnd ended;
    long logSeq;
    long refundSeq;

    Member member(String userId) {
        for (Member m : members) {
            if (m.userId.equals(userId)) {
                return m;
            }
        }
        return null;
    }

    boolean isLeader(String userId) {
        return leaderId.equals(userId);
    }

    /** The file of the room's mode: imported for it, or official. */
    ModeSpec spec() {
        return customSpec != null ? customSpec : officialOrSoulLink(mode);
    }

    boolean rule(String key) {
        Object v = rules.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.TRUE.equals(spec().defaultOf(key));
    }

    String ruleText(String key) {
        Object v = rules.get(key);
        return v == null ? String.valueOf(spec().defaultOf(key)) : v.toString();
    }

    String runName() {
        return spec().name;
    }

    /** Do the deaths count for this player? Soul Link: once everybody has a Poké Ball; Nuzlocke: once they have theirs. */
    boolean started(Member m) {
        return Mode.forRoom(this).run().started(this, m);
    }

    /**
     * The leader changes rules: only the rules of the room's mode count; a rule the mode fixes does not change; a value
     * must be one the rule allows, and a rule that makes no sense with the mode's primitives keeps its neutral value; a
     * rule written in the ROM changes only in the lobby; a rule the game cannot apply is never turned on (see
     * {@link Capabilities}). Returns the rules changed.
     */
    List<String> changeRules(Member leader, String leaderName, Map<String, Object> asked, Out out) {
        ModeSpec spec = spec();
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> e : asked.entrySet()) {
            if (!spec.offers(e.getKey())) {
                continue;
            }
            Object before = rules.get(e.getKey());
            if (before == null ? e.getValue() != null : !before.equals(e.getValue())) {
                if (spec.locked(e.getKey())) {
                    throw new Http.Fail(409, "Cette règle est fixée par le mode « " + spec.name + " ».");
                }
                if (!ModeSpec.allows(e.getKey(), e.getValue())) {
                    throw new Http.Fail(400, "Valeur impossible pour la règle « " + e.getKey() + " ».");
                }
                if (!spec.applies(e.getKey()) && !e.getValue().equals(ModeSpec.neutral(e.getKey()))) {
                    throw new Http.Fail(409, "Cette règle n'a pas de sens dans le mode « " + spec.name + " ».");
                }
                if (ROM_RULES.contains(e.getKey()) && !"lobby".equals(state)) {
                    throw new Http.Fail(409, "Cette règle est écrite dans la ROM de la salle : elle ne peut plus changer une fois l'aventure lancée.");
                }
                if (Boolean.TRUE.equals(e.getValue())) {
                    String missing = Capabilities.missingForRule(e.getKey(), game.gameId(), game.gen());
                    if (missing != null) {
                        throw new Http.Fail(409, "Règle indisponible avec " + gameName() + " : " + missing + ".");
                    }
                }
                rules.put(e.getKey(), e.getValue());
                changed.add(e.getKey());
            }
        }
        if (!changed.isEmpty() && !"lobby".equals(state)) {
            out.log(leader, "rules", leaderName + " modifie les règles : " + String.join(", ", changed), null);
        }
        return changed;
    }

    /** The adventure starts (the leader launched it; {@link Rooms} checked the players and their ROMs). */
    void launch(Out out) {
        invites.clear();
        state = "running";
        adventureOpen = true;
        launchedAt = System.currentTimeMillis();
        out.log(null, "launch", "L'aventure commence ! " + members.size() + " joueur(s) sur " + gameName() + ".", null);
        if (rule("startWithBalls")) {
            out.log(null, "info", Mode.forRoom(this).run().launchNotice(this), null);
        }
        checkStart(out);
    }

    String gameName() {
        String n = game.name();
        return n == null ? "Pokémon" : n;
    }

    // ---------------------------------------------------------------- events

    /** Applies one tracker event of a player, through the room's mode (an ordered list of rules). */
    void event(Member m, Map<String, Object> ev, Out out) {
        if (!"running".equals(state)) {
            return;
        }
        if (Json.str(ev, "type") == null) {
            return;
        }
        Mode.forRoom(this).handle(new Mode.Ctx(this, m, out), ev);
        if (!checks.isEmpty()) {
            check(m, ev);
        }
    }

    /** The checks look at the event (point 3e): they may flag the run, never change it. */
    private void check(Member m, Map<String, Object> ev) {
        RunCheck.Run run = new RunCheck.Run(id, m.userId, mode, game.gameId(), game.gen(),
                java.util.Collections.unmodifiableMap(rules), m.checkState, System.currentTimeMillis());
        for (RunCheck c : checks) {
            List<RunCheck.Flag> raised;
            try {
                raised = c.check(run, ev);
            } catch (RuntimeException | StackOverflowError e) {
                Log.warn("Contrôle des parties " + c.getClass().getName() + " : " + e);
                continue;
            }
            for (RunCheck.Flag f : raised == null ? List.<RunCheck.Flag>of() : raised) {
                if (f != null && m.flags.size() < MAX_FLAGS) {
                    m.flags.add(Json.map("code", f.code(), "text", f.text(), "at", run.now()));
                    Log.info("Contrôle des parties : salle " + id + ", " + m.name + " (" + f.code() + ")");
                }
            }
        }
    }

    /** The per-type handlers, run by the mode's rules (see {@link Mode}). */
    void dispatch(Member m, Map<String, Object> ev, Out out) {
        switch (Json.str(ev, "type")) {
            case "location" -> location(m, ev, out);
            case "capture" -> capture(m, ev, out);
            case "update" -> update(m, ev, out);
            case "faint" -> faint(m, ev, out);
            case "party", "team" -> live(m, ev, out);
            case "badges" -> badges(m, ev, out);
            case "whiteout" -> whiteout(m, out);
            case "battle" -> battle(m, ev, out);
            case "items" -> items(m, ev, out);
            case "champion" -> champion(m, out);
            case "balls" -> balls(m, ev, out);
            case "caps" -> caps(m, ev, out);
            case "removed", "held", "forgot" -> ruled(m, ev, out);
            default -> {
                // unknown event: ignored
            }
        }
    }

    private void location(Member m, Map<String, Object> ev, Out out) {
        String zone = Json.str(ev, "zone");
        m.live.put("zone", zone);
        if (zone == null || zone.equals(m.lastZone)) {
            return;
        }
        m.lastZone = zone;
        Zone z = zones.get(zoneKey(zone));
        String hint = "";
        if (z != null) {
            Entry e = z.entries.get(m.userId);
            if ("cancelled".equals(z.status)) {
                hint = " — zone annulée";
            } else if ("dead".equals(z.status)) {
                hint = " — lien perdu";
            } else {
                hint = Mode.forRoom(this).encounters().zoneHint(this, z, e);
            }
        }
        out.log(m, "zone", m.name + " entre dans " + zone + hint, Json.map("zone", zone));
    }

    /** The bag holds Poké Balls (sent by the game when it changes). */
    private void balls(Member m, Map<String, Object> ev, Out out) {
        if (!Json.bool(ev, "has", false) || m.hasBalls) {
            return;
        }
        m.hasBalls = true;
        out.log(m, "balls", m.name + " a ses premières Poké Balls", null);
        checkStart(out);
    }

    /** The run starts when its mode says so (Soul Link: once every player has had a Poké Ball; until then, nobody dies). */
    void checkStart(Out out) {
        if (linkStarted || !"running".equals(state)) {
            return;
        }
        Mode.forRoom(this).run().checkStart(this, out);
    }

    /** Does this wild Pokémon count for its zone? The room's mode decides (see {@link Mode.EncounterLogic}). */
    private String classify(Member m, String key, int species, boolean shiny) {
        return Mode.forRoom(this).encounters().classify(this, m, key, species, shiny);
    }

    /** An evolution line already caught by someone in the room (encountering player's evolution data). */
    boolean ownedFamily(Member m, int species, String zoneKey, boolean everyone) {
        int fam = m.family(species);
        for (Member other : members) {
            if (!everyone && other != m) {
                continue;
            }
            for (MonRec r : other.mons.values()) {
                if ("released".equals(r.status) && r.reason != null && r.reason.startsWith("interdit")) {
                    continue;
                }
                if (zoneKey.equals(r.zoneKey)) {
                    continue; // the partner linked through this very zone is not a duplicate
                }
                if (!"existing".equals(r.origin) && m.family(r.species) == fam) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Zone cancelled: the Pokémon caught there are released, items used to catch them are given back. */
    void cancel(Zone z, Out out) {
        if ("cancelled".equals(z.status)) {
            return;
        }
        z.status = "cancelled";
        namingQueue.remove(z.key);
        for (Map.Entry<String, Entry> x : z.entries.entrySet()) {
            Member owner = member(x.getKey());
            Entry e = x.getValue();
            if (owner == null || !"caught".equals(e.result)) {
                continue;
            }
            MonRec r = owner.mons.get(e.uid);
            if (r != null && "alive".equals(r.status)) {
                r.status = "released";
                r.reason = "zone annulée";
            }
            if (rule("refundCancelled") && !e.items.isEmpty()) {
                refund(owner, e.items, e.itemNames, out);
            }
        }
        out.log(null, "cancel", z.name + " est annulée : les Pokémon capturés là sont relâchés.",
                Json.map("zone", z.name));
    }

    private void refund(Member owner, Map<String, Integer> items, Map<String, String> names, Out out) {
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Integer> it : items.entrySet()) {
            owner.refunds.add(Json.map("id", Long.toString(++refundSeq), "item", Long.parseLong(it.getKey()),
                    "name", names.get(it.getKey()), "qty", (long) (int) it.getValue()));
            parts.add(it.getValue() + "× " + (names.get(it.getKey()) == null ? "objet #" + it.getKey() : names.get(it.getKey())));
        }
        out.log(owner, "refund", owner.name + " récupère " + String.join(", ", parts), null);
        out.notice(owner, "info", "Objets rendus : " + String.join(", ", parts));
    }

    private void capture(Member m, Map<String, Object> ev, Out out) {
        String uid = Json.str(ev, "uid");
        if (uid == null || m.mons.containsKey(uid)) {
            return;
        }
        String origin = Json.str(ev, "origin");
        MonRec r = new MonRec();
        r.uid = uid;
        r.species = (int) Json.num(ev, "species", 0);
        r.speciesName = Json.str(ev, "speciesName");
        r.level = (int) Json.num(ev, "level", 0);
        r.nickname = Json.str(ev, "nickname");
        r.shiny = Json.bool(ev, "shiny", false);
        r.origin = origin;
        r.at = System.currentTimeMillis();
        String zone = zoneName(Json.str(ev, "zone"));
        r.zone = zone;
        m.mons.put(uid, r);

        if ("existing".equals(origin)) {
            // already in the save when the tracking started: known, not judged
            out.log(m, "existing", m.name + " a déjà " + r.label(), null);
            return;
        }
        boolean first = m.mons.values().stream().noneMatch(x -> x != r && !"existing".equals(x.origin));
        Encounter e = m.encounter;
        boolean wild = "wild".equals(origin) || (e != null && uid.equals(e.uid));
        Mode.CaptureLogic captures = Mode.forRoom(this).captures();
        if (!wild && first) {
            r.zone = "Départ";
            captures.starter(this, m, r, out);
            return;
        }
        if (!wild) {
            captures.gift(this, m, r, zone, out);
            return;
        }
        if (!m.hasBalls) {
            // caught in the wild: this player obviously has Poké Balls
            m.hasBalls = true;
            checkStart(out);
        }
        String kind;
        Encounter enc = e != null && uid.equals(e.uid) ? e : null;
        if (enc != null) {
            kind = enc.kind;
            r.zone = enc.zone;
        } else {
            kind = classify(m, zoneKey(zone), r.species, r.shiny);
        }
        if ("noballs".equals(kind)) {
            kind = classify(m, zoneKey(r.zone), r.species, r.shiny);
        }
        m.encounter = null;
        m.live.remove("battle");
        // "Notepad Clause": no capture while the team is full (the Pokémon caught went to the PC)
        boolean full = captures.notepad(this) && !Json.bool(ev, "inParty", true);
        if (keepForHms(m, r, kind, full, enc, out)) {
            return;
        }
        if (full && ("zone".equals(kind) || "shiny".equals(kind))) {
            if ("zone".equals(kind)) {
                link(m, r, zone(zoneKey(r.zone), r.zone), enc, out); // the chance of the zone is used all the same
            }
            release(m, r, "interdit : équipe pleine", out);
            return;
        }
        switch (kind) {
            case "zone" -> link(m, r, zone(zoneKey(r.zone), r.zone), enc, out);
            case "shiny" -> bonus(m, r, "chromatique", out);
            case "legendary" -> release(m, r, "interdit : légendaire", out);
            case "dupe" -> release(m, r, "interdit : doublon", out);
            case "notshiny" -> release(m, r, "interdit : pas chromatique", out);
            case "cancelled" -> {
                release(m, r, "interdit : zone annulée", out);
                if (enc != null && rule("refundCancelled") && !enc.items.isEmpty()) {
                    refund(m, enc.items, enc.itemNames, out);
                }
            }
            default -> release(m, r, "interdit : zone déjà utilisée", out);
        }
    }

    /**
     * Rule "Exception pour les CS": the player asked to keep their next capture the rules forbid (zone already used,
     * duplicate, not shiny, team full) for the HMs. It counts for no zone, and may never gain experience.
     */
    private boolean keepForHms(Member m, MonRec r, String kind, boolean full, Encounter enc, Out out) {
        boolean forbidden = full || "used".equals(kind) || "dupe".equals(kind) || "notshiny".equals(kind);
        if (!Mode.forRoom(this).captures().hmException(this) || !m.hmNext || !forbidden) {
            return false;
        }
        if ("zone".equals(kind)) {
            link(m, r, zone(zoneKey(r.zone), r.zone), enc, out); // team full: the chance of the zone is used all the same
        } else {
            bonus(m, r, "Pokémon de CS", out);
        }
        r.hm = true;
        m.hmNext = false;
        out.log(m, "hm", r.label() + " de " + m.name + " est gardé pour les CS : il ne doit jamais gagner d'expérience", null);
        out.notice(m, "info", r.label() + " est votre Pokémon de CS : s'il gagne de l'expérience (combat, Multi Exp, Super Bonbon), il est relâché.");
        return true;
    }

    /** A Pokémon kept for the HMs gained experience: it is released. */
    private void hmBroken(Member m, MonRec r, Out out) {
        r.status = "released";
        r.reason = "Pokémon de CS qui a gagné de l'expérience";
        out.log(m, "release", r.label() + " de " + m.name + " a gagné de l'expérience : un Pokémon de CS ne combat pas, il est relâché", null);
        out.notice(m, "warn", r.label() + " a gagné de l'expérience : un Pokémon de CS ne doit pas combattre. Il est relâché.");
    }

    /** Rule "Exception pour les CS": the player keeps (or no longer keeps) their next forbidden capture for the HMs. */
    void hmNext(Member m, boolean on, Out out) {
        if (!"running".equals(state) || !Mode.forRoom(this).captures().hmException(this)) {
            throw new Http.Fail(409, "L'exception pour les CS n'est pas active dans cette salle.");
        }
        if (m.hmNext == on) {
            return;
        }
        m.hmNext = on;
        out.changed = true;
        out.notice(m, "info", on ? "Pokémon de CS : votre prochaine capture interdite est gardée pour les CS. Il ne devra jamais gagner d'expérience."
                : "Pokémon de CS annulé : les captures interdites sont de nouveau relâchées.");
        if (on) {
            out.log(m, "hm", m.name + " va capturer un Pokémon pour les CS", null);
        }
    }

    /** The Pokémon becomes this player's Pokémon for the zone (then the mode says what linking it means). */
    void link(Member m, MonRec r, Zone z, Encounter enc, Out out) {
        r.zoneKey = z.key;
        Entry entry = new Entry();
        entry.result = "caught";
        entry.uid = r.uid;
        entry.species = r.species;
        entry.speciesName = r.speciesName;
        entry.level = r.level;
        entry.shiny = r.shiny;
        entry.at = r.at;
        if (enc != null) {
            entry.items.putAll(enc.items);
            entry.itemNames.putAll(enc.itemNames);
        }
        z.entries.put(m.userId, entry);
        m.stats.captures++;
        if (r.shiny) {
            m.stats.shinies++;
        }
        out.log(m, "capture", m.name + " capture " + r.label() + " N." + r.level + " (" + z.name + ")",
                Json.map("zone", z.name, "species", (long) r.species, "speciesName", r.speciesName, "level", (long) r.level,
                        "shiny", r.shiny));
        Mode.forRoom(this).captures().linked(this, m, r, z, out);
    }

    void bonus(Member m, MonRec r, String why, Out out) {
        r.bonus = true;
        m.stats.captures++;
        if (r.shiny) {
            m.stats.shinies++;
        }
        out.log(m, "capture", m.name + " capture " + r.label() + " N." + r.level + " — " + why
                        + Mode.forRoom(this).captures().bonusSuffix(),
                Json.map("species", (long) r.species, "speciesName", r.speciesName, "level", (long) r.level,
                        "shiny", r.shiny, "bonus", true));
    }

    void release(Member m, MonRec r, String why, Out out) {
        r.status = "released";
        r.reason = why;
        out.log(m, "release", r.label() + " de " + m.name + " est relâché (" + why.replace("interdit : ", "") + ")", null);
        out.notice(m, "warn", "Capture non autorisée (" + why.replace("interdit : ", "") + ") : ce Pokémon sera retiré de l'équipe.");
    }

    /** Recomputes the status of a zone after a change. */
    void refreshZone(Zone z, Out out) {
        if ("cancelled".equals(z.status) || "dead".equals(z.status)) {
            return;
        }
        int caught = 0;
        for (Member mm : members) {
            Entry e = z.entries.get(mm.userId);
            if (e != null && "caught".equals(e.result)) {
                caught++;
            }
        }
        if (caught == 0) {
            z.status = "open";
            return;
        }
        if (caught < members.size()) {
            z.status = "waiting";
            return;
        }
        if (z.nickname == null && "leader".equals(ruleText("naming"))) {
            if (!"naming".equals(z.status)) {
                z.status = "naming";
                if (!namingQueue.contains(z.key)) {
                    namingQueue.add(z.key);
                }
                out.log(null, "link", "Tout le monde a capturé son Pokémon de " + z.name + " : le chef de salle choisit son nom.", null);
            }
            return;
        }
        if (!"linked".equals(z.status)) {
            z.status = "linked";
            out.log(null, "link", "Lien formé : " + z.name + (z.nickname != null ? " « " + z.nickname + " »" : ""), null);
        }
    }

    /** Name chosen by the leader for the link of a zone. */
    void name(String zoneKey, String nickname, Out out) {
        Zone z = zones.get(zoneKey);
        if (z == null) {
            throw new Http.Fail(404, "Zone inconnue");
        }
        String n = nickname == null ? "" : nickname.replaceAll("\\p{Cntrl}", "").trim();
        if (n.isEmpty() || n.length() > 10) {
            throw new Http.Fail(400, "Le surnom doit faire de 1 à 10 caractères.");
        }
        z.nickname = n;
        namingQueue.remove(z.key);
        for (Map.Entry<String, Entry> x : z.entries.entrySet()) {
            Member owner = member(x.getKey());
            MonRec r = owner == null ? null : owner.mons.get(x.getValue().uid);
            if (r != null) {
                r.nickname = n;
            }
        }
        if ("naming".equals(z.status)) {
            z.status = "linked";
        }
        out.log(null, "name", "Lien de " + z.name + " nommé « " + n + " »", Json.map("zone", z.name, "nickname", n));
    }

    private void update(Member m, Map<String, Object> ev, Out out) {
        MonRec r = m.mons.get(Json.str(ev, "uid"));
        if (r == null) {
            return;
        }
        int species = (int) Json.num(ev, "species", r.species);
        boolean evolved = Json.bool(ev, "evolved", false) && species != r.species;
        String before = r.label();
        int level = r.level;
        r.level = (int) Json.num(ev, "level", r.level);
        if (evolved) {
            r.species = species;
            r.speciesName = Json.str(ev, "speciesName");
            out.log(m, "evolution", before + " de " + m.name + " évolue en " + r.speciesName, null);
        }
        if (r.hm && "alive".equals(r.status) && level > 0 && r.level > level) {
            hmBroken(m, r, out); // a level gained: experience too (the team view also tells it, without a level)
            return;
        }
        if (r.level != level && levelCap(m) > 0) {
            parkCheck(m, out);
        }
    }

    private void faint(Member m, Map<String, Object> ev, Out out) {
        MonRec r = m.mons.get(Json.str(ev, "uid"));
        if (r == null || !"alive".equals(r.status) || "existing".equals(r.origin)) {
            return;
        }
        Mode.RunLogic run = Mode.forRoom(this).run();
        if (!started(m)) {
            out.log(m, "faint", r.label() + " de " + m.name + " est K.O., sans conséquence : le " + runName() + " n'a pas encore commencé.", null);
            out.notice(m, "info", r.label() + " est K.O. mais ne meurt pas : le " + runName() + " commence " + run.startsWhen());
            return;
        }
        r.status = "dead";
        r.reason = "K.O.";
        m.stats.deaths++;
        out.log(m, "death", "☠ " + r.label() + " de " + m.name + " est mort" + (r.zone != null ? " (" + r.zone + ")" : ""), null);
        run.died(this, m, r, out);
    }

    private void live(Member m, Map<String, Object> ev, Out out) {
        if ("team".equals(Json.str(ev, "type"))) {
            m.live.put("team", ev.get("mons"));
            hmCheck(m, Json.arr(ev.get("mons")), out);
        } else {
            m.live.put("party", ev.get("uids"));
            parkCheck(m, out);
        }
        m.live.put("at", System.currentTimeMillis());
    }

    /** The Pokémon kept for the HMs never gain experience: the team view gives their experience points. */
    private void hmCheck(Member m, List<Object> team, Out out) {
        if (team == null) {
            return;
        }
        for (Object o : team) {
            Map<String, Object> x = Json.obj(o);
            MonRec r = x == null ? null : m.mons.get(Json.str(x, "uid"));
            Map<String, Object> set = x == null ? null : Json.obj(x.get("set"));
            long exp = set == null ? -1 : Json.num(set, "exp", -1);
            if (r == null || !r.hm || !"alive".equals(r.status) || exp < 0) {
                continue;
            }
            if (r.hmExp < 0) {
                r.hmExp = exp;
                out.changed = true;
            } else if (exp > r.hmExp) {
                hmBroken(m, r, out);
            }
        }
    }

    /**
     * The living Pokémon of the team that its game must send to the PC (directive "park"). Sololocke with "les autres
     * vont au PC": one living Pokémon in the team, the one that was alone stays (or the first one). Hardcore: a Pokémon
     * above the level cap goes to the PC until the next badge raises it (a team always keeps one Pokémon: the lowest).
     */
    void parkCheck(Member m, Out out) {
        Map<String, String> before = new LinkedHashMap<>(m.park);
        m.park.clear();
        List<Object> party = Json.arr(m.live.get("party"));
        Mode.RunLogic run = Mode.forRoom(this).run();
        if (party == null || !run.parksTeam() || !started(m)) {
            return;
        }
        List<String> alive = new ArrayList<>();
        for (Object o : party) {
            String uid = String.valueOf(o);
            MonRec r = m.mons.get(uid);
            if (r == null || ("alive".equals(r.status) && !r.hm)) {
                alive.add(uid); // a Pokémon kept for the HMs stays in the team: it never fights
            }
        }
        if (alive.isEmpty()) {
            return;
        }
        int cap = levelCap(m);
        List<String> fit = new ArrayList<>();
        for (String uid : alive) {
            if (cap <= 0 || levelOf(m, uid) <= cap) {
                fit.add(uid);
            }
        }
        String lowest = alive.stream().min(java.util.Comparator.comparingInt(u -> levelOf(m, u))).orElse(alive.get(0));
        String kept = null;
        if (run.soloTeam(this)) {
            String keep = m.soloUid != null && fit.contains(m.soloUid) ? m.soloUid
                    : !fit.isEmpty() ? fit.get(0) : m.soloUid != null && alive.contains(m.soloUid) ? m.soloUid : lowest;
            m.soloUid = keep;
            kept = keep;
            for (String uid : alive) {
                if (!uid.equals(keep)) {
                    m.park.put(uid, fit.contains(uid) ? "solo" : "cap");
                }
            }
        } else if (fit.size() < alive.size()) {
            kept = fit.isEmpty() ? lowest : null;
            for (String uid : alive) {
                if (!fit.contains(uid) && !uid.equals(kept)) {
                    m.park.put(uid, "cap");
                }
            }
        }
        boolean soloNotice = false;
        for (Map.Entry<String, String> e : m.park.entrySet()) {
            if (before.containsKey(e.getKey())) {
                continue;
            }
            MonRec r = m.mons.get(e.getKey());
            String label = r == null ? "Un Pokémon" : r.label();
            if ("cap".equals(e.getValue())) {
                int level = levelOf(m, e.getKey());
                out.notice(m, "warn", "Hardcore : " + label + " (niv. " + level + ") dépasse le plafond de niveau (" + cap
                        + ") : il va au PC jusqu'au prochain badge.");
                out.log(m, "cap", label + " de " + m.name + " dépasse le plafond de niveau (" + level + " > " + cap
                        + ") : envoyé au PC", null);
            } else {
                soloNotice = true;
            }
        }
        if (soloNotice) {
            MonRec r = m.mons.get(m.soloUid);
            out.notice(m, "info", runName() + " : un seul Pokémon dans l'équipe" + (r != null ? " (" + r.label() + ")" : "")
                    + ", les autres vont au PC.");
        }
        boolean allOver = cap > 0 && fit.isEmpty();
        if (allOver && !m.allOver) {
            MonRec r = m.mons.get(kept);
            out.notice(m, "warn", "Hardcore : toute l'équipe dépasse le plafond de niveau (" + cap + ")"
                    + (r != null ? " : " + r.label() + " reste seul dans l'équipe." : "."));
        }
        m.allOver = allOver;
    }

    /** Level of a Pokémon of the player (as the referee knows it, else as its team shows it). */
    private static int levelOf(Member m, String uid) {
        MonRec r = m.mons.get(uid);
        if (r != null && r.level > 0) {
            return r.level;
        }
        List<Object> team = Json.arr(m.live.get("team"));
        if (team != null) {
            for (Object o : team) {
                Map<String, Object> x = Json.obj(o);
                if (x != null && uid.equals(Json.str(x, "uid"))) {
                    return (int) Json.num(x, "level", 0);
                }
            }
        }
        return 0;
    }

    /**
     * Hardcore: the level no Pokémon of the team may pass right now, the strongest Pokémon of the next gym leader
     * (from the player's ROM), then of the League once the 8 badges are won; -1 when there is none (rule off, levels
     * unknown, Champion beaten).
     */
    int levelCap(Member m) {
        if (!Mode.forRoom(this).run().hardcore(this) || m.capGyms == null || "champion".equals(m.status)) {
            return -1;
        }
        int b = Math.max(0, m.stats.badges);
        int cap = b < 8 ? m.capGyms[b] : m.capLeague;
        return cap > 0 ? cap : -1;
    }

    /** The levels of the gym leaders and of the League in the player's ROM (sent by the tracker of their game). */
    private void caps(Member m, Map<String, Object> ev, Out out) {
        int[] gyms = ints(Json.arr(ev.get("gyms")), 8);
        if (gyms == null) {
            return;
        }
        int league = (int) Json.num(ev, "league", 0);
        if (m.capGyms != null && java.util.Arrays.equals(gyms, m.capGyms) && m.capLeague == league) {
            return;
        }
        m.capGyms = gyms;
        m.capLeague = league;
        out.changed = true;
        parkCheck(m, out);
    }

    static int[] ints(List<Object> l, int size) {
        if (l == null || l.size() != size) {
            return null;
        }
        int[] out = new int[size];
        for (int i = 0; i < size; i++) {
            out[i] = l.get(i) instanceof Number n ? n.intValue() : 0;
        }
        return out;
    }

    /** One Pokémon in the team: the player's Pokémon is dead. Lost run, or another one of the PC takes over (if any). */
    void soloFallen(Member m, MonRec r, Out out) {
        if ("wiped".equals(m.status) || !"running".equals(state)) {
            return; // already lost, or the room just ended (a linked death ended it before this player's turn)
        }
        long alive = m.mons.values().stream().filter(x -> "alive".equals(x.status)).count();
        if ("end".equals(ruleText("soloLoss")) || alive == 0) {
            m.status = "wiped";
            out.log(m, "wipe", "☠ " + m.name + " a perdu son " + runName() + (alive == 0 ? " : il ne lui reste aucun Pokémon." : "."), null);
            Mode.forRoom(this).run().playerLost(this, m, out);
            return;
        }
        out.notice(m, "warn", r.label() + " est mort : prenez un autre Pokémon vivant dans le PC pour continuer.");
        out.log(m, "solo", m.name + " doit choisir un nouveau Pokémon dans le PC", null);
    }

    private void badges(Member m, Map<String, Object> ev, Out out) {
        int count = (int) Json.num(ev, "count", 0);
        if (count > m.stats.badges) {
            m.stats.badges = count;
            out.log(m, "badge", m.name + " obtient son " + ordinal(count) + " badge", Json.map("count", (long) count));
            int cap = levelCap(m);
            if (cap > 0) {
                out.notice(m, "info", "Hardcore : nouveau plafond de niveau, " + cap + ".");
                parkCheck(m, out);
            }
        }
    }

    private void whiteout(Member m, Out out) {
        out.log(m, "whiteout", "L'équipe de " + m.name + " est entièrement K.O.", null);
        Mode.forRoom(this).run().whiteout(this, m, out);
    }

    private void battle(Member m, Map<String, Object> ev, Out out) {
        String kind = Json.str(ev, "kind");
        String phase = Json.str(ev, "phase");
        if (!"trainer".equals(kind)) {
            return; // wild battles are logged as encounters
        }
        String trainer = Json.str(ev, "trainer");
        String against = trainer == null || trainer.isBlank() ? "un dresseur" : trainer;
        if ("start".equals(phase)) {
            m.live.put("battle", "trainer");
            out.log(m, "battle", m.name + " lance un combat contre " + against, null);
        } else {
            m.live.remove("battle");
            String outcome = Json.str(ev, "outcome");
            if ("won".equals(outcome)) {
                m.stats.trainersBeaten++;
                out.log(m, "win", m.name + " gagne le combat contre " + against, null);
            } else if ("lost".equals(outcome)) {
                out.log(m, "loss", m.name + " perd le combat contre " + against, null);
            }
        }
    }

    private void items(Member m, Map<String, Object> ev, Out out) {
        List<Object> changes = Json.arr(ev.get("changes"));
        if (changes == null) {
            return;
        }
        long money = Json.num(ev, "money", 0);
        boolean inBattle = Json.bool(ev, "battle", false);
        for (Object o : changes) {
            Map<String, Object> c = Json.obj(o);
            int delta = (int) Json.num(c, "delta", 0);
            String itemId = String.valueOf(Json.num(c, "item", 0));
            String name = Json.str(c, "name") == null ? "objet #" + itemId : Json.str(c, "name");
            if (delta < 0) {
                int qty = -delta;
                if (money > 0) {
                    out.log(m, "sell", m.name + " vend " + qty + "× " + name, Json.map("item", name, "qty", (long) qty));
                    continue;
                }
                if (inBattle) {
                    m.stats.itemsBattle.merge(name, qty, Integer::sum);
                    Encounter e = m.encounter;
                    if (e != null) {
                        e.items.merge(itemId, qty, Integer::sum);
                        e.itemNames.put(itemId, name);
                    }
                } else {
                    m.stats.itemsField.merge(name, qty, Integer::sum);
                }
                out.log(m, "item", m.name + " utilise " + qty + "× " + name + (inBattle ? " en combat" : ""),
                        Json.map("item", name, "qty", (long) qty, "battle", inBattle));
                if (fight(m, ev) && !ball(Json.num(c, "item", 0))) {
                    foul(m, name, qty, out);
                }
            } else if (delta > 0) {
                if (money < 0) {
                    m.stats.bought.merge(name, delta, Integer::sum);
                    out.log(m, "buy", m.name + " achète " + delta + "× " + name, Json.map("item", name, "qty", (long) delta));
                } else {
                    out.log(m, "obtain", m.name + " obtient " + delta + "× " + name, Json.map("item", name, "qty", (long) delta));
                }
            }
        }
    }

    /** What the classic rules changed in the player's game: an item out of the bag or off a Pokémon, Fly forgotten. */
    private void ruled(Member m, Map<String, Object> ev, Out out) {
        String name = Json.str(ev, "name") == null ? "un objet" : Json.str(ev, "name");
        String rule = slt.player.track.ItemRules.label(Json.str(ev, "rule"));
        MonRec r = m.mons.get(Json.str(ev, "uid"));
        String who = r != null ? r.label() : Json.str(ev, "nickname") != null ? Json.str(ev, "nickname") : "un Pokémon";
        String text = switch (String.valueOf(Json.str(ev, "type"))) {
            case "removed" -> "🚫 " + Json.num(ev, "qty", 1) + "× " + name + " retiré du sac de " + m.name + " (" + rule + ")";
            case "held" -> "held".equals(Json.str(ev, "rule"))
                    ? "🎒 " + name + " de " + who + " (" + m.name + ") remis dans le sac (pas d'objet tenu)"
                    : "🚫 " + name + " retiré à " + who + " de " + m.name + " (" + rule + ")";
            default -> "🚫 " + who + " de " + m.name + " oublie Vol (pas de Vol)";
        };
        out.log(m, "rule", text, null);
    }

    /**
     * Was the player in a battle when the item went? The game tells (generations 1 to 3: "fight"); the DS games do not:
     * what their screen shows then (field or battle, from the position sent to the room). Unknown: no (the tracker's
     * own guess on the DS lasts until the next map, it would count the items used after a wild battle).
     */
    private boolean fight(Member m, Map<String, Object> ev) {
        if (ev.containsKey("fight")) {
            return Json.bool(ev, "fight", false);
        }
        return "battle".equals(m.shown);
    }

    /** Poké Balls of the room's game (thrown in battle: always allowed). */
    boolean ball(long item) {
        int id = (int) item;
        return switch (game.gen()) {
            case 1 -> (id >= 1 && id <= 4) || id == 8;
            case 2 -> Set.of(1, 2, 4, 5, 157, 159, 160, 161, 164, 165, 166, 177).contains(id);
            case 3 -> id >= 1 && id <= 12;
            default -> (id >= 1 && id <= 16) || (id >= 492 && id <= 500) || id == 576;
        };
    }

    /** Hardcore: an item of the bag used in a battle (held items and Poké Balls are allowed). */
    private void foul(Member m, String name, int qty, Out out) {
        if (!Mode.forRoom(this).run().hardcore(this) || !started(m) || "wiped".equals(m.status) || "champion".equals(m.status)) {
            return;
        }
        m.stats.fouls++;
        if ("lose".equals(ruleText("hardcoreItems"))) {
            m.status = "wiped";
            out.log(m, "wipe", "☠ " + m.name + " a perdu son " + runName() + " Hardcore : " + qty + "× " + name
                    + " utilisé en combat.", null);
            endWhenAllDone(out);
            return;
        }
        out.log(m, "foul", "⚠ Hardcore : " + m.name + " utilise " + qty + "× " + name + " en combat (interdit)", null);
        out.notice(m, "warn", "Hardcore : pas d'objets en combat. " + name + " est signalé à la salle.");
    }

    private void champion(Member m, Out out) {
        if ("champion".equals(m.status)) {
            return;
        }
        m.status = "champion";
        out.log(m, "champion", "🏆 " + m.name + " bat le Maître de la Ligue !", null);
        if (!spec().wins("champion") || !rule("autoEnd")) {
            return; // the leader ends the run by hand
        }
        Mode.forRoom(this).run().champion(this, m, out);
    }

    /** Each player's own run: it ends when every player has either beaten the League or lost their team. */
    void endWhenAllDone(Out out) {
        if (!members.stream().allMatch(x -> "champion".equals(x.status) || "wiped".equals(x.status))) {
            return;
        }
        boolean any = members.stream().anyMatch(x -> "champion".equals(x.status));
        finish(any ? "victory" : "defeat", any ? "Tous les joueurs ont terminé leur " + runName() + "." : "Toutes les équipes sont tombées.", out);
    }

    /** Ends the run: victory, defeat or abandon. */
    void finish(String outcome, String reason, Out out) {
        if (!"running".equals(state)) {
            return;
        }
        state = "abandon".equals(outcome) ? "abandoned" : "finished";
        adventureOpen = false;
        namingQueue.clear();
        ended = RoomEnd.of(System.currentTimeMillis(), outcome, reason, summary());
        String title = switch (outcome) {
            case "victory" -> runName() + " réussi !";
            case "defeat" -> runName() + " perdu.";
            default -> runName() + " abandonné.";
        };
        out.log(null, "end", title + " " + reason, Json.map("outcome", outcome));
        out.ended = true;
    }

    /** Totals of each player (shown at the end and in the history). */
    List<Object> summary() {
        List<Object> list = new ArrayList<>();
        for (Member m : members) {
            Map<String, Object> s = m.stats.toJson();
            s.put("userId", m.userId);
            s.put("name", m.name);
            s.put("status", m.status);
            long alive = m.mons.values().stream().filter(r -> "alive".equals(r.status) && !"existing".equals(r.origin)).count();
            s.put("alive", alive);
            list.add(s);
        }
        return list;
    }

    // ------------------------------------------------------------ directives

    /** What the game of this player must apply right now. */
    Map<String, Object> directives(Member m) {
        List<Object> doomed = new ArrayList<>();
        List<Object> release = new ArrayList<>();
        Map<String, Object> nicknames = new LinkedHashMap<>();
        for (MonRec r : m.mons.values()) {
            if ("dead".equals(r.status) || "released".equals(r.status)) {
                doomed.add(r.uid);
            }
            if ("released".equals(r.status)) {
                release.add(r.uid); // removed from the game (dead ones go to the PC box instead)
            }
            if (r.zoneKey != null) {
                Zone z = zones.get(r.zoneKey);
                if (z != null && z.nickname != null && !"released".equals(r.status)) {
                    nicknames.put(r.uid, z.nickname);
                }
            }
        }
        Map<String, Object> pause = null;
        if ("running".equals(state)) {
            if (!adventureOpen) {
                pause = Json.map("reason", "closed", "text", "L'aventure est fermée par le chef de salle.");
            } else if (!namingQueue.isEmpty() && rule("pauseForNaming")) {
                Zone z = zones.get(namingQueue.peekFirst());
                if (z != null) {
                    pause = Json.map("reason", "naming", "zone", z.key, "zoneName", z.name,
                            "text", "Le chef de salle choisit le nom du lien de " + z.name + ".");
                }
            }
        } else if ("finished".equals(state) || "abandoned".equals(state)) {
            pause = Json.map("reason", "ended", "text", "Le " + runName() + " est terminé.");
        }
        // the classic rules applied in the game while the player's run goes on: items taken out of the bag (by
        // category, see ItemRules), held items back in it, Fly forgotten
        Mode.RunLogic run = Mode.forRoom(this).run();
        boolean classic = run.classicRules() && "running".equals(state) && "playing".equals(m.status);
        List<Object> ban = new ArrayList<>();
        if (classic) {
            for (String[] b : new String[][]{{"honorNoHealItems", "heal"}, {"honorNoRepel", "repel"},
                    {"honorNoMasterBall", "masterBall"}, {"honorNoMultiExp", "expShare"}, {"honorNoFly", "fly"}}) {
                if (rule(b[0])) {
                    ban.add(b[1]);
                }
            }
        }
        Map<String, Object> directives = Json.map("room", id, "doomed", doomed, "release", release, "nicknames", nicknames, "pause", pause,
                "refunds", new ArrayList<Object>(m.refunds), "deathBox", rule("deathBox"),
                // shiny rate of the room: k out of 8192 (0 = the game's), applied while playing; "1 in N" too, for the
                // players of older versions
                "shinyNum", (long) slt.ShinyOdds.numerator(rando),
                "shinyRate", (long) slt.ShinyOdds.oneIn(slt.ShinyOdds.numerator(rando)),
                "park", new ArrayList<Object>(m.park.keySet()), "parkWhy", new LinkedHashMap<String, Object>(m.park),
                // Hardcore: the level cap of the player right now; Hardcore or "Mode Défini": the battle style "Set"
                // imposed in their game
                "levelCap", (long) levelCap(m), "setMode", run.hardcore(this) || (run.classicRules() && rule("honorSetMode")),
                "ban", ban, "noHeld", classic && rule("honorNoHeldItems"), "noFly", classic && rule("honorNoFly"),
                "saveStates", rule("saveStates"), "state", state, "open", adventureOpen, "trade", trades.directive(m));
        // DS games: rooms saved before this have the rate in their ROM (SltPatches), and every player's copy must stay so
        if (Json.bool(rando, "shinyRam", false)) {
            directives.put("shinyRam", true);
        }
        return directives;
    }

    void ackRefunds(Member m, List<Object> ids) {
        Set<String> done = new HashSet<>();
        for (Object o : ids) {
            done.add(String.valueOf(o));
        }
        m.refunds.removeIf(r -> done.contains(Json.str(r, "id")));
    }

    // ---------------------------------------------------------------- zones

    Zone zone(String key, String name) {
        Zone z = zones.get(key);
        if (z == null) {
            z = new Zone();
            z.key = key;
            z.name = name;
            z.firstAt = System.currentTimeMillis();
            zones.put(key, z);
        }
        return z;
    }

    static String zoneName(String zone) {
        return zone == null || zone.isBlank() ? "Lieu inconnu" : zone.trim();
    }

    static String zoneKey(String name) {
        String n = Normalizer.normalize(zoneName(name), Normalizer.Form.NFD).replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
        return n.isEmpty() ? "inconnu" : n;
    }

    private static String ordinal(int n) {
        return n == 1 ? "1er" : n + "e";
    }

    // ------------------------------------------------------------ serialize

    /** Full state for the members (and a short view for invited users): {@link RoomStore#view}. */
    Map<String, Object> view(Hub hub, boolean full) {
        return RoomStore.view(this, hub, full);
    }

    Map<String, Object> toStore() {
        return RoomStore.toStore(this);
    }

    static Room fromStore(Map<String, Object> m) {
        return RoomStore.fromStore(m);
    }

    // --------------------------------------------------------------- helpers

    static Map<String, Object> intMap(Map<String, Integer> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> e : m.entrySet()) {
            out.put(e.getKey(), (long) (int) e.getValue());
        }
        return out;
    }

    static void readInts(Object o, Map<String, Integer> into) {
        Map<String, Object> m = Json.obj(o);
        if (m != null) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() instanceof Number n) {
                    into.put(e.getKey(), n.intValue());
                }
            }
        }
    }

    static void readStrings(Object o, Map<String, String> into) {
        Map<String, Object> m = Json.obj(o);
        if (m != null) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() != null) {
                    into.put(e.getKey(), e.getValue().toString());
                }
            }
        }
    }
}
