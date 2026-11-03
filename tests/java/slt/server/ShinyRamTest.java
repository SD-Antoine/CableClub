package slt.server;

import slt.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Where the shiny rate of a DS room is applied (point 8b). A randomization saved now has it in RAM ("shinyRam", told to
 * the games in the directives); a room saved before keeps its patched ROM, and its directives do not change: its players
 * must be able to generate the same ROM again. Runs on a throwaway data directory with made-up accounts.
 */
public final class ShinyRamTest {

    private static int failed;

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("cableclub-shinyram-test");
        try {
            run(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     ShinyRam (taux de chromatiques des salles)" : "ÉCHEC  ShinyRam : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(Path dir) throws IOException {
        Accounts accounts = new Accounts(dir);
        Rooms rooms = new Rooms(dir, accounts, new Hub());
        Accounts.User alice = accounts.register("test_shiny_a", "mot-de-passe-de-test", "Alice", true);
        Map<String, Object> game = Json.map("gameId", "platinum", "name", "Pokémon Platine", "gen", 4L);
        Map<String, Object> view = rooms.create(alice, Json.map("game", game, "maxPlayers", 2L,
                "rando", Json.map("enabled", false, "shinyNum", 64L)));
        Room room = rooms.byCode(Json.str(view, "code"));
        Room.Member me = room.members.get(0);
        check(Json.bool(room.rando, "shinyRam", false), "nouvelle salle : le taux est en RAM");
        Map<String, Object> d = room.directives(me);
        check(Boolean.TRUE.equals(d.get("shinyRam")) && Json.num(d, "shinyNum", -1) == 64, "les directives le disent aux jeux");

        // a room saved before: nothing in its randomization, nothing in its directives
        room.rando.remove("shinyRam");
        d = room.directives(me);
        check(!d.containsKey("shinyRam") && Json.num(d, "shinyNum", -1) == 64, "salle ancienne : directives inchangées");
        Room again = Room.fromStore(Json.parseObject(Json.stringify(room.toStore())));
        check(!Json.bool(again.rando, "shinyRam", false), "salle ancienne : relue sans la marque");

        // its randomization changed before the launch: every ROM is generated again, the rate moves to the RAM
        rooms.settings(room.id, alice, Json.map("rando", Json.map("enabled", false, "shinyNum", 8L)));
        check(Json.bool(room.rando, "shinyRam", false), "randomisation modifiée : le taux passe en RAM");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  " + what);
        }
    }
}
