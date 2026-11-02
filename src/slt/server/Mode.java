package slt.server;

import slt.Json;

import java.util.List;
import java.util.Map;

/**
 * A game mode seen as an ordered list of rules applied to each event (point 1c of the evolution plan). The
 * mode-specific logic moves out of {@link Room}'s handlers step by step: wild encounters are handled by the mode's
 * {@link EncounterLogic} (1c-1); every other event still goes through Room's existing handlers ({@link LegacyDispatch})
 * until its own step. The server stays the referee and the directives stay the only channel to the adapters.
 */
final class Mode {

    /** A piece of a mode's behaviour: it reacts to one event through the context. */
    interface Rule {
        void onEvent(Ctx ctx, Map<String, Object> ev);
    }

    /** What a rule may act on: the room, the player whose event it is, and where logs, notices and state changes go. */
    static final class Ctx {
        final Room room;
        final Room.Member member;
        final Room.Out out;

        Ctx(Room room, Room.Member member, Room.Out out) {
            this.room = room;
            this.member = member;
            this.out = out;
        }
    }

    /** How a family of modes treats wild encounters: Soul Link, each player's own run, Shinylocke. */
    interface EncounterLogic {

        /** The kind of a wild Pokémon: zone, shiny, used, dupe, legendary, cancelled, notshiny or noballs. */
        String classify(Room r, Room.Member m, String key, int species, boolean shiny);

        /** What the journal adds when the player enters a zone already played (cancelled and lost links aside). */
        String zoneHint(Room r, Room.Zone z, Room.Entry mine);

        /** Start of a wild battle: classify, record the encounter, tell the player. */
        void start(Ctx ctx, Map<String, Object> ev);

        /** End of a wild battle: the player fled or knocked the wild Pokémon out. */
        void end(Ctx ctx, Map<String, Object> ev);

        /** The same for every mode: records the encounter on the player and logs it. */
        static Room.Encounter open(Ctx ctx, Map<String, Object> ev, String zone, String key, String kind) {
            Room.Member m = ctx.member;
            int species = (int) Json.num(ev, "species", 0);
            String speciesName = Json.str(ev, "speciesName");
            int level = (int) Json.num(ev, "level", 0);
            boolean shiny = Json.bool(ev, "shiny", false);
            Room.Encounter e = new Room.Encounter();
            e.uid = Json.str(ev, "uid");
            e.zoneKey = key;
            e.zone = zone;
            e.species = species;
            e.speciesName = speciesName;
            e.level = level;
            e.shiny = shiny;
            e.at = System.currentTimeMillis();
            e.kind = kind;
            m.encounter = e;
            m.stats.encounters++;
            m.live.put("battle", "wild");
            String who = (speciesName == null ? "#" + species : speciesName) + " N." + level + (shiny ? " ✨" : "");
            ctx.out.log(m, "encounter", m.name + " rencontre " + who + " (" + zone + ")",
                    Json.map("species", (long) species, "speciesName", speciesName, "level", (long) level, "zone", zone,
                            "shiny", shiny, "kind", kind));
            return e;
        }

        /** The same for every mode: the encounter this end closes, or null when it closes none. */
        static Room.Encounter closing(Ctx ctx, Map<String, Object> ev) {
            Room.Member m = ctx.member;
            Room.Encounter e = m.encounter;
            String uid = Json.str(ev, "uid");
            m.live.remove("battle");
            if (e == null || (uid != null && !uid.equals(e.uid))) {
                return null;
            }
            m.encounter = null;
            return e;
        }
    }

    private static final EncounterLogic SOUL_LINK = new SoulLinkEncounters();
    private static final EncounterLogic INDIVIDUAL = new IndividualEncounters();
    private static final EncounterLogic SHINYLOCKE = new ShinylockeEncounters();

    private final String id;
    private final EncounterLogic encounters;
    private final List<Rule> rules;

    Mode(String id, EncounterLogic encounters, List<Rule> rules) {
        this.id = id;
        this.encounters = encounters;
        this.rules = rules;
    }

    String id() {
        return id;
    }

    EncounterLogic encounters() {
        return encounters;
    }

