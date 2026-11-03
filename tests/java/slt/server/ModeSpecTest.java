package slt.server;

import slt.Http;
import slt.Json;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Checks the mode files (point 2a): the official modes reproduce exactly the rules the code gave before (same values,
 * same order), the imported modes are refused with a clear message whenever they go beyond the primitives, and the
 * rules a mode fixes cannot change.
 */
public final class ModeSpecTest {

    private static int failed;

    /** What Room.defaultRules gave before the modes became files (0.11.2). */
    private static final Map<String, String> FORMER = Map.of(
            "soullink", "{\"shinyClause\":true,\"dupesClause\":true,\"giftPokemon\":false,\"legendaries\":true,\"naming\":\"leader\",\"pauseForNaming\":true,\"deathBox\":true,\"refundCancelled\":true,\"wipeEndsRun\":true,\"endWhen\":\"all\",\"autoEnd\":true,\"startWithBalls\":true,\"saveStates\":false}",
            "nuzlocke", "{\"shinyClause\":false,\"dupesClause\":false,\"giftPokemon\":true,\"legendaries\":true,\"naming\":\"free\",\"pauseForNaming\":false,\"deathBox\":true,\"refundCancelled\":false,\"wipeEndsRun\":true,\"endWhen\":\"all\",\"autoEnd\":true,\"startWithBalls\":true,\"saveStates\":false,\"honorNoHealItems\":false,\"honorNoHeldItems\":false,\"honorNoMultiExp\":false,\"honorNoMasterBall\":false,\"honorNoEvolution\":false,\"honorNoFly\":false,\"honorNoRepel\":false,\"honorSetMode\":false,\"honorCsException\":false,\"honorNotepad\":false,\"hardcore\":false,\"hardcoreItems\":\"report\"}",
            "sololocke", "{\"shinyClause\":false,\"dupesClause\":false,\"giftPokemon\":true,\"legendaries\":true,\"naming\":\"free\",\"pauseForNaming\":false,\"deathBox\":true,\"refundCancelled\":false,\"endWhen\":\"all\",\"autoEnd\":true,\"startWithBalls\":true,\"saveStates\":false,\"honorNoHealItems\":false,\"honorNoHeldItems\":false,\"honorNoMasterBall\":false,\"honorNoEvolution\":false,\"honorNoFly\":false,\"honorNoRepel\":false,\"honorSetMode\":false,\"honorCsException\":false,\"hardcore\":false,\"hardcoreItems\":\"report\",\"soloParty\":\"auto\",\"soloLoss\":\"next\"}",
            "shinylocke", "{\"dupesClause\":false,\"giftPokemon\":false,\"legendaries\":true,\"naming\":\"free\",\"pauseForNaming\":false,\"deathBox\":true,\"refundCancelled\":false,\"wipeEndsRun\":true,\"endWhen\":\"all\",\"autoEnd\":true,\"startWithBalls\":true,\"saveStates\":false,\"honorNoHealItems\":false,\"honorNoHeldItems\":false,\"honorNoMultiExp\":false,\"honorNoMasterBall\":false,\"honorNoEvolution\":false,\"honorNoFly\":false,\"honorNoRepel\":false,\"honorSetMode\":false,\"honorCsException\":false,\"honorNotepad\":false,\"hardcore\":false,\"hardcoreItems\":\"report\",\"oneShinyPerZone\":false}");
    private static final Map<String, String> FORMER_PLAYERS = Map.of(
            "soullink", "2-5", "nuzlocke", "1-10", "sololocke", "1-10", "shinylocke", "1-10");

