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

    /**
     * How a family of modes judges a Pokémon that joins a player (point 1c-2): the starter, a Pokémon given by a
     * character, a capture made with a full team, the one kept for the HMs, and what linking it to its zone means.
     * Room keeps the steps common to every mode (recording it, telling a starter, a gift and a wild capture apart).
     */
    interface CaptureLogic {

        /** The first Pokémon received: the starter. */
        void starter(Room r, Room.Member m, Room.MonRec rec, Room.Out out);

        /** A Pokémon given, traded or sold by a character (not caught in the wild). */
        void gift(Room r, Room.Member m, Room.MonRec rec, String zone, Room.Out out);

        /** "Notepad Clause": a capture made while the team is full is forbidden. */
        boolean notepad(Room r);

        /** "Exception pour les CS": the next forbidden capture may be kept for the HMs. */
        boolean hmException(Room r);

        /** Once a capture is recorded for its zone: what linking it means (nothing when each player is alone). */
        void linked(Room r, Room.Member m, Room.MonRec rec, Room.Zone z, Room.Out out);

        /** Added to the journal line of a capture that counts for no zone. */
        String bonusSuffix();
    }

    /**
     * How a family of modes runs a player's adventure (points 1c-3 to 1c-5): what a death and a whole team knocked
     * out mean. Families: Soul Link (deaths are shared through the links), each player's own run (Nuzlocke,
     * Shinylocke), Sololocke (one Pokémon at a time).
     */
    interface RunLogic {

        /** When deaths start to count, as told to a player whose Pokémon fell before: "… commence <this>". */
        String startsWhen();

        /** A Pokémon of the player died (already recorded and written in the journal). */
        void died(Room r, Room.Member m, Room.MonRec rec, Room.Out out);

        /** The whole team of the player is knocked out (already written in the journal). */
        void whiteout(Room r, Room.Member m, Room.Out out);

        /** Whether living Pokémon of the team may be sent to the PC (Sololocke, Hardcore level cap): own runs only. */
        boolean parksTeam();

        /** Sololocke "les autres vont au PC": one living Pokémon in the team. */
        boolean soloTeam(Room r);

        /** The Hardcore option applies (level cap, no items in battle, a team knocked out loses): own runs only. */
        boolean hardcore(Room r);

        /** The classic rules are applied in the games (items, Fly, battle style "Set"): own runs only. */
        boolean classicRules();

        /** Said at the launch when deaths only count from the first Poké Ball (rule "startWithBalls"). */
        String launchNotice(Room r);

        /** Do the deaths count for this player yet? */
        boolean started(Room r, Room.Member m);

        /** The room is running and has not started yet: start it if its condition is met. */
        void checkStart(Room r, Room.Out out);

        /** The player beat the Champion and the run ends by itself (rule "autoEnd"): does the room end? */
        void champion(Room r, Room.Member m, Room.Out out);
    }

    private static final EncounterLogic SOUL_LINK = new SoulLinkEncounters();
    private static final EncounterLogic INDIVIDUAL = new IndividualEncounters();
    private static final EncounterLogic SHINYLOCKE = new ShinylockeEncounters();
    private static final CaptureLogic SOUL_LINK_CAPTURES = new SoulLinkCaptures();
    private static final CaptureLogic INDIVIDUAL_CAPTURES = new IndividualCaptures();
    private static final CaptureLogic SHINYLOCKE_CAPTURES = new ShinylockeCaptures();
    private static final RunLogic SOUL_LINK_RUN = new SoulLinkRun();
    private static final RunLogic INDIVIDUAL_RUN = new IndividualRun();
    private static final RunLogic SOLOLOCKE_RUN = new SololockeRun();

    private final String id;
    private final EncounterLogic encounters;
    private final CaptureLogic captures;
    private final RunLogic run;
    private final List<Rule> rules;

    Mode(String id, EncounterLogic encounters, CaptureLogic captures, RunLogic run, List<Rule> rules) {
        this.id = id;
        this.encounters = encounters;
        this.captures = captures;
        this.run = run;
        this.rules = rules;
    }

    RunLogic run() {
        return run;
    }

    String id() {
        return id;
    }

    EncounterLogic encounters() {
        return encounters;
    }

    CaptureLogic captures() {
        return captures;
    }

    /** Runs every rule of the mode on the event, in order. */
    void handle(Ctx ctx, Map<String, Object> ev) {
        for (Rule rule : rules) {
            rule.onEvent(ctx, ev);
        }
    }

    /** The mode of a room: its encounter and capture logic, then Room's handlers for the events not moved yet. */
    static Mode forRoom(Room room) {
        return switch (room.mode) {
            case "shinylocke" -> of(room.mode, SHINYLOCKE, SHINYLOCKE_CAPTURES, INDIVIDUAL_RUN);
            case "nuzlocke" -> of(room.mode, INDIVIDUAL, INDIVIDUAL_CAPTURES, INDIVIDUAL_RUN);
            case "sololocke" -> of(room.mode, INDIVIDUAL, INDIVIDUAL_CAPTURES, SOLOLOCKE_RUN);
            default -> of(room.mode, SOUL_LINK, SOUL_LINK_CAPTURES, SOUL_LINK_RUN);
        };
    }

    private static Mode of(String id, EncounterLogic enc, CaptureLogic captures, RunLogic run) {
        return new Mode(id, enc, captures, run, List.of(new EncounterRule(enc), new LegacyDispatch()));
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

    /** The room's players are linked: a starter or a gift links through its zone, a shiny or a second gift does not. */
    private static final class SoulLinkCaptures implements CaptureLogic {

        @Override
        public void starter(Room r, Room.Member m, Room.MonRec rec, Room.Out out) {
            // the first Pokémon received is the starter: linked with the other starters
            r.link(m, rec, r.zone(Room.STARTER, "Départ"), null, out);
        }

        @Override
        public void gift(Room r, Room.Member m, Room.MonRec rec, String zone, Room.Out out) {
            if (!r.rule("giftPokemon")) {
                r.release(m, rec, "interdit : Pokémon offert ou échangé", out);
                return;
            }
            Room.Zone z = r.zones.get(Room.zoneKey(zone));
            if (z == null || !z.entries.containsKey(m.userId)) {
                r.link(m, rec, r.zone(Room.zoneKey(zone), zone), null, out);
            } else {
                r.bonus(m, rec, "Pokémon offert", out);
            }
        }

        @Override
        public boolean notepad(Room r) {
            return false;
        }

        @Override
        public boolean hmException(Room r) {
            return false;
        }

        @Override
        public void linked(Room r, Room.Member m, Room.MonRec rec, Room.Zone z, Room.Out out) {
            if (z.nickname != null) {
                rec.nickname = z.nickname;
            }
            switch (z.status) {
                case "dead" -> {
                    rec.status = "dead";
                    rec.reason = "lien déjà perdu";
                    out.log(m, "death", rec.label() + " rejoint ses âmes liées : le lien de " + z.name + " était déjà perdu.", null);
                    out.notice(m, "warn", "Le lien de " + z.name + " était déjà perdu : ce Pokémon est mort d'office.");
                }
                case "cancelled" -> {
                    rec.status = "released";
                    rec.reason = "zone annulée";
                }
                default -> r.refreshZone(z, out);
            }
        }

        @Override
        public String bonusSuffix() {
            return ", sans lien";
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

    /** Each player alone: nobody to link with; the classic rules (full team, HMs) may apply. */
    private static class IndividualCaptures implements CaptureLogic {

        @Override
        public void starter(Room r, Room.Member m, Room.MonRec rec, Room.Out out) {
            r.bonus(m, rec, "Pokémon de départ", out);
            r.parkCheck(m, out);
        }

        @Override
        public void gift(Room r, Room.Member m, Room.MonRec rec, String zone, Room.Out out) {
            if (!r.rule("giftPokemon")) {
                r.release(m, rec, "interdit : Pokémon offert ou échangé", out);
                return;
            }
            r.bonus(m, rec, "Pokémon offert", out);
        }

        @Override
        public boolean notepad(Room r) {
            return r.rule("honorNotepad");
        }

        @Override
        public boolean hmException(Room r) {
            return r.rule("honorCsException");
        }

        @Override
        public void linked(Room r, Room.Member m, Room.MonRec rec, Room.Zone z, Room.Out out) {
            // alone: nobody to link with, the Pokémon is simply the player's for the zone
        }

        @Override
        public String bonusSuffix() {
            return "";
        }
    }

    // ------------------------------------------------------------ Shinylocke

    /** A gift is kept when it is shiny; otherwise as in the other runs of one player. */
    private static final class ShinylockeCaptures extends IndividualCaptures {

        @Override
        public void gift(Room r, Room.Member m, Room.MonRec rec, String zone, Room.Out out) {
            if (rec.shiny) {
                r.bonus(m, rec, "Pokémon offert chromatique", out);
                return;
            }
            if (!r.rule("giftPokemon")) {
                r.release(m, rec, "interdit : Pokémon offert non chromatique", out);
                return;
            }
            r.bonus(m, rec, "Pokémon offert", out);
        }
    }

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

    // ================================================================ runs

    /** Soul Link: a death kills the linked souls of the partners; a team knocked out ends the room's run. */
    private static final class SoulLinkRun implements RunLogic {

        @Override
        public String startsWhen() {
            return "quand chaque joueur a une Poké Ball.";
        }

        @Override
        public void died(Room r, Room.Member m, Room.MonRec rec, Room.Out out) {
            if (rec.bonus || rec.zoneKey == null) {
                return;
            }
            Room.Zone z = r.zones.get(rec.zoneKey);
            if (z == null || "cancelled".equals(z.status) || "dead".equals(z.status)) {
                return;
            }
            z.status = "dead";
            r.namingQueue.remove(z.key);
            for (Map.Entry<String, Room.Entry> x : z.entries.entrySet()) {
                Room.Member owner = r.member(x.getKey());
                if (owner == null || owner == m) {
                    continue;
                }
                Room.MonRec linked = owner.mons.get(x.getValue().uid);
                if (linked != null && "alive".equals(linked.status)) {
                    linked.status = "dead";
                    linked.reason = "âme liée à " + rec.label();
                    // not counted in the partner's deaths: only the player whose Pokémon fell first is
                    out.log(owner, "death", "☠ " + linked.label() + " de " + owner.name + " meurt avec son âme liée", null);
                    out.notice(owner, "danger", linked.label() + " est mort : son âme liée de " + m.name + " est tombée.");
                }
            }
        }

        @Override
        public void whiteout(Room r, Room.Member m, Room.Out out) {
            if (r.rule("wipeEndsRun") && r.linkStarted) {
                m.status = "wiped";
                r.finish("defeat", "L'équipe de " + m.name + " est tombée.", out);
            }
        }

        @Override
        public boolean parksTeam() {
            return false;
        }

        @Override
        public boolean soloTeam(Room r) {
            return false;
        }

        @Override
        public boolean hardcore(Room r) {
            return false;
        }

        @Override
        public boolean classicRules() {
            return false;
        }

        @Override
        public String launchNotice(Room r) {
            return "Le Soul Link commencera vraiment quand chaque joueur aura une Poké Ball : d'ici là, un Pokémon K.O. ne meurt pas.";
        }

        @Override
        public boolean started(Room r, Room.Member m) {
            return r.linkStarted;
        }

        /** The Soul Link really starts when every player has had a Poké Ball (until then, nobody dies). */
        @Override
        public void checkStart(Room r, Room.Out out) {
            if (r.rule("startWithBalls") && !r.members.stream().allMatch(x -> x.hasBalls)) {
                return;
            }
            r.linkStarted = true;
            out.log(null, "start", r.rule("startWithBalls")
                    ? "Tout le monde a des Poké Balls : le Soul Link commence vraiment. Désormais, un Pokémon K.O. est mort."
                    : "Le Soul Link commence : un Pokémon K.O. est mort.", null);
            for (Room.Member x : r.members) {
                out.notice(x, "warn", "Le Soul Link commence : désormais, un Pokémon K.O. est mort, avec son âme liée.");
            }
        }

        @Override
        public void champion(Room r, Room.Member m, Room.Out out) {
            boolean all = r.members.stream().allMatch(x -> "champion".equals(x.status));
            if ("first".equals(r.ruleText("endWhen")) || all) {
                r.finish("victory", m.name + " a battu le Maître de la Ligue.", out);
            }
        }
    }

    /** Each player's own run (Nuzlocke, Shinylocke): a death is the player's alone; a team knocked out loses the run. */
    private static class IndividualRun implements RunLogic {

        @Override
        public String startsWhen() {
            return "à votre première Poké Ball.";
        }

        @Override
        public void died(Room r, Room.Member m, Room.MonRec rec, Room.Out out) {
            // nobody is linked: the death is this player's alone
        }

        @Override
        public void whiteout(Room r, Room.Member m, Room.Out out) {
            if (hardcoreWipe(r, m, out)) {
                return;
            }
            if (r.rule("wipeEndsRun") && r.started(m) && !"wiped".equals(m.status)) {
                m.status = "wiped";
                out.log(m, "wipe", "☠ " + m.name + " a perdu son Nuzlocke : toute son équipe est tombée.", null);
                r.endWhenAllDone(out);
            }
        }

        @Override
        public boolean parksTeam() {
            return true;
        }

        @Override
        public boolean soloTeam(Room r) {
            return false;
        }

        @Override
        public boolean hardcore(Room r) {
            return r.rule("hardcore");
        }

        @Override
        public boolean classicRules() {
            return true;
        }

        @Override
        public String launchNotice(Room r) {
            return "Le " + r.runName() + " de chaque joueur commence à sa première Poké Ball : d'ici là, un Pokémon K.O. ne meurt pas.";
        }

        @Override
        public boolean started(Room r, Room.Member m) {
            return !r.rule("startWithBalls") || m.hasBalls;
        }

        @Override
        public void checkStart(Room r, Room.Out out) {
            r.linkStarted = true; // nobody waits for the others: each player's rules start with their own first Poké Ball
        }

        /** Each player's own run: it ends with the first Champion, or when every player has beaten the League or lost. */
        @Override
        public void champion(Room r, Room.Member m, Room.Out out) {
            if ("first".equals(r.ruleText("endWhen"))) {
                r.finish("victory", m.name + " a battu le Maître de la Ligue.", out);
            } else {
                r.endWhenAllDone(out);
            }
        }

        /** Hardcore: a whiteout loses the run, whatever the other rules say. */
        static boolean hardcoreWipe(Room r, Room.Member m, Room.Out out) {
            if (!r.rule("hardcore") || !r.started(m) || "wiped".equals(m.status) || "champion".equals(m.status)) {
                return false;
            }
            m.status = "wiped";
            out.log(m, "wipe", "☠ " + m.name + " a perdu son " + r.runName() + " Hardcore : toute son équipe est tombée.", null);
            r.endWhenAllDone(out);
            return true;
        }
    }

    /** Sololocke: the death of the Pokémon alone decides (another one of the PC takes over, or the run is lost). */
    private static final class SololockeRun extends IndividualRun {

        @Override
        public void died(Room r, Room.Member m, Room.MonRec rec, Room.Out out) {
            r.soloFallen(m, rec, out);
        }

        @Override
        public void whiteout(Room r, Room.Member m, Room.Out out) {
            // the death of the Pokémon already decided (soloFallen), unless Hardcore loses the run here
            hardcoreWipe(r, m, out);
        }

        @Override
        public boolean soloTeam(Room r) {
            return "auto".equals(r.ruleText("soloParty"));
        }
    }
}