    /** Runs every rule of the mode on the event, in order. */
    void handle(Ctx ctx, Map<String, Object> ev) {
        for (Rule rule : rules) {
            rule.onEvent(ctx, ev);
        }
    }

    /** The mode of a room: its encounter logic, then Room's handlers for the events not moved yet. */
    static Mode forRoom(Room room) {
        EncounterLogic enc = switch (room.mode) {
            case "shinylocke" -> SHINYLOCKE;
            case "nuzlocke", "sololocke" -> INDIVIDUAL;
            default -> SOUL_LINK;
        };
        return new Mode(room.mode, enc, List.of(new EncounterRule(enc), new LegacyDispatch()));
    }

    /** Wild encounters (start and end), through the mode's encounter logic. */
    private static final class EncounterRule implements Rule {
        private final EncounterLogic logic;

        EncounterRule(EncounterLogic logic) {
            this.logic = logic;
        }

        @Override
        public void onEvent(Ctx ctx, Map<String, Object> ev) {
            if (!"encounter".equals(Json.str(ev, "type"))) {
                return;
            }
            if ("start".equals(Json.str(ev, "phase"))) {
                logic.start(ctx, ev);
            } else {
                logic.end(ctx, ev);
            }
        }
    }

    /** Transitional rule: runs Room's existing per-type handlers. Shrinks as logic moves into real rules. */
    private static final class LegacyDispatch implements Rule {
        @Override
        public void onEvent(Ctx ctx, Map<String, Object> ev) {
            ctx.room.dispatch(ctx.member, ev, ctx.out);
        }
    }

    // ------------------------------------------------------------ Soul Link

    /** The room shares its zones: the first Pokémon of each zone links the players; knocking it out loses the zone. */
    private static final class SoulLinkEncounters implements EncounterLogic {

        @Override
        public String classify(Room r, Room.Member m, String key, int species, boolean shiny) {
            if (!m.hasBalls && r.rule("startWithBalls")) {
                return "noballs";
            }
            if (shiny && r.rule("shinyClause")) {
                return "shiny";
            }
            if (Room.LEGENDARIES.contains(species) && !r.rule("legendaries")) {
                return "legendary";
            }
            Room.Zone z = r.zones.get(key);
            if (z != null && "cancelled".equals(z.status)) {
                return "cancelled";
            }
            if (z != null && z.entries.containsKey(m.userId)) {
                return "used";
            }
            if (r.rule("dupesClause") && r.ownedFamily(m, species, key, true)) {
                return "dupe";
            }
            return "zone";
        }

        @Override
        public String zoneHint(Room r, Room.Zone z, Room.Entry mine) {
            if (mine != null) {
                return " — capture déjà faite ici";
            }
            return z.entries.isEmpty() ? "" : " — déjà capturée par un partenaire";
        }

        @Override
        public void start(Ctx ctx, Map<String, Object> ev) {
            Room.Member m = ctx.member;
            String zone = Room.zoneName(Json.str(ev, "zone"));
            String key = Room.zoneKey(zone);
            String kind = classify(ctx.room, m, key, (int) Json.num(ev, "species", 0), Json.bool(ev, "shiny", false));
            EncounterLogic.open(ctx, ev, zone, key, kind);
            switch (kind) {
                case "zone" -> ctx.out.notice(m, "info", "Première rencontre de " + zone + " : elle compte pour le Soul Link.");
                case "shiny" -> ctx.out.notice(m, "ok", "Chromatique ! Capture libre (clause chromatique), sans lien.");
                case "used" -> ctx.out.notice(m, "warn", zone + " : vous y avez déjà capturé ou mis K.O. un Pokémon. Capture interdite.");
                case "cancelled" -> ctx.out.notice(m, "warn", zone + " est annulée pour la salle. Capture interdite.");
                case "dupe" -> ctx.out.notice(m, "warn",
                        "Doublon : cette lignée d'évolution est déjà dans la salle. Rencontre ignorée, fuyez ou mettez-le K.O.");
                case "legendary" -> ctx.out.notice(m, "warn", "Légendaire interdit par les règles : ne le capturez pas.");
                case "noballs" -> ctx.out.notice(m, "info",
                        "Pas encore de Poké Ball : cette rencontre ne compte pas pour le " + ctx.room.runName() + ".");
                default -> {
                    // nothing to say
                }
            }
        }

