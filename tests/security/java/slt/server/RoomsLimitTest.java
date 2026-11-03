package slt.server;

import slt.Http;
import slt.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Room creation stays within bounds (security audit, R-4): {@link Rooms#MAX_CREATIONS} creations in a window, and
 * {@link Rooms#MAX_OPEN_ROOMS} rooms not over per leader; a deleted room frees its place. Runs on a throwaway data
 * directory with made-up accounts.
 */
public final class RoomsLimitTest {

    private static int failed;

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("cableclub-limit-test");
        try {
            run(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     limites de création des salles" : "ÉCHEC  limites de création des salles : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(Path dir) throws IOException {
        Accounts accounts = new Accounts(dir);
        Rooms rooms = new Rooms(dir, accounts, new Hub());
        Accounts.User alice = accounts.register("test_limite_a", "mot-de-passe-de-test", "Alice", true);
        Accounts.User bob = accounts.register("test_limite_b", "mot-de-passe-de-test", "Bob", true);
        Map<String, Object> body = Json.map("game", Json.map("gameId", "emerald", "name", "Pokémon Émeraude", "gen", 3L), "mode", "nuzlocke");

        // the rate: the creation after MAX_CREATIONS waits
        for (int i = 0; i < Rooms.MAX_CREATIONS; i++) {
            rooms.create(alice, body);
        }
        status(() -> rooms.create(alice, body), 429, "création au-delà du débit refusée");
        rooms.create(bob, body); // another player is not slowed down
        check(true, "");

        // the cap: rooms not over, whatever the time (the window is reset to reach it)
        String last = null;
        while (count(rooms, alice) < Rooms.MAX_OPEN_ROOMS) {
            rooms.creations.clear();
            last = Json.str(rooms.create(alice, body), "id");
        }
        rooms.creations.clear();
        status(() -> rooms.create(alice, body), 409, "création au-delà de " + Rooms.MAX_OPEN_ROOMS + " salles en cours refusée");
        rooms.delete(last, alice);
        rooms.creations.clear();
        try {
            rooms.create(alice, body);
        } catch (Http.Fail f) {
            check(false, "une salle supprimée libère sa place : " + f.getMessage());
        }
    }

    private static long count(Rooms rooms, Accounts.User user) {
        return rooms.mine(user).stream().filter(o -> user.id.equals(Json.str(Json.obj(o), "leaderId"))).count();
    }

    private static void status(Runnable action, int expected, String what) {
        try {
            action.run();
            check(false, what + " (acceptée)");
        } catch (Http.Fail f) {
            check(f.status == expected, what + " (" + f.status + " " + f.getMessage() + ")");
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  " + what);
        }
    }
}
