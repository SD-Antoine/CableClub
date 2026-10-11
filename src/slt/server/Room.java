package slt.server;

import slt.Http;
import slt.Json;

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
 * A room (1 to 4 players) and its Soul Link: the server is the referee.
 * Every player's game reports what happens (tracker events); the rules below
 * decide which encounter counts, which Pokémon are linked, who dies with whom,
 * and what each game must apply (deaths, nicknames, refunds, pauses).
 *
 * All methods are called with the room locked (see {@link Rooms}).
 */
public final class Room {

    // ------------------------------------------------------------------ rules

    /** Rules of a new room; the leader may change them at any time. */
    public static Map<String, Object> defaultRules() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("shinyClause", true);        // a shiny can always be caught (not linked)
        r.put("dupesClause", true);        // an evolution line already owned in the room is ignored
        r.put("giftPokemon", false);       // Pokémon given / traded / sold by characters
        r.put("legendaries", true);        // legendary Pokémon may be caught
        r.put("naming", "leader");         // "leader": the leader names each link; "free": no naming step
        r.put("pauseForNaming", true);     // games wait while the leader names a link
        r.put("deathBox", true);           // dead Pokémon go to a dedicated PC box
        r.put("refundCancelled", true);    // items used on a cancelled zone are given back
        r.put("wipeEndsRun", true);        // a whole team down ends the Soul Link (defeat)
        r.put("endWhen", "all");           // "all": every player beats the Champion; "first": the first one does
        r.put("autoEnd", true);            // beating the Champion is detected in the game and ends the run
        r.put("startWithBalls", true);     // deaths count once every player has had a Poké Ball
        r.put("saveStates", false);        // save states allowed in the player
        return r;
    }

    private static final Set<Integer> LEGENDARIES = Set.of(144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251,
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

        Map<String, Object> toJson() {
            return Json.map("uid", uid, "species", (long) species, "speciesName", speciesName, "level", (long) level,
                    "nickname", nickname, "zoneKey", zoneKey, "zone", zone, "origin", origin, "shiny", shiny,
                    "bonus", bonus, "status", status, "reason", reason, "at", at);
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

    /** Totals shown at the end of the Soul Link. */
    static final class Stats {
        int captures;
        int deaths;
        int wildKills;
        int shinies;
        int encounters;
        int trainersBeaten;
        int badges;
        final Map<String, Integer> itemsBattle = new TreeMap<>();
        final Map<String, Integer> itemsField = new TreeMap<>();
        final Map<String, Integer> bought = new TreeMap<>();

        Map<String, Object> toJson() {
            return Json.map("captures", (long) captures, "deaths", (long) deaths, "wildKills", (long) wildKills,
                    "shinies", (long) shinies, "encounters", (long) encounters, "trainersBeaten", (long) trainersBeaten,
                    "badges", (long) badges, "itemsBattle", intMap(itemsBattle), "itemsField", intMap(itemsField),
                    "bought", intMap(bought));
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
        Map<String, Object> rom = new LinkedHashMap<>();
        int[] families = new int[0];
        String status = "playing";  // playing, champion, wiped
        boolean hasBalls;           // has had Poké Balls (the Soul Link starts when everybody has)
        final Map<String, MonRec> mons = new LinkedHashMap<>();
        final List<Map<String, Object>> refunds = new ArrayList<>();
        Stats stats = new Stats();
        Encounter encounter;
        String lastZone;
        // live view (not saved)
        Map<String, Object> live = new LinkedHashMap<>();

        Map<String, Object> toStore() {
            Map<String, Object> mons = new LinkedHashMap<>();
            for (MonRec r : this.mons.values()) {
                mons.put(r.uid, r.toJson());
            }
            return Json.map("userId", userId, "name", name, "joinedAt", joinedAt, "slot", (long) slot, "ready", ready,
                    "rom", rom, "families", families, "status", status, "mons", mons, "refunds", refunds,
                    "stats", stats.toJson(), "lastZone", lastZone, "hasBalls", hasBalls);
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
                x.rom = rom;
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
    long createdAt;
    long launchedAt;
    Map<String, Object> game = new LinkedHashMap<>();
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
    final Map<String, Trade> trades = new LinkedHashMap<>();
    final Deque<String> namingQueue = new ArrayDeque<>();
    Map<String, Object> ended;
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

    boolean rule(String key) {
        Object v = rules.get(key);
        if (v instanceof Boolean b) {
            return b;
        }
        return Boolean.TRUE.equals(defaultRules().get(key));
    }

    String ruleText(String key) {
        Object v = rules.get(key);
        return v == null ? String.valueOf(defaultRules().get(key)) : v.toString();
    }

    String gameName() {
        String n = Json.str(game, "name");
        return n == null ? "Pokémon" : n;
    }

    // ---------------------------------------------------------------- events

    /** Applies one tracker event of a player. */
    void event(Member m, Map<String, Object> ev, Out out) {
        if (!"running".equals(state)) {
            return;
        }
        String type = Json.str(ev, "type");
        if (type == null) {
            return;
        }
        switch (type) {
            case "location" -> location(m, ev, out);
            case "encounter" -> {
                if ("start".equals(Json.str(ev, "phase"))) {
                    encounterStart(m, ev, out);
                } else {
                    encounterEnd(m, ev, out);
                }
            }
            case "capture" -> capture(m, ev, out);
            case "update" -> update(m, ev, out);
            case "faint" -> faint(m, ev, out);
            case "party", "team" -> live(m, ev);
            case "badges" -> badges(m, ev, out);
            case "whiteout" -> whiteout(m, out);
            case "battle" -> battle(m, ev, out);
            case "items" -> items(m, ev, out);
            case "champion" -> champion(m, out);
            case "balls" -> balls(m, ev, out);
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
            } else if (e != null) {
                hint = " — capture déjà faite ici";
            } else if (!z.entries.isEmpty()) {
                hint = " — déjà capturée par un partenaire";
            }
        }
        out.log(m, "zone", m.name + " entre dans " + zone + hint, Json.map("zone", zone));
    }

    private void encounterStart(Member m, Map<String, Object> ev, Out out) {
        String uid = Json.str(ev, "uid");
        String zone = zoneName(Json.str(ev, "zone"));
        String key = zoneKey(zone);
        int species = (int) Json.num(ev, "species", 0);
        String speciesName = Json.str(ev, "speciesName");
        int level = (int) Json.num(ev, "level", 0);
        boolean shiny = Json.bool(ev, "shiny", false);
        Encounter e = new Encounter();
        e.uid = uid;
        e.zoneKey = key;
        e.zone = zone;
        e.species = species;
        e.speciesName = speciesName;
        e.level = level;
        e.shiny = shiny;
        e.at = System.currentTimeMillis();
        e.kind = classify(m, key, species, shiny);
        m.encounter = e;
        m.stats.encounters++;
        m.live.put("battle", "wild");
        String who = (speciesName == null ? "#" + species : speciesName) + " N." + level + (shiny ? " ✨" : "");
        out.log(m, "encounter", m.name + " rencontre " + who + " (" + zone + ")",
                Json.map("species", (long) species, "speciesName", speciesName, "level", (long) level, "zone", zone,
                        "shiny", shiny, "kind", e.kind));
        switch (e.kind) {
            case "zone" -> out.notice(m, "info", "Première rencontre de " + zone + " : elle compte pour le Soul Link.");
            case "shiny" -> out.notice(m, "ok", "Chromatique ! Capture libre (clause chromatique), sans lien.");
            case "used" -> out.notice(m, "warn", zone + " : vous y avez déjà capturé ou mis K.O. un Pokémon. Capture interdite.");
            case "cancelled" -> out.notice(m, "warn", zone + " est annulée pour la salle. Capture interdite.");
            case "dupe" -> out.notice(m, "warn", "Doublon : cette lignée d'évolution est déjà dans la salle. Rencontre ignorée, fuyez ou mettez-le K.O.");
            case "legendary" -> out.notice(m, "warn", "Légendaire interdit par les règles : ne le capturez pas.");
            case "noballs" -> out.notice(m, "info", "Pas encore de Poké Ball : cette rencontre ne compte pas pour le Soul Link.");
            default -> {
                // nothing to say
            }
        }
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

    /** The Soul Link really starts when every player has had a Poké Ball (until then, nobody dies). */
    void checkStart(Out out) {
        if (linkStarted || !"running".equals(state)) {
            return;
        }
        if (rule("startWithBalls") && !members.stream().allMatch(x -> x.hasBalls)) {
            return;
        }
        linkStarted = true;
        out.log(null, "start", rule("startWithBalls")
                ? "Tout le monde a des Poké Balls : le Soul Link commence vraiment. Désormais, un Pokémon K.O. est mort."
                : "Le Soul Link commence : un Pokémon K.O. est mort.", null);
        for (Member x : members) {
            out.notice(x, "warn", "Le Soul Link commence : désormais, un Pokémon K.O. est mort, avec son âme liée.");
        }
    }

    /** Does this wild Pokémon count for its zone? */
    private String classify(Member m, String key, int species, boolean shiny) {
        if (!m.hasBalls && rule("startWithBalls")) {
            return "noballs";
        }
        if (shiny && rule("shinyClause")) {
            return "shiny";
        }
        if (LEGENDARIES.contains(species) && !rule("legendaries")) {
            return "legendary";
        }
        Zone z = zones.get(key);
        if (z != null && "cancelled".equals(z.status)) {
            return "cancelled";
        }
        if (z != null && z.entries.containsKey(m.userId)) {
            return "used";
        }
        if (rule("dupesClause") && ownedFamily(m, species, key)) {
            return "dupe";
        }
        return "zone";
    }

    /** An evolution line already caught by someone in the room (encountering player's evolution data). */
    private boolean ownedFamily(Member m, int species, String zoneKey) {
        int fam = m.family(species);
        for (Member other : members) {
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

    private void encounterEnd(Member m, Map<String, Object> ev, Out out) {
        Encounter e = m.encounter;
        String uid = Json.str(ev, "uid");
        m.live.remove("battle");
        if (e == null || (uid != null && !uid.equals(e.uid))) {
            return;
        }
        m.encounter = null;
        boolean killed = Json.bool(ev, "wildFainted", false);
        String who = e.speciesName == null ? "#" + e.species : e.speciesName;
        if (!killed) {
            out.log(m, "flee", m.name + " quitte le combat contre " + who, Json.map("zone", e.zone));
            return;
        }
        m.stats.wildKills++;
        if (!"zone".equals(e.kind)) {
            out.log(m, "kill", m.name + " met K.O. " + who + " (" + e.zone + ")", Json.map("zone", e.zone));
            return;
        }
        // the first Pokémon of the zone is dead: the zone is lost for the whole room
        Zone z = zone(e.zoneKey, e.zone);
        Entry entry = new Entry();
        entry.result = "killed";
        entry.uid = e.uid;
        entry.species = e.species;
        entry.speciesName = e.speciesName;
        entry.level = e.level;
        entry.at = System.currentTimeMillis();
        z.entries.put(m.userId, entry);
        out.log(m, "kill", m.name + " met K.O. " + who + ", premier Pokémon de " + e.zone + " : zone annulée",
                Json.map("zone", e.zone));
        cancel(z, out);
    }

    /** Zone cancelled: the Pokémon caught there are released, items used to catch them are given back. */
    private void cancel(Zone z, Out out) {
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
        if (!wild && first) {
            // the first Pokémon received is the starter: linked with the other starters
            r.zone = "Départ";
            link(m, r, zone(STARTER, "Départ"), null, out);
            return;
        }
        if (!wild) {
            if (!rule("giftPokemon")) {
                release(m, r, "interdit : Pokémon offert ou échangé", out);
                return;
            }
            Zone z = zones.get(zoneKey(zone));
            if (z == null || !z.entries.containsKey(m.userId)) {
                link(m, r, zone(zoneKey(zone), zone), null, out);
            } else {
                bonus(m, r, "Pokémon offert", out);
            }
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
        switch (kind) {
            case "zone" -> link(m, r, zone(zoneKey(r.zone), r.zone), enc, out);
            case "shiny" -> bonus(m, r, "chromatique", out);
            case "legendary" -> release(m, r, "interdit : légendaire", out);
            case "dupe" -> release(m, r, "interdit : doublon", out);
            case "cancelled" -> {
                release(m, r, "interdit : zone annulée", out);
                if (enc != null && rule("refundCancelled") && !enc.items.isEmpty()) {
                    refund(m, enc.items, enc.itemNames, out);
                }
            }
            default -> release(m, r, "interdit : zone déjà utilisée", out);
        }
    }

    /** The Pokémon becomes this player's Pokémon for the zone. */
    private void link(Member m, MonRec r, Zone z, Encounter enc, Out out) {
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
        if (z.nickname != null) {
            r.nickname = z.nickname;
        }
        switch (z.status) {
            case "dead" -> {
                r.status = "dead";
                r.reason = "lien déjà perdu";
                out.log(m, "death", r.label() + " rejoint ses âmes liées : le lien de " + z.name + " était déjà perdu.", null);
                out.notice(m, "warn", "Le lien de " + z.name + " était déjà perdu : ce Pokémon est mort d'office.");
            }
            case "cancelled" -> {
                r.status = "released";
                r.reason = "zone annulée";
            }
            default -> refreshZone(z, out);
        }
    }

    private void bonus(Member m, MonRec r, String why, Out out) {
        r.bonus = true;
        m.stats.captures++;
        if (r.shiny) {
            m.stats.shinies++;
        }
        out.log(m, "capture", m.name + " capture " + r.label() + " N." + r.level + " — " + why + ", sans lien",
                Json.map("species", (long) r.species, "speciesName", r.speciesName, "level", (long) r.level,
                        "shiny", r.shiny, "bonus", true));
    }

    private void release(Member m, MonRec r, String why, Out out) {
        r.status = "released";
        r.reason = why;
        out.log(m, "release", r.label() + " de " + m.name + " est relâché (" + why.replace("interdit : ", "") + ")", null);
        out.notice(m, "warn", "Capture non autorisée (" + why.replace("interdit : ", "") + ") : ce Pokémon sera retiré de l'équipe.");
    }

    /** Recomputes the status of a zone after a change. */
    private void refreshZone(Zone z, Out out) {
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
        r.level = (int) Json.num(ev, "level", r.level);
        if (evolved) {
            r.species = species;
            r.speciesName = Json.str(ev, "speciesName");
            out.log(m, "evolution", before + " de " + m.name + " évolue en " + r.speciesName, null);
        }
    }

    private void faint(Member m, Map<String, Object> ev, Out out) {
        MonRec r = m.mons.get(Json.str(ev, "uid"));
        if (r == null || !"alive".equals(r.status) || "existing".equals(r.origin)) {
            return;
        }
        if (!linkStarted) {
            out.log(m, "faint", r.label() + " de " + m.name + " est K.O., sans conséquence : le Soul Link n'a pas encore commencé.", null);
            out.notice(m, "info", r.label() + " est K.O. mais ne meurt pas : le Soul Link commence quand chaque joueur a une Poké Ball.");
            return;
        }
        r.status = "dead";
        r.reason = "K.O.";
        m.stats.deaths++;
        out.log(m, "death", "☠ " + r.label() + " de " + m.name + " est mort" + (r.zone != null ? " (" + r.zone + ")" : ""), null);
        if (r.bonus || r.zoneKey == null) {
            return;
        }
        Zone z = zones.get(r.zoneKey);
        if (z == null || "cancelled".equals(z.status) || "dead".equals(z.status)) {
            return;
        }
        z.status = "dead";
        namingQueue.remove(z.key);
        for (Map.Entry<String, Entry> x : z.entries.entrySet()) {
            Member owner = member(x.getKey());
            if (owner == null || owner == m) {
                continue;
            }
            MonRec linked = owner.mons.get(x.getValue().uid);
            if (linked != null && "alive".equals(linked.status)) {
                linked.status = "dead";
                linked.reason = "âme liée à " + r.label();
                // not counted in the partner's deaths: only the player whose Pokémon fell first is
                out.log(owner, "death", "☠ " + linked.label() + " de " + owner.name + " meurt avec son âme liée", null);
                out.notice(owner, "danger", linked.label() + " est mort : son âme liée de " + m.name + " est tombée.");
            }
        }
    }

    private void live(Member m, Map<String, Object> ev) {
        if ("team".equals(Json.str(ev, "type"))) {
            m.live.put("team", ev.get("mons"));
        } else {
            m.live.put("party", ev.get("uids"));
        }
        m.live.put("at", System.currentTimeMillis());
    }

    private void badges(Member m, Map<String, Object> ev, Out out) {
        int count = (int) Json.num(ev, "count", 0);
        if (count > m.stats.badges) {
            m.stats.badges = count;
            out.log(m, "badge", m.name + " obtient son " + ordinal(count) + " badge", Json.map("count", (long) count));
        }
    }

    private void whiteout(Member m, Out out) {
        out.log(m, "whiteout", "L'équipe de " + m.name + " est entièrement K.O.", null);
        if (rule("wipeEndsRun") && linkStarted) {
            m.status = "wiped";
            finish("defeat", "L'équipe de " + m.name + " est tombée.", out);
        }
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

    private void champion(Member m, Out out) {
        if ("champion".equals(m.status)) {
            return;
        }
        m.status = "champion";
        out.log(m, "champion", "🏆 " + m.name + " bat le Maître de la Ligue !", null);
        if (!rule("autoEnd")) {
            return; // the leader ends the Soul Link by hand
        }
        boolean all = members.stream().allMatch(x -> "champion".equals(x.status));
        if ("first".equals(ruleText("endWhen")) || all) {
            finish("victory", m.name + " a battu le Maître de la Ligue.", out);
        }
    }

    /** Ends the Soul Link: victory, defeat or abandon. */
    void finish(String outcome, String reason, Out out) {
        if (!"running".equals(state)) {
            return;
        }
        state = "abandon".equals(outcome) ? "abandoned" : "finished";
        adventureOpen = false;
        namingQueue.clear();
        ended = Json.map("at", System.currentTimeMillis(), "outcome", outcome, "reason", reason, "summary", summary());
        String title = switch (outcome) {
            case "victory" -> "Soul Link réussi !";
            case "defeat" -> "Soul Link perdu.";
            default -> "Soul Link abandonné.";
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
            pause = Json.map("reason", "ended", "text", "Le Soul Link est terminé.");
        }
        return Json.map("room", id, "doomed", doomed, "release", release, "nicknames", nicknames, "pause", pause,
                "refunds", new ArrayList<Object>(m.refunds), "deathBox", rule("deathBox"),
                "saveStates", rule("saveStates"), "state", state, "open", adventureOpen, "trade", tradeDirective(m));
    }

    void ackRefunds(Member m, List<Object> ids) {
        Set<String> done = new HashSet<>();
        for (Object o : ids) {
            done.add(String.valueOf(o));
        }
        m.refunds.removeIf(r -> done.contains(Json.str(r, "id")));
    }

    // --------------------------------------------------------------- trades

    private List<Object> storedTrades() {
        List<Object> out = new ArrayList<>();
        for (Trade t : trades.values()) {
            if (t.active()) {
                out.add(t.toJson(true));
            }
        }
        return out;
    }

    private Trade activeTradeOf(String userId) {
        for (Trade t : trades.values()) {
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
    void offerTrade(Member m, String toUserId, String uid, Out out) {
        if (!"running".equals(state)) {
            throw new Http.Fail(409, "L'aventure n'est pas en cours.");
        }
        Member to = toUserId == null ? null : member(toUserId);
        if (to == null || to == m) {
            throw new Http.Fail(400, "Choisissez un partenaire de la salle.");
        }
        if (activeTradeOf(m.userId) != null || activeTradeOf(to.userId) != null) {
            throw new Http.Fail(409, "Un échange est déjà en cours pour l'un de vous deux.");
        }
        MonRec r = tradable(m, to, uid);
        Trade t = new Trade();
        t.id = Long.toString(System.currentTimeMillis(), 36) + Integer.toString((int) (Math.random() * 1296), 36);
        t.from = m.userId;
        t.to = to.userId;
        t.fromUid = uid;
        t.at = System.currentTimeMillis();
        trades.put(t.id, t);
        out.log(m, "trade", m.name + " propose " + r.label() + " en échange à " + to.name, null);
        out.notice(to, "info", m.name + " vous propose un échange : " + r.label() + ". Répondez depuis la salle.");
        out.changed = true;
    }

    /** The partner accepts (with one of their Pokémon) or declines. */
    void answerTrade(Member m, String tradeId, boolean accept, String uid, Out out) {
        Trade t = trades.get(tradeId);
        if (t == null || !"offered".equals(t.state) || !m.userId.equals(t.to)) {
            throw new Http.Fail(409, "Cette proposition n'est plus valable.");
        }
        Member from = member(t.from);
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

    void cancelTrade(Member m, String tradeId, Out out) {
        Trade t = trades.get(tradeId);
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
    void tradeData(Member m, String tradeId, String phase, String blob, Out out) {
        Trade t = trades.get(tradeId);
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
                Member a = member(t.from);
                Member b = member(t.to);
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
        Member a = member(t.from);
        Member b = member(t.to);
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
    private Map<String, Object> tradeDirective(Member m) {
        Trade t = activeTradeOf(m.userId);
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

    // ---------------------------------------------------------------- zones

    private Zone zone(String key, String name) {
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

    /** Full state for the members (and a short view for invited users). */
    Map<String, Object> view(Hub hub, boolean full) {
        List<Object> ms = new ArrayList<>();
        for (Member m : members) {
            Map<String, Object> x = Json.map("userId", m.userId, "name", m.name, "slot", (long) m.slot,
                    "ready", m.ready, "rom", m.rom, "status", m.status, "online", hub.online(m.userId),
                    "leader", isLeader(m.userId), "hasBalls", m.hasBalls);
            if (full) {
                List<Object> mons = new ArrayList<>();
                for (MonRec r : m.mons.values()) {
                    mons.add(r.toJson());
                }
                x.put("mons", mons);
                x.put("live", m.live);
                x.put("stats", m.stats.toJson());
                x.put("refunds", (long) m.refunds.size());
            }
            ms.add(x);
        }
        Map<String, Object> v = Json.map("id", id, "name", name, "leaderId", leaderId, "createdAt", createdAt,
                "launchedAt", launchedAt, "game", game, "maxPlayers", (long) maxPlayers, "state", state,
                "adventureOpen", adventureOpen, "members", ms, "ended", ended, "linkStarted", linkStarted);
        if (full) {
            v.put("code", code);
            v.put("rules", rules);
            v.put("rando", rando);
            List<Object> zs = new ArrayList<>();
            for (Zone z : zones.values()) {
                zs.add(z.toJson());
            }
            v.put("zones", zs);
            String active = namingQueue.peekFirst();
            Zone nz = active == null ? null : zones.get(active);
            v.put("naming", nz == null ? null : Json.map("zone", nz.key, "zoneName", nz.name, "queue", (long) namingQueue.size()));
            v.put("invites", new ArrayList<Object>(invites));
            List<Object> ts = new ArrayList<>();
            for (Trade t : trades.values()) {
                if (t.active() || System.currentTimeMillis() - t.at < 120_000) {
                    ts.add(t.toJson(false));
                }
            }
            v.put("trades", ts);
        }
        return v;
    }

    Map<String, Object> toStore() {
        List<Object> ms = new ArrayList<>();
        for (Member m : members) {
            ms.add(m.toStore());
        }
        List<Object> zs = new ArrayList<>();
        for (Zone z : zones.values()) {
            zs.add(z.toJson());
        }
        return Json.map("version", 1L, "id", id, "code", code, "name", name, "leaderId", leaderId,
                "createdAt", createdAt, "launchedAt", launchedAt, "game", game, "maxPlayers", (long) maxPlayers,
                "state", state, "adventureOpen", adventureOpen, "linkStarted", linkStarted, "rules", rules, "rando", rando, "members", ms,
                "invites", new ArrayList<Object>(invites), "history", new ArrayList<Object>(history), "zones", zs,
                "namingQueue", new ArrayList<Object>(namingQueue), "ended", ended, "logSeq", logSeq,
                "refundSeq", refundSeq, "trades", storedTrades());
    }

    static Room fromStore(Map<String, Object> m) {
        Room r = new Room();
        r.id = Json.str(m, "id");
        r.code = Json.str(m, "code");
        r.name = Json.str(m, "name");
        r.leaderId = Json.str(m, "leaderId");
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
        Map<String, Object> rules = defaultRules();
        Map<String, Object> saved = Json.obj(m.get("rules"));
        if (saved != null) {
            rules.putAll(saved);
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
        List<Object> ts = Json.arr(m.get("trades"));
        if (ts != null) {
            for (Object o : ts) {
                Trade t = Trade.fromJson(Json.obj(o));
                if (t.id != null) {
                    r.trades.put(t.id, t);
                }
            }
        }
        r.logSeq = Json.num(m, "logSeq", 0);
        r.refundSeq = Json.num(m, "refundSeq", 0);
        return r;
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
