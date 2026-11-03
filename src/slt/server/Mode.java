package slt.server;

import slt.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A game mode seen as an ordered list of rules applied to each event (point 1c of the evolution plan), built from its
 * declarative file (point 2, {@link ModeSpec}). The file chooses three primitives of the engine — the players are
 * linked or not, the first wild Pokémon of a zone counts or only shinies do, the team is free or holds one Pokémon —
 * and every combination runs on the same {@link Engine}. Wild encounters are handled by the engine
 * ({@link EncounterRule}); every other event still goes through Room's handlers ({@link LegacyDispatch}), which ask the
 * engine where the modes differ. The server stays the referee and the directives stay the only channel to the
 * adapters.
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

    /** How the mode treats wild encounters. */
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

    /** How the mode judges a Pokémon that joins a player (Room keeps the steps common to every mode). */
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

    /** How the mode runs a player's adventure: deaths, team, Hardcore, start and end. */
    interface RunLogic {

        /** When deaths start to count, as told to a player whose Pokémon fell before: "… commence <this>". */
        String startsWhen();

        /** A Pokémon of the player died (already recorded and written in the journal). */
        void died(Room r, Room.Member m, Room.MonRec rec, Room.Out out);

        /** The whole team of the player is knocked out (already written in the journal). */
        void whiteout(Room r, Room.Member m, Room.Out out);

        /** The player lost their run (Sololocke without a Pokémon left, for example): what it means for the room. */
        void playerLost(Room r, Room.Member m, Room.Out out);

        /** Whether living Pokémon of the team may be sent to the PC (one Pokémon, Hardcore level cap). */
        boolean parksTeam();

        /** "Les autres vont au PC": one living Pokémon in the team. */
        boolean soloTeam(Room r);

        /** The Hardcore option applies (level cap, no items in battle, a team knocked out loses). */
        boolean hardcore(Room r);

        /** The classic rules are applied in the games (items, Fly, battle style "Set"). */
        boolean classicRules();

        /** Said at the launch when deaths only count from the first Poké Ball (rule "startWithBalls"). */
        String launchNotice(Room r);

        /** Do the deaths count for this player yet? */
        boolean started(Room r, Room.Member m);

        /** The room is running and has not started yet: start it if its condition is met. */
        void checkStart(Room r, Room.Out out);

        /** The player beat the Champion and the run ends by itself: does the room end? */
        void champion(Room r, Room.Member m, Room.Out out);
    }

    private final ModeSpec spec;
    private final Engine engine;
    private final List<Rule> rules;

    private Mode(ModeSpec spec, Engine engine) {
        this.spec = spec;
        this.engine = engine;
        this.rules = List.of(new EncounterRule(engine), new LegacyDispatch());
    }

    String id() {
        return spec.id;
    }

    ModeSpec spec() {
        return spec;
    }

    EncounterLogic encounters() {
        return engine;
    }

    CaptureLogic captures() {
        return engine;
    }

    RunLogic run() {
        return engine;
    }

    /** Runs every rule of the mode on the event, in order. */
    void handle(Ctx ctx, Map<String, Object> ev) {
        for (Rule rule : rules) {
            rule.onEvent(ctx, ev);
        }
    }

    /** The engines of the 8 combinations of primitives (each stateless, made once). */
    private static final Engine[] ENGINES = new Engine[8];

    static {
        for (int i = 0; i < ENGINES.length; i++) {
            ENGINES[i] = new Engine((i & 1) != 0, (i & 2) != 0, (i & 4) != 0);
        }
    }

    /** The mode of a room: its file, run by the engine of its primitives. */
    static Mode forRoom(Room room) {
        ModeSpec spec = room.spec();
        int i = (spec.shared() ? 1 : 0) | (spec.shiniesOnly() ? 2 : 0) | (spec.solo() ? 4 : 0);
        return new Mode(spec, ENGINES[i]);
    }

    /** Wild encounters (start and end), through the engine. */
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

    // ============================================================== engine

    /**
     * The engine of one combination of primitives. {@code shared}: the players' Pokémon of a zone are linked (Soul
     * Link) — otherwise each player has their own run. {@code shinies}: only shinies may be caught (Shinylocke) —
     * otherwise the first wild Pokémon of each zone counts. {@code solo}: one living Pokémon in the team (Sololocke).
     * The historical modes are four of the eight combinations; the others combine the same pieces.
     */
    private static final class Engine implements EncounterLogic, CaptureLogic, RunLogic {

        private final boolean shared;
        private final boolean shinies;
        private final boolean solo;

        Engine(boolean shared, boolean shinies, boolean solo) {
            this.shared = shared;
            this.shinies = shinies;
            this.solo = solo;
        }

        // ------------------------------------------------------ encounters

        @Override
        public String classify(Room r, Room.Member m, String key, int species, boolean shiny) {
            // linked players wait for their own first Poké Ball; a player alone for the start of their run
            boolean noBalls = shared ? !m.hasBalls && r.rule("startWithBalls") : !r.started(m);
            if (shinies) {
                // only shinies may be caught, anywhere (one per zone if the room wants)
                if (noBalls) {
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
                if (r.rule("dupesClause") && r.ownedFamily(m, species, key, shared)) {
                    return "dupe";
                }
                return "zone";
            }
            if (noBalls) {
                return "noballs";
            }
            if (shiny && r.rule("shinyClause")) {
                return "shiny";
            }
            if (Room.LEGENDARIES.contains(species) && !r.rule("legendaries")) {
                return "legendary";
            }
            Room.Zone z = r.zones.get(key);
            if (shared && z != null && "cancelled".equals(z.status)) {
                return "cancelled";
            }
            if (z != null && z.entries.containsKey(m.userId)) {
                return "used";
            }
            if (r.rule("dupesClause") && r.ownedFamily(m, species, key, shared)) {
                return "dupe";
            }
            return "zone";
        }

        @Override
        public String zoneHint(Room r, Room.Zone z, Room.Entry mine) {
            if (shinies) {
                return mine != null && "caught".equals(mine.result) && r.rule("oneShinyPerZone")
                        ? " — chromatique déjà capturé ici" : "";
            }
            if (!shared) {
                return mine != null ? " — première rencontre déjà faite ici" : "";
            }
            if (mine != null) {
                return " — capture déjà faite ici";
            }
            return z.entries.isEmpty() ? "" : " — déjà capturée par un partenaire";
        }

        @Override
        public void start(Ctx ctx, Map<String, Object> ev) {
            Room r = ctx.room;
            Room.Member m = ctx.member;
            Room.Out out = ctx.out;
            String zone = Room.zoneName(Json.str(ev, "zone"));
            String key = Room.zoneKey(zone);
            String kind = classify(r, m, key, (int) Json.num(ev, "species", 0), Json.bool(ev, "shiny", false));
            EncounterLogic.open(ctx, ev, zone, key, kind);
            switch (kind) {
                case "zone" -> {
                    if (shinies) {
                        out.notice(m, "ok", "Chromatique ! Vous pouvez le capturer.");
                    } else if (shared) {
                        out.notice(m, "info", "Première rencontre de " + zone + " : elle compte pour le " + r.runName() + ".");
                    } else {
                        out.notice(m, "info", "Première rencontre de " + zone + " : c'est votre seule chance ici (fuir ou le battre l'épuise).");
                    }
                }
                case "shiny" -> out.notice(m, "ok", "Chromatique ! Capture libre (clause chromatique), sans lien.");
                case "used" -> out.notice(m, "warn", zone + (shinies ? " : vous y avez déjà capturé un chromatique."
                        : shared ? " : vous y avez déjà capturé ou mis K.O. un Pokémon." : " : vous y avez déjà rencontré un Pokémon.")
                        + " Capture interdite.");
                case "cancelled" -> out.notice(m, "warn", zone + " est annulée pour la salle. Capture interdite.");
                case "dupe" -> out.notice(m, "warn", shared
                        ? "Doublon : cette lignée d'évolution est déjà dans la salle. Rencontre ignorée, fuyez ou mettez-le K.O."
                        : "Doublon : vous avez déjà cette lignée d'évolution. Rencontre ignorée, fuyez ou mettez-le K.O.");
                case "legendary" -> out.notice(m, "warn", "Légendaire interdit par les règles : ne le capturez pas.");
                case "noballs" -> out.notice(m, "info",
                        "Pas encore de Poké Ball : cette rencontre ne compte pas pour le " + r.runName() + ".");
                default -> {
                    // not shiny (said at the capture), or nothing to say
                }
            }
        }

        @Override
        public void end(Ctx ctx, Map<String, Object> ev) {
            Room.Encounter e = EncounterLogic.closing(ctx, ev);
            if (e == null) {
                return;
            }
            Room r = ctx.room;
            Room.Member m = ctx.member;
            Room.Out out = ctx.out;
            boolean killed = Json.bool(ev, "wildFainted", false);
            String who = e.speciesName == null ? "#" + e.species : e.speciesName;
            String did = m.name + (killed ? " met K.O. " : " quitte le combat contre ") + who;
            if (shinies) {
                // a battle never uses up a zone: only a shiny caught does
                if (killed) {
                    m.stats.wildKills++;
                }
                out.log(m, killed ? "kill" : "flee", did + " (" + e.zone + ")", Json.map("zone", e.zone));
                return;
            }
            if (!shared) {
                if (killed) {
                    m.stats.wildKills++;
                }
                if (!"zone".equals(e.kind)) {
                    out.log(m, killed ? "kill" : "flee", did + " (" + e.zone + ")", Json.map("zone", e.zone));
                    return;
                }
                // the first Pokémon of the zone got away or was beaten: no second chance in this zone
                Room.Zone z = r.zone(e.zoneKey, e.zone);
                if (!z.entries.containsKey(m.userId)) {
                    z.entries.put(m.userId, entry(e, killed ? "killed" : "fled"));
                }
                out.log(m, killed ? "kill" : "flee", did + ", première rencontre de " + e.zone + " : pas de seconde chance ici",
                        Json.map("zone", e.zone));
                return;
            }
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
            Room.Zone z = r.zone(e.zoneKey, e.zone);
            z.entries.put(m.userId, entry(e, "killed"));
            out.log(m, "kill", m.name + " met K.O. " + who + ", premier Pokémon de " + e.zone + " : zone annulée",
                    Json.map("zone", e.zone));
            r.cancel(z, out);
        }

        private static Room.Entry entry(Room.Encounter e, String result) {
            Room.Entry entry = new Room.Entry();
            entry.result = result;
            entry.uid = e.uid;
            entry.species = e.species;
            entry.speciesName = e.speciesName;
            entry.level = e.level;
            entry.at = System.currentTimeMillis();
            return entry;
        }

        // -------------------------------------------------------- captures

        @Override
        public void starter(Room r, Room.Member m, Room.MonRec rec, Room.Out out) {
            if (shared) {
                // the first Pokémon received is the starter: linked with the other starters
                r.link(m, rec, r.zone(Room.STARTER, "Départ"), null, out);
            } else {
                r.bonus(m, rec, "Pokémon de départ", out);
            }
            if (!shared || solo) {
                r.parkCheck(m, out);
            }
        }

        @Override
        public void gift(Room r, Room.Member m, Room.MonRec rec, String zone, Room.Out out) {
            if (shinies) {
                // a gift is kept when it is shiny; it never links
                if (rec.shiny) {
                    r.bonus(m, rec, "Pokémon offert chromatique", out);
                } else if (!r.rule("giftPokemon")) {
                    r.release(m, rec, "interdit : Pokémon offert non chromatique", out);
                } else {
                    r.bonus(m, rec, "Pokémon offert", out);
                }
                return;
            }
            if (!r.rule("giftPokemon")) {
                r.release(m, rec, "interdit : Pokémon offert ou échangé", out);
                return;
            }
            if (!shared) {
                r.bonus(m, rec, "Pokémon offert", out);
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
            return !shared && r.rule("honorNotepad");
        }

        @Override
        public boolean hmException(Room r) {
            return !shared && r.rule("honorCsException");
        }

        @Override
        public void linked(Room r, Room.Member m, Room.MonRec rec, Room.Zone z, Room.Out out) {
            if (!shared) {
                return; // alone: nobody to link with, the Pokémon is simply the player's for the zone
            }
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
            return shared ? ", sans lien" : "";
        }

        // ------------------------------------------------------------ runs

        @Override
        public String startsWhen() {
            return shared ? "quand chaque joueur a une Poké Ball." : "à votre première Poké Ball.";
        }

        @Override
        public void died(Room r, Room.Member m, Room.MonRec rec, Room.Out out) {
            List<Room.Member> owners = new ArrayList<>();
            List<Room.MonRec> fallen = new ArrayList<>();
            if (shared && !rec.bonus && rec.zoneKey != null) {
                Room.Zone z = r.zones.get(rec.zoneKey);
                if (z != null && !"cancelled".equals(z.status) && !"dead".equals(z.status)) {
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
                            owners.add(owner);
                            fallen.add(linked);
                        }
                    }
                }
            }
            if (solo) {
                // the Pokémon alone decides: another one of the PC takes over, or the run is lost
                r.soloFallen(m, rec, out);
                for (int i = 0; i < owners.size(); i++) {
                    r.soloFallen(owners.get(i), fallen.get(i), out);
                }
            }
        }

        @Override
        public void whiteout(Room r, Room.Member m, Room.Out out) {
            if (!shared && hardcoreWipe(r, m, out)) {
                return;
            }
            if (solo || !r.spec().loses("teamWiped") || !r.rule("wipeEndsRun")) {
                return; // one Pokémon: its death already decided (soloFallen)
            }
            if (shared) {
                if (r.linkStarted) {
                    m.status = "wiped";
                    r.finish("defeat", "L'équipe de " + m.name + " est tombée.", out);
                }
                return;
            }
            if (r.started(m) && !"wiped".equals(m.status)) {
                m.status = "wiped";
                out.log(m, "wipe", "☠ " + m.name + " a perdu son " + r.runName() + " : toute son équipe est tombée.", null);
                r.endWhenAllDone(out);
            }
        }

        /** Hardcore: a whiteout loses the run, whatever the other rules say. */
        private boolean hardcoreWipe(Room r, Room.Member m, Room.Out out) {
            if (!r.rule("hardcore") || !r.started(m) || "wiped".equals(m.status) || "champion".equals(m.status)) {
                return false;
            }
            m.status = "wiped";
            String run = r.runName().contains("Hardcore") ? r.runName() : r.runName() + " Hardcore";
            out.log(m, "wipe", "☠ " + m.name + " a perdu son " + run + " : toute son équipe est tombée.", null);
            r.endWhenAllDone(out);
            return true;
        }

        @Override
        public void playerLost(Room r, Room.Member m, Room.Out out) {
            if (shared) {
                r.finish("defeat", "L'équipe de " + m.name + " est tombée.", out);
            } else {
                r.endWhenAllDone(out);
            }
        }

        @Override
        public boolean parksTeam() {
            return !shared || solo;
        }

        @Override
        public boolean soloTeam(Room r) {
            return solo && "auto".equals(r.ruleText("soloParty"));
        }

        @Override
        public boolean hardcore(Room r) {
            return !shared && r.rule("hardcore");
        }

        @Override
        public boolean classicRules() {
            return !shared;
        }

        @Override
        public String launchNotice(Room r) {
            return shared
                    ? "Le " + r.runName() + " commencera vraiment quand chaque joueur aura une Poké Ball : d'ici là, un Pokémon K.O. ne meurt pas."
                    : "Le " + r.runName() + " de chaque joueur commence à sa première Poké Ball : d'ici là, un Pokémon K.O. ne meurt pas.";
        }

        @Override
        public boolean started(Room r, Room.Member m) {
            return shared ? r.linkStarted : !r.rule("startWithBalls") || m.hasBalls;
        }

        @Override
        public void checkStart(Room r, Room.Out out) {
            if (!shared) {
                r.linkStarted = true; // nobody waits for the others: each player's rules start with their own first Poké Ball
                return;
            }
            // the linked run really starts when every player has had a Poké Ball (until then, nobody dies)
            if (r.rule("startWithBalls") && !r.members.stream().allMatch(x -> x.hasBalls)) {
                return;
            }
            r.linkStarted = true;
            out.log(null, "start", r.rule("startWithBalls")
                    ? "Tout le monde a des Poké Balls : le " + r.runName() + " commence vraiment. Désormais, un Pokémon K.O. est mort."
                    : "Le " + r.runName() + " commence : un Pokémon K.O. est mort.", null);
            for (Room.Member x : r.members) {
                out.notice(x, "warn", "Le " + r.runName() + " commence : désormais, un Pokémon K.O. est mort, avec son âme liée.");
            }
        }

        @Override
        public void champion(Room r, Room.Member m, Room.Out out) {
            if (shared) {
                boolean all = r.members.stream().allMatch(x -> "champion".equals(x.status));
                if ("first".equals(r.ruleText("endWhen")) || all) {
                    r.finish("victory", m.name + " a battu le Maître de la Ligue.", out);
                }
            } else if ("first".equals(r.ruleText("endWhen"))) {
                r.finish("victory", m.name + " a battu le Maître de la Ligue.", out);
            } else {
                r.endWhenAllDone(out);
            }
        }
    }
}
