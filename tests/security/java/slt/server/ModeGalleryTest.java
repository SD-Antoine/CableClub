package slt.server;

import slt.Http;
import slt.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The gallery of public modes (point 2d): who may publish what, what strangers see, what the moderation takes out,
 * and which rooms of an imported mode may be public. Runs on a throwaway data directory with made-up accounts.
 */
public final class ModeGalleryTest {

    private static int failed;

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("cableclub-gallery-test");
        try {
            run(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     galerie des modes publics" : "ÉCHEC  galerie des modes publics : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static String mode(String id, String name) {
        return Json.stringify(Json.map("schemaVersion", 1L, "id", id, "name", name, "description", "Un mode de test.",
                "author", "Testeur", "license", "CC-BY-4.0", "players", Json.map("min", 1L, "max", 4L), "needs", List.of(),
                "engine", Json.map("links", "none", "encounters", "firstPerZone", "team", "free"),
                "rules", Json.map("autoEnd", true, "endWhen", "all", "wipeEndsRun", true),
                "win", List.of("champion"), "lose", List.of("teamWiped"), "objectives", List.of()));
    }

    private static void run(Path dir) throws IOException {
        Accounts accounts = new Accounts(dir);
        Rooms rooms = new Rooms(dir, accounts, new Hub());
        ModeGallery gallery = new ModeGallery(dir, accounts);
        rooms.gallery = gallery;
        Room.publishedMode = gallery::published;
        Accounts.User alice = accounts.register("test_galerie_a", "mot-de-passe-de-test", "Alice", true);
        Accounts.User bob = accounts.register("test_galerie_b", "mot-de-passe-de-test", "Bob", true);
        Accounts.User mod = accounts.register("test_galerie_m", "mot-de-passe-de-test", "Modo", true);

        // publish, then what everyone sees
        gallery.publish(alice, mode("defi-alice", "Défi d’Alice"));
        List<Object> seen = gallery.list(bob, null, false);
        Map<String, Object> first = seen.isEmpty() ? Map.of() : Json.obj(seen.get(0));
        check(seen.size() == 1 && "Alice".equals(first.get("publisher")) && Boolean.FALSE.equals(first.get("mine")),
                "un mode publié est vu par tous, avec le compte qui l'a publié");
        check(gallery.list(bob, "alice défi", false).size() == 1 && gallery.list(bob, "inconnu", false).isEmpty(), "recherche par mots");
        status(() -> gallery.publish(bob, mode("defi-alice", "Autre")), 409, "l'identifiant d'un autre joueur est refusé");
        status(() -> gallery.publish(bob, mode("nuzlocke", "Copie")), 400, "l'identifiant d'un mode officiel est refusé");
        status(() -> gallery.publish(bob, mode("defi-bob", "Soul Link")), 400, "le nom d'un mode officiel est refusé");
        gallery.publish(alice, mode("defi-alice", "Défi d’Alice, version 2"));
        check(Json.str(Json.obj(Json.obj(gallery.list(bob, null, false).get(0)).get("mode")), "name").endsWith("version 2"),
                "le propriétaire met son mode à jour");

        // bounds: the rate, then the number of modes per account
        for (int i = 0; i < ModeGallery.MAX_PUBLICATIONS; i++) {
            gallery.publish(bob, mode("rafale-" + i, "Rafale " + i));
        }
        status(() -> gallery.publish(bob, mode("rafale-x", "Rafale x")), 429, "publication au-delà du débit refusée");
        for (int i = 0; gallery.list(bob, null, true).size() < ModeGallery.MAX_PER_ACCOUNT; i++) {
            gallery.publications.clear();
            gallery.publish(bob, mode("lot-" + i, "Lot " + i));
        }
        gallery.publications.clear();
        status(() -> gallery.publish(bob, mode("lot-trop", "Lot de trop")), 409, ModeGallery.MAX_PER_ACCOUNT + " modes publiés au plus");
        status(() -> gallery.unpublish(alice, "lot-0"), 404, "on ne retire que ses propres modes");
        gallery.unpublish(bob, "lot-0");
        check(gallery.list(bob, null, true).size() == ModeGallery.MAX_PER_ACCOUNT - 1, "retirer son mode libère une place");

        // rooms: a published mode may give a public room, a modified copy may not
        ModeSpec published = Rooms.importMode(mode("defi-alice", "Défi d’Alice, version 2"));
        ModeSpec modified = Rooms.importMode(mode("defi-alice", "Défi d’Alice modifié"));
        Map<String, Object> game = Json.map("gameId", "emerald", "name", "Pokémon Émeraude", "gen", 3L);
        Map<String, Object> pub = rooms.create(bob, Json.map("game", game, "modeFile", published.toJson(), "maxPlayers", 2L, "visibility", "public"));
        Map<String, Object> copy = rooms.create(bob, Json.map("game", game, "modeFile", modified.toJson(), "maxPlayers", 2L, "visibility", "public"));
        check("public".equals(pub.get("visibility")) && Boolean.TRUE.equals(pub.get("modePublic")), "salle d'un mode publié : publique");
        check("private".equals(copy.get("visibility")) && Boolean.FALSE.equals(copy.get("modePublic")), "salle d'une copie modifiée : privée");
        status(() -> rooms.settings(Json.str(copy, "id"), bob, Json.map("visibility", "public")), 409, "copie modifiée : « rendre publique » refusé");
        check(listed(rooms, mod, pub), "la salle du mode publié est dans les salles publiques");
        check(Json.num(Json.obj(gallery.list(bob, "version 2", false).get(0)), "uses", 0) == 1, "la galerie compte les salles créées");

        // reports and moderation
        check(gallery.forReport("defi-alice") != null && alice.id.equals(gallery.forReport("defi-alice").get("owner")),
                "un signalement vise le compte qui a publié");
        gallery.hide(mod, "defi-alice");
        check(gallery.list(bob, null, false).stream().noneMatch(o -> "defi-alice".equals(Json.str(Json.obj(o), "id"))),
                "un mode retiré par la modération disparaît de la galerie");
        check(gallery.list(alice, null, true).stream().anyMatch(o -> Boolean.TRUE.equals(Json.obj(o).get("hidden"))),
                "son auteur le voit, marqué retiré");
        check(!listed(rooms, mod, pub), "ses salles ne sont plus dans les salles publiques");
        status(() -> gallery.publish(alice, mode("defi-alice", "Défi d’Alice, version 3")), 409, "un mode retiré ne se republie pas");

        // checks of a mode file (the editor): within a minute's bound, another player not slowed down
        long now = System.currentTimeMillis();
        for (int i = 0; i < ModeGallery.MAX_CHECKS; i++) {
            gallery.limitCheck(alice.id, now);
        }
        status(() -> gallery.limitCheck(alice.id, now), 429, "vérification au-delà du débit refusée");
        gallery.limitCheck(bob.id, now);
        gallery.limitCheck(alice.id, now + ModeGallery.CHECK_WINDOW_MS + 1); // a minute later: allowed again

        // a restart keeps the gallery; a deleted account takes its modes away
        ModeGallery again = new ModeGallery(dir, accounts);
        check(again.list(bob, null, true).size() == ModeGallery.MAX_PER_ACCOUNT - 1 && !again.published(published),
                "relue après un redémarrage, retraits compris");
        again.forget(bob.id);
        check(again.list(bob, null, true).isEmpty(), "un compte supprimé emporte ses modes");
    }

    private static boolean listed(Rooms rooms, Accounts.User user, Map<String, Object> room) {
        return rooms.publicRooms(user, null, null, 0, true).stream().anyMatch(o -> room.get("id").equals(Json.obj(o).get("id")));
    }

    private static void status(Runnable action, int expected, String what) {
        try {
            action.run();
            check(false, what + " (accepté)");
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
