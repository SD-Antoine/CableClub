package slt.server;

import slt.Http;
import slt.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Live screens on demand (point 4 of the follow-up of the audit of 2026-11-10): the server tells a player whether
 * another member of the room looked at their image in the last {@link Rooms#WATCH_MS}. Their own look, or the
 * refused one of someone outside the room, does not count. Throwaway data directory, made-up accounts.
 */
public final class LiveScreensTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); // log lines of the rooms
        Path dir = Files.createTempDirectory("cableclub-screens-test");
        try {
            run(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     écrans en direct à la demande" : "ÉCHEC  écrans en direct à la demande : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(Path dir) throws Exception {
        Accounts accounts = new Accounts(dir);
        Rooms rooms = new Rooms(dir, accounts, new Hub());
        Accounts.User alice = accounts.register("test_ecran_a", "mot-de-passe-de-test", "Alice", true);
        Accounts.User bob = accounts.register("test_ecran_b", "mot-de-passe-de-test", "Bob", true);
        Accounts.User carol = accounts.register("test_ecran_c", "mot-de-passe-de-test", "Carol", true);
        Map<String, Object> body = Json.map("mode", "soullink", "maxPlayers", 2L,
                "game", Json.map("gameId", "rubisfr", "name", "Pokémon Rubis", "gen", 3L));
        String id = Json.str(rooms.create(alice, body), "id");
        rooms.invite(id, alice, bob.id);
        rooms.join(rooms.get(id), bob, false);
        byte[] image = {1, 2, 3};
        String key = id + "/" + alice.id;

        check(!rooms.putScreen(id, alice, image), "personne ne regarde au départ");
        check(rooms.screen(id, bob, alice.id, 0) != null, "l'image est lue par le partenaire");
        check(rooms.putScreen(id, alice, image), "regardée juste après");
        check(rooms.screen(id, bob, alice.id, 99) == null && rooms.putScreen(id, alice, image),
                "regardée aussi quand il n'y a pas d'image neuve");

        rooms.watched.put(key, System.currentTimeMillis() - Rooms.WATCH_MS - 1);
        check(!rooms.putScreen(id, alice, image), "plus regardée après " + Rooms.WATCH_MS + " ms sans demande");
        rooms.screen(id, alice, alice.id, 0);
        check(!rooms.putScreen(id, alice, image), "son propre regard ne compte pas");
        try {
            rooms.screen(id, carol, alice.id, 0);
            check(false, "hors de la salle : refusé");
        } catch (Http.Fail f) {
            check(f.status == 403, "hors de la salle : 403 (" + f.status + ")");
        }
        check(!rooms.putScreen(id, alice, image), "le regard refusé d'un autre joueur ne compte pas");
        rooms.screen(id, bob, "inconnu", 0);
        check(!rooms.watched.containsKey(id + "/inconnu"), "pas de trace pour un joueur hors de la salle");

        rooms.screen(id, bob, alice.id, 0);
        rooms.delete(id, alice);
        check(rooms.watched.keySet().stream().noneMatch(k -> k.startsWith(id + "/")), "oublié avec la salle");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
