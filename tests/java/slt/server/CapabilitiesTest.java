package slt.server;

import slt.Http;
import slt.Json;

import java.util.List;
import java.util.Map;

/**
 * Checks the capabilities of the games (point 1d): web/js/data/capabilities.json is consistent, what each game can
 * do matches the adapters, and the server refuses a mode or a rule the game cannot play.
 */
public final class CapabilitiesTest {

    private static int failed;

    public static void main(String[] args) {
        Map<String, Object> d = Capabilities.data();
        check(!d.isEmpty(), "capabilities.json chargé");
        consistency(d);

        // what the adapters can do
        check(!Capabilities.has("red", 1, "shinies"), "pas de chromatiques en 1re génération");
        check(Capabilities.has("crystal", 2, "shinies"), "chromatiques en 2e génération");
        check(!Capabilities.has("yellow", 1, "heldItems"), "pas d'objets tenus en 1re génération");
        check(Capabilities.has("crystal", 2, "trainerBattles"), "Cristal lit les combats de dresseurs");
        check(!Capabilities.has("gold", 2, "trainerBattles"), "Or ne lit pas les combats de dresseurs");
        check(!Capabilities.has("platinum", 4, "trainerBattles"), "les jeux DS ne lisent pas les combats de dresseurs");
        check(Capabilities.has("white2", 5, "autoEnd"), "fin automatique en 5e génération");

        // modes and rules
        check(Capabilities.missingForMode("shinylocke", "red", 1) != null, "Shinylocke refusé en 1re génération");
        check(Capabilities.missingForMode("shinylocke", "crystal", 2) == null, "Shinylocke accepté en 2e génération");
        check(Capabilities.missingForMode("nuzlocke", "red", 1) == null, "Nuzlocke accepté en 1re génération");
        check(Capabilities.missingForMode("shinylocke", null, 0) == null, "jeu inconnu : rien de refusé");
        check(Capabilities.missingForRule("shinyClause", "red", 1) != null, "clause chromatique refusée en 1re génération");
        check(Capabilities.missingForRule("honorNoHeldItems", "red", 1) != null, "« pas d'objet tenu » refusé en 1re génération");
        check(Capabilities.missingForRule("honorNoHeldItems", "gold", 2) == null, "« pas d'objet tenu » accepté en 2e génération");
        check(Capabilities.missingForRule("legendaries", "red", 1) == null, "une règle sans besoin est toujours acceptée");

        // the server: a rule the game cannot apply is never turned on
        Room gen1 = room("nuzlocke", "red", 1);
        try {
            gen1.changeRules(gen1.members.get(0), "Alice", Json.map("honorNoHeldItems", true), new Room.Out());
            check(false, "activer « pas d'objet tenu » en 1re génération est refusé");
        } catch (Http.Fail f) {
            check(f.status == 409 && f.getMessage().contains("objets tenus"), "refus 409 avec la raison : " + f.getMessage());
        }
        Room gen2 = room("nuzlocke", "gold", 2);
        List<String> changed = gen2.changeRules(gen2.members.get(0), "Alice", Json.map("honorNoHeldItems", true), new Room.Out());
        check(changed.equals(List.of("honorNoHeldItems")), "activer « pas d'objet tenu » en 2e génération est accepté");
        // turning a rule off is never refused (a room of generation 1 may hold shinyClause from the defaults)
        Room soulLink = room("soullink", "red", 1);
        soulLink.changeRules(soulLink.members.get(0), "Alice", Json.map("shinyClause", false), new Room.Out());
        check(Boolean.FALSE.equals(soulLink.rules.get("shinyClause")), "désactiver une règle non prise en charge est accepté");

        System.out.println(failed == 0 ? "ok     Capabilities (capacités des jeux)" : "ÉCHEC  Capabilities : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Every capability named exists and is given for every generation; every rule named belongs to a mode. */
    private static void consistency(Map<String, Object> d) {
        Map<String, Object> caps = Json.obj(d.get("capabilities"));
        Map<String, Object> gens = Json.obj(d.get("generations"));
        for (int gen = 1; gen <= 5; gen++) {
            Map<String, Object> g = Json.obj(gens.get(String.valueOf(gen)));
            check(g != null, "génération " + gen + " décrite");
            for (String cap : caps.keySet()) {
                check(g != null && g.get(cap) instanceof Boolean, "génération " + gen + " : « " + cap + " » renseigné");
            }
        }
        for (String table : List.of("modes", "rules")) {
            for (Map.Entry<String, Object> e : Json.obj(d.get(table)).entrySet()) {
                for (Object cap : Json.arr(e.getValue())) {
                    check(caps.containsKey(String.valueOf(cap)), table + " " + e.getKey() + " : capacité « " + cap + " » connue");
                }
            }
        }
        for (String mode : Json.obj(d.get("modes")).keySet()) {
            check(Room.MODES.contains(mode), "mode « " + mode + " » connu");
        }
        for (String rule : Json.obj(d.get("rules")).keySet()) {
            check(Room.MODES.stream().anyMatch(m -> Room.defaultRules(m).containsKey(rule)), "règle « " + rule + " » connue");
        }
    }

    private static Room room(String mode, String gameId, int gen) {
        Room r = new Room();
        r.mode = mode;
        r.rules = Room.defaultRules(mode);
        r.game = Json.map("gameId", gameId, "name", "Pokémon " + gameId, "gen", (long) gen);
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

    private CapabilitiesTest() {
    }
}