    public static void main(String[] args) {
        // the official modes: the same rules as before, in the same order
        Map<String, ModeSpec> officials = ModeSpec.officials();
        check(officials.keySet().equals(new java.util.LinkedHashSet<>(List.of("soullink", "nuzlocke", "sololocke",
                "shinylocke", "nuzlocke-hardcore"))), "les 5 modes officiels sont chargés");
        for (Map.Entry<String, String> e : FORMER.entrySet()) {
            ModeSpec s = officials.get(e.getKey());
            check(Json.stringify(s.defaults()).equals(e.getValue()), e.getKey() + " : mêmes règles par défaut qu'avant");
            check((s.minPlayers + "-" + s.maxPlayers).equals(FORMER_PLAYERS.get(e.getKey())), e.getKey() + " : mêmes nombres de joueurs");
        }
        ModeSpec hc = officials.get("nuzlocke-hardcore");
        Map<String, Object> expected = new LinkedHashMap<>(officials.get("nuzlocke").defaults());
        expected.put("hardcore", true);
        check(hc.defaults().equals(expected) && hc.locked("hardcore"), "Nuzlocke Hardcore : le Nuzlocke avec le Hardcore fixé");
        // until the interface reads the mode files (2c), capabilities.json says the same about what a mode needs
        Map<String, Object> capModes = Json.obj(Capabilities.data().get("modes"));
        for (ModeSpec s : officials.values()) {
            List<Object> listed = Json.arr(capModes.get(s.id));
            check((listed == null ? List.of() : listed).equals(new java.util.ArrayList<Object>(s.needs)),
                    s.id + " : mêmes besoins dans capabilities.json");
        }

        // a mode of one's own: a combination the historical modes do not use is accepted
        ModeSpec shinySolo = ModeSpec.parse(mode(m -> {
            engine(m, "none", "shiniesOnly", "one");
            m.put("needs", List.of("shinies"));
            m.put("rules", Json.map("autoEnd", true, "endWhen", "all", "soloParty", "auto", "soloLoss", "next",
                    "oneShinyPerZone", Json.map("default", true, "locked", true)));
            m.put("lose", List.of("soloLost"));
        }), false);
        check(shinySolo.solo() && shinySolo.shiniesOnly() && shinySolo.locked("oneShinyPerZone"), "un Shiny Sololocke importé est accepté");
        // the normalized form (kept in the room, given back by /api/modes/check) reads back as the same mode
        String once = Json.stringify(shinySolo.toJson());
        check(Json.stringify(ModeSpec.parse(once, false).toJson()).equals(once), "un mode relu depuis sa forme normalisée est le même");
        for (ModeSpec s : officials.values()) {
            check(Json.stringify(ModeSpec.parse(Json.stringify(s.toJson()), true).toJson()).equals(Json.stringify(s.toJson())),
                    s.id + " : forme normalisée relue à l'identique");
        }
        failsWith(() -> Rooms.importMode(mode(m -> m.put("id", "nuzlocke"))), 400, "mode officiel");
        check(Rooms.importMode(mode(m -> { })).id.equals("mon-mode"), "un mode à soi s'importe");

        // what an imported mode may not do: each refused with a clear message
        refused(mode(m -> m.put("script", "x")), "champ inconnu « script »");
        refused(mode(m -> m.put("schemaVersion", 2)), "schemaVersion");
        refused(mode(m -> m.put("id", "Mon Mode")), "« id »");
        refused(mode(m -> m.put("description", "La ROM est sur http://exemple.test/rom")), "pas d'adresse");
        refused(mode(m -> m.put("description", "Voir rom-gratuite.com")), "pas d'adresse");
        refused(mode(m -> m.put("name", "<b>Mode</b>")), "balises interdits");
        refused(mode(m -> rules(m).put("teleport", true)), "règle inconnue « teleport »");
        refused(mode(m -> rules(m).put("honorNoEvolution", true)), "réservée aux modes officiels");
        refused(mode(m -> rules(m).put("naming", "leader")), "n'a pas de sens avec ces primitives");
        refused(mode(m -> {
            engine(m, "shared", "firstPerZone", "free");
            Json.obj(m.get("players")).put("min", 2L);
            rules(m).put("hardcore", true);
        }), "n'a pas de sens avec ces primitives");
        refused(mode(m -> rules(m).put("deathBox", "oui")), "true ou false");
        refused(mode(m -> Json.obj(m.get("players")).put("max", 0L)), "« players.max »");
        refused(mode(m -> engine(m, "shared", "firstPerZone", "free")), "au moins 2");
        refused(mode(m -> engine(m, "none", "shiniesOnly", "free")), "« shinies »");
        refused(mode(m -> {
            engine(m, "none", "firstPerZone", "one");
            m.put("lose", List.of());
        }), "« soloLost »");
        refused(mode(m -> engine(m, "none", "firstPerZone", "one")), "« teamWiped »");
        refused(mode(m -> m.put("win", List.of("champion", "everything"))), "« everything » inconnu");
        refused(mode(m -> rules(m).remove("autoEnd")), "« autoEnd »");
        refused(mode(m -> m.put("objectives", List.of(Json.map("type", "badges")))), "« count » manquant");
        refused(mode(m -> m.put("description", "x".repeat(ModeSpec.MAX_BYTES))), "trop gros");
        refused("{ pas du JSON", "JSON lisible");
        refused("[" .repeat(20000), "JSON lisible");

        // in a room: a rule the mode fixes, a value the rule does not allow, a rule without sense for the mode
        Room hardcore = room("nuzlocke-hardcore");
        failsWith(() -> hardcore.changeRules(hardcore.members.get(0), "Alice", Json.map("hardcore", false), new Room.Out()),
                409, "fixée par le mode");
        Room nuzlocke = room("nuzlocke");
        failsWith(() -> nuzlocke.changeRules(nuzlocke.members.get(0), "Alice", Json.map("naming", "leader"), new Room.Out()),
                409, "n'a pas de sens");
        failsWith(() -> nuzlocke.changeRules(nuzlocke.members.get(0), "Alice", Json.map("endWhen", "jamais"), new Room.Out()),
                400, "Valeur impossible");
        check(nuzlocke.changeRules(nuzlocke.members.get(0), "Alice", Json.map("hardcore", true), new Room.Out())
                .equals(List.of("hardcore")), "une règle permise change");

        System.out.println(failed == 0 ? "ok     ModeSpec (fichiers de mode)" : "ÉCHEC  ModeSpec : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** A valid imported mode (a Nuzlocke of one's own), changed by {@code edit}, as text. */
    private static String mode(java.util.function.Consumer<Map<String, Object>> edit) {
        Map<String, Object> m = Json.map("schemaVersion", 1L, "id", "mon-mode", "name", "Mon mode",
                "description", "Un mode de test.", "author", "Testeur", "license", "CC-BY-4.0",
                "players", Json.map("min", 1L, "max", 4L), "needs", new java.util.ArrayList<>(),
                "engine", Json.map("links", "none", "encounters", "firstPerZone", "team", "free"),
                "rules", Json.map("autoEnd", true, "endWhen", "all", "wipeEndsRun", true, "dupesClause", true),
                "win", List.of("champion"), "lose", List.of("teamWiped"), "objectives", List.of());
        edit.accept(m);
        return Json.stringify(m);
    }

    private static Map<String, Object> rules(Map<String, Object> m) {
        return Json.obj(m.get("rules"));
    }

    private static void engine(Map<String, Object> m, String links, String encounters, String team) {
        m.put("engine", Json.map("links", links, "encounters", encounters, "team", team));
        if ("one".equals(team)) {
            rules(m).remove("wipeEndsRun");
        }
    }

    private static void refused(String text, String why) {
        try {
            ModeSpec.parse(text, false);
            check(false, "aurait dû être refusé (" + why + ")");
        } catch (Http.Fail f) {
            check(f.status == 400 && f.getMessage().contains(why), "refus « " + why + " », reçu : " + f.getMessage());
        }
    }

    private static void failsWith(Runnable action, int status, String why) {
        try {
            action.run();
            check(false, "aurait dû être refusé (" + why + ")");
        } catch (Http.Fail f) {
            check(f.status == status && f.getMessage().contains(why), "refus " + status + " « " + why + " », reçu : "
                    + f.status + " " + f.getMessage());
        }
    }

    private static Room room(String mode) {
        Room r = new Room();
        r.mode = mode;
        r.rules = Room.defaultRules(mode);
        r.game = Json.map("gameId", "emerald", "name", "Pokémon Émeraude", "gen", 3L);
        Room.Member m = new Room.Member();
        m.userId = "alice";
        m.name = "Alice";
        r.members.add(m);
        r.leaderId = "alice";
        return r;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            System.out.println("  " + what);
            failed++;
        }
    }

    private ModeSpecTest() {
    }
}