        @Override
        public void end(Ctx ctx, Map<String, Object> ev) {
            Room.Encounter e = EncounterLogic.closing(ctx, ev);
            if (e == null) {
                return;
            }
            Room.Member m = ctx.member;
            Room.Out out = ctx.out;
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
            Room.Zone z = ctx.room.zone(e.zoneKey, e.zone);
            Room.Entry entry = new Room.Entry();
            entry.result = "killed";
            entry.uid = e.uid;
            entry.species = e.species;
            entry.speciesName = e.speciesName;
            entry.level = e.level;
            entry.at = System.currentTimeMillis();
            z.entries.put(m.userId, entry);
            out.log(m, "kill", m.name + " met K.O. " + who + ", premier Pokémon de " + e.zone + " : zone annulée",
                    Json.map("zone", e.zone));
            ctx.room.cancel(z, out);
        }
    }

    // ------------------------------------------------- Nuzlocke, Sololocke

    /** Each player alone: the first wild Pokémon met in a zone is the only one that can be caught there. */
    private static final class IndividualEncounters implements EncounterLogic {

        @Override
        public String classify(Room r, Room.Member m, String key, int species, boolean shiny) {
            if (!r.started(m)) {
                return "noballs";
            }
            if (shiny && r.rule("shinyClause")) {
                return "shiny";
            }
            if (Room.LEGENDARIES.contains(species) && !r.rule("legendaries")) {
                return "legendary";
            }
            Room.Zone z = r.zones.get(key);
            if (z != null && z.entries.containsKey(m.userId)) {
                return "used";
            }
            if (r.rule("dupesClause") && r.ownedFamily(m, species, key, false)) {
                return "dupe";
            }
            return "zone";
        }

        @Override
        public String zoneHint(Room r, Room.Zone z, Room.Entry mine) {
            return mine != null ? " — première rencontre déjà faite ici" : "";
        }

        @Override
        public void start(Ctx ctx, Map<String, Object> ev) {
            Room.Member m = ctx.member;
            String zone = Room.zoneName(Json.str(ev, "zone"));
            String key = Room.zoneKey(zone);
            String kind = classify(ctx.room, m, key, (int) Json.num(ev, "species", 0), Json.bool(ev, "shiny", false));
            EncounterLogic.open(ctx, ev, zone, key, kind);
            switch (kind) {
                case "zone" -> ctx.out.notice(m, "info",
                        "Première rencontre de " + zone + " : c'est votre seule chance ici (fuir ou le battre l'épuise).");
                case "shiny" -> ctx.out.notice(m, "ok", "Chromatique ! Capture libre (clause chromatique), sans lien.");
                case "used" -> ctx.out.notice(m, "warn", zone + " : vous y avez déjà rencontré un Pokémon. Capture interdite.");
                case "dupe" -> ctx.out.notice(m, "warn",
                        "Doublon : vous avez déjà cette lignée d'évolution. Rencontre ignorée, fuyez ou mettez-le K.O.");
                case "legendary" -> ctx.out.notice(m, "warn", "Légendaire interdit par les règles : ne le capturez pas.");
                case "noballs" -> ctx.out.notice(m, "info",
                        "Pas encore de Poké Ball : cette rencontre ne compte pas pour le " + ctx.room.runName() + ".");
                default -> {
                    // nothing to say
                }
            }
        }

