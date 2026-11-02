package slt.server;

import slt.Json;

import java.util.List;
import java.util.Map;

/**
 * Checks {@link EventSchema} (point 1b) : valid events pass, malformed ones are flagged. Complements the golden
 * harness, which only exercises the valid side (every scenario event is schema-clean).
 */
public final class EventSchemaTest {

    public static void main(String[] args) {
        int failed = 0;
        failed += valid(Json.map("type", "capture", "uid", "1", "species", 1L, "level", 5L, "origin", "wild"));
        failed += valid(Json.map("type", "encounter", "phase", "start", "uid", "1", "zone", "Route 1",
                "species", 16L, "level", 3L));
        failed += valid(Json.map("type", "capture", "uid", "1", "species", 1L, "level", 5L, "origin", "new",
                "speciesName", null, "nickname", null)); // optional fields may be null
        failed += valid(Json.map("type", "whiteout"));
        failed += valid(Json.map("type", "caps", "gyms", List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L), "league", 50L));
        failed += invalid(Json.map("type", "capture", "species", 1L, "level", 5L, "origin", "wild"), "uid manquant");
        failed += invalid(Json.map("type", "capture", "uid", "1", "species", "oups", "level", 5L, "origin", "wild"),
                "species mauvais type");
        failed += invalid(Json.map("type", "encounter", "uid", "1", "zone", "z", "species", 1L, "level", 2L),
                "phase manquante");
        failed += invalid(Json.map("type", "capturee", "uid", "1"), "type inconnu");
        failed += invalid(Json.map("type", "caps", "gyms", List.of(1L, 2L, 3L)), "gyms != 8");
        failed += invalid(Json.map("foo", "bar"), "type manquant");
        System.out.println(failed == 0 ? "ok     EventSchema (validation des événements)"
                : "ÉCHEC  EventSchema : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static int valid(Map<String, Object> ev) {
        List<String> problems = EventSchema.validate(ev);
        if (problems.isEmpty()) {
            return 0;
        }
        System.out.println("  devait être valide : " + problems);
        return 1;
    }

    private static int invalid(Map<String, Object> ev, String why) {
        if (!EventSchema.validate(ev).isEmpty()) {
            return 0;
        }
        System.out.println("  devait être refusé (" + why + ")");
        return 1;
    }

    private EventSchemaTest() {
    }
}