        @Override
        public void end(Ctx ctx, Map<String, Object> ev) {
            Room.Encounter e = EncounterLogic.closing(ctx, ev);
            if (e == null) {
                return;
            }
            Room.Member m = ctx.member;
            boolean killed = Json.bool(ev, "wildFainted", false);
            String who = e.speciesName == null ? "#" + e.species : e.speciesName;
            if (killed) {
                m.stats.wildKills++;
            }
            String did = m.name + (killed ? " met K.O. " : " quitte le combat contre ") + who;
            if (!"zone".equals(e.kind)) {
                ctx.out.log(m, killed ? "kill" : "flee", did + " (" + e.zone + ")", Json.map("zone", e.zone));
                return;
            }
            // the first Pokémon of the zone got away or was beaten: no second chance in this zone
            Room.Zone z = ctx.room.zone(e.zoneKey, e.zone);
            if (!z.entries.containsKey(m.userId)) {
                Room.Entry entry = new Room.Entry();
                entry.result = killed ? "killed" : "fled";
                entry.uid = e.uid;
                entry.species = e.species;
                entry.speciesName = e.speciesName;
                entry.level = e.level;
                entry.at = System.currentTimeMillis();
                z.entries.put(m.userId, entry);
            }
            ctx.out.log(m, killed ? "kill" : "flee", did + ", première rencontre de " + e.zone + " : pas de seconde chance ici",
                    Json.map("zone", e.zone));
        }
    }

    // ------------------------------------------------------------ Shinylocke

    /** Only shinies may be caught, anywhere (one per zone if the room wants); a battle never uses up a zone. */
    private static final class ShinylockeEncounters implements EncounterLogic {

        @Override
        public String classify(Room r, Room.Member m, String key, int species, boolean shiny) {
            if (!r.started(m)) {
                return "noballs";
            }
            if (Room.LEGENDARIES.contains(species) && !r.rule("legendaries")) {
                return "legendary";
            }
            if (!shiny) {
                return "notshiny";
            }
            Room.Zone z = r.zones.get(key);
            Room.Entry mine = z == null ? null : z.entries.get(m.userId);
            if (r.rule("oneShinyPerZone") && mine != null && "caught".equals(mine.result)) {
                return "used";
            }
            if (r.rule("dupesClause") && r.ownedFamily(m, species, key, false)) {
                return "dupe";
            }
            return "zone";
        }

        @Override
        public String zoneHint(Room r, Room.Zone z, Room.Entry mine) {
            return mine != null && "caught".equals(mine.result) && r.rule("oneShinyPerZone")
                    ? " — chromatique déjà capturé ici" : "";
        }

        @Override
        public void start(Ctx ctx, Map<String, Object> ev) {
            Room.Member m = ctx.member;
            String zone = Room.zoneName(Json.str(ev, "zone"));
            String key = Room.zoneKey(zone);
            String kind = classify(ctx.room, m, key, (int) Json.num(ev, "species", 0), Json.bool(ev, "shiny", false));
            EncounterLogic.open(ctx, ev, zone, key, kind);
            switch (kind) {
                case "zone" -> ctx.out.notice(m, "ok", "Chromatique ! Vous pouvez le capturer.");
                case "used" -> ctx.out.notice(m, "warn", zone + " : vous y avez déjà capturé un chromatique. Capture interdite.");
                case "dupe" -> ctx.out.notice(m, "warn",
                        "Doublon : vous avez déjà cette lignée d'évolution. Rencontre ignorée, fuyez ou mettez-le K.O.");
                case "legendary" -> ctx.out.notice(m, "warn", "Légendaire interdit par les règles : ne le capturez pas.");
                case "noballs" -> ctx.out.notice(m, "info",
                        "Pas encore de Poké Ball : cette rencontre ne compte pas pour le " + ctx.room.runName() + ".");
                default -> {
                    // not shiny: said at the capture (released)
                }
            }
        }

        @Override
        public void end(Ctx ctx, Map<String, Object> ev) {
            Room.Encounter e = EncounterLogic.closing(ctx, ev);
            if (e == null) {
                return;
            }
            Room.Member m = ctx.member;
            boolean killed = Json.bool(ev, "wildFainted", false);
            String who = e.speciesName == null ? "#" + e.species : e.speciesName;
            if (killed) {
                m.stats.wildKills++;
            }
            ctx.out.log(m, killed ? "kill" : "flee", m.name + (killed ? " met K.O. " : " quitte le combat contre ") + who
                    + " (" + e.zone + ")", Json.map("zone", e.zone));
        }
    }
}
