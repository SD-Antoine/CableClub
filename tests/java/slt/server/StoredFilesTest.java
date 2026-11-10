package slt.server;

import slt.Json;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The typed views of what the server stores (point 9 of the follow-up of the audit of 2026-11-10) never change a
 * stored file: rooms in every state written by the server, read and written again, give the same bytes; so do files
 * of another version (a key more, a key less, in the game, the end, a player's ROM and the summary). Throwaway data
 * directories, made-up accounts.
 */
public final class StoredFilesTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); // log lines of the rooms
        Path dir = Files.createTempDirectory("cableclub-stored-test");
        try {
            rooms(dir);
            others(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     fichiers enregistrés inchangés (salles, galerie, signalements, décisions ; autre version)"
                : "ÉCHEC  fichiers enregistrés : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void rooms(Path dir) throws Exception {
        Accounts accounts = new Accounts(dir);
        Rooms rooms = new Rooms(dir, accounts, new Hub());
        Accounts.User a = accounts.register("test_fichier_a", "mot-de-passe-de-test", "Alice", true);
        Accounts.User b = accounts.register("test_fichier_b", "mot-de-passe-de-test", "Bob", true);
        List<String> ids = new ArrayList<>();
        ids.add(Json.str(rooms.create(a, Json.map("mode", "nuzlocke", "maxPlayers", 2L,
                "game", Json.map("gameId", "rougefr", "name", "Pokémon Rouge", "gen", 1L))), "id")); // lobby, no ROM yet
        ids.add(played(rooms, a, b, null));
        ids.add(played(rooms, a, b, "victory"));
        ids.add(played(rooms, b, a, "abandon"));
        ids.add(played(rooms, a, b, "defeat"));
        Path salles = dir.resolve("salles");
        for (String id : ids) {
            String file = Files.readString(salles.resolve(id + ".json"));
            Map<String, Object> m = Json.parseObject(file);
            check(Json.stringify(Room.fromStore(m).toStore()).equals(file), "salle " + Json.str(m, "state") + " relue et réécrite à l'identique");
            // another version: a key more and a key less in each typed part
            Map<String, Object> other = Json.parseObject(file);
            Map<String, Object> game = Json.obj(other.get("game"));
            game.remove("platform");
            game.put("cleVenue", "d'ailleurs");
            List<Object> members = Json.arr(other.get("members"));
            for (Object o : members) {
                Map<String, Object> rom = Json.obj(Json.obj(o).get("rom"));
                rom.remove("sourceCrc");
                rom.put("autreChamp", 7L);
            }
            Map<String, Object> ended = Json.obj(other.get("ended"));
            if (ended != null) {
                ended.remove("reason");
                ended.put("bonus", Json.map("x", 1L));
                for (Object o : Json.arr(ended.get("summary"))) {
                    Json.obj(o).put("titre", "inconnu");
                }
            }
            String text = Json.stringify(other);
            check(Json.stringify(Room.fromStore(Json.parseObject(text)).toStore()).equals(text),
                    "salle " + Json.str(m, "state") + " d'une autre version réécrite à l'identique");
        }
        // what the typed parts read
        Room won = Room.fromStore(Json.parseObject(Files.readString(salles.resolve(ids.get(2) + ".json"))));
        check("rubisfr".equals(won.game.gameId()) && won.game.gen() == 3 && "Pokémon Rubis".equals(won.game.name()), "jeu de la salle lu");
        check(won.ended != null && won.ended.victory() && won.ended.at() > 0 && won.ended.players().size() == 2, "fin de la salle lue");
        check(won.members.get(0).rom.ready() && "fichiercrc".equals(won.members.get(0).rom.crc()), "ROM du joueur lue");
        Room lobby = Room.fromStore(Json.parseObject(Files.readString(salles.resolve(ids.get(0) + ".json"))));
        check(lobby.ended == null && "missing".equals(lobby.members.get(0).rom.status()), "salle en attente : pas de fin, ROM manquante");
        Map<String, Object> bare = new LinkedHashMap<>(Json.parseObject(Files.readString(salles.resolve(ids.get(0) + ".json"))));
        bare.remove("game");
        Room noGame = Room.fromStore(bare);
        check(noGame.game.gameId() == null && noGame.game.gen() == 0 && noGame.game.gen(3) == 3, "salle sans jeu enregistré");
        check(Json.stringify(noGame.toStore().get("game")).equals("{}"), "son jeu s'écrit comme avant ({})");
    }

    /**
     * The gallery, the reports and the moderators' decisions: a file of another version (a key more in an entry) read,
     * changed by one operation, written again: only what the operation changes moves, the unknown key stays.
     */
    private static void others(Path base) throws Exception {
        Path dir = base.resolve("autres");
        Files.createDirectories(dir);
        Accounts accounts = new Accounts(dir);
        Accounts.User a = accounts.register("test_autre_a", "mot-de-passe-de-test", "Alice", true);
        Accounts.User mod = accounts.register("test_autre_m", "mot-de-passe-de-test", "Modo", true);

        // gallery: one more room created with the mode
        ModeSpec spec = ModeSpec.stored(Json.stringify(Json.map("schemaVersion", 1L, "id", "defi-fichier", "name", "Défi fichier",
                "description", "Un mode de test.", "author", "Testeur", "license", "CC-BY-4.0", "players", Json.map("min", 1L, "max", 4L),
                "needs", List.of(), "engine", Json.map("links", "none", "encounters", "firstPerZone", "team", "free"),
                "rules", Json.map("autoEnd", true, "endWhen", "all", "wipeEndsRun", true), "win", List.of("champion"),
                "lose", List.of("teamWiped"), "objectives", List.of())));
        Map<String, Object> entry = Json.map("owner", a.id, "publishedAt", 5L, "uses", 2L, "hidden", false, "mode", spec.toJson(),
                "updatedAt", 6L, "etiquette", "d'une autre version");
        Files.writeString(dir.resolve("modes-publics.json"), Json.stringify(Json.map("version", 1L, "modes", List.of(entry))));
        new ModeGallery(dir, accounts).used(spec);
        Map<String, Object> expected = new LinkedHashMap<>(entry);
        expected.put("uses", 3L);
        check(Files.readString(dir.resolve("modes-publics.json")).equals(Json.stringify(Json.map("version", 1L, "modes", List.of(expected)))),
                "galerie : une utilisation de plus, le reste du fichier inchangé");

        // reports: one marked handled
        Map<String, Object> report = Json.map("id", 4L, "at", 1L, "by", mod.id, "byName", "Modo", "userId", a.id, "userName", "Alice",
                "username", "test_autre_a", "reason", "test", "done", false, "note", "d'une autre version");
        Files.writeString(dir.resolve("signalements.json"), Json.stringify(Json.map("version", 1L, "reports", List.of(report))));
        new Reports(dir).done(4, mod, true);
        Map<String, Object> handled = new LinkedHashMap<>(report);
        handled.put("done", true);
        handled.put("doneBy", "Modo");
        check(Files.readString(dir.resolve("signalements.json")).equals(Json.stringify(Json.map("version", 1L, "reports", List.of(handled)))),
                "signalements : un signalement traité, le reste inchangé");

        // the moderators' decisions: one more
        Map<String, Object> verdict = Json.map("verdict", "cleared", "by", mod.id, "at", 5L, "note", "d'une autre version");
        Files.writeString(dir.resolve("parties-verifiees.json"), Json.stringify(Json.map("version", 1L, "runs", Json.map("salle1/" + a.id, verdict))));
        RunReviews reviews = new RunReviews(dir);
        reviews.decide("salle2", a.id, RunReviews.REMOVED, mod);
        Map<String, Object> runs = Json.obj(Json.parseObject(Files.readString(dir.resolve("parties-verifiees.json"))).get("runs"));
        check(Json.stringify(runs.get("salle1/" + a.id)).equals(Json.stringify(verdict)), "décisions : l'ancienne inchangée");
        check(RunReviews.REMOVED.equals(new RunReviews(dir).verdict("salle2", a.id))
                && RunReviews.CLEARED.equals(reviews.verdict("salle1", a.id)), "décisions : la nouvelle enregistrée, l'ancienne lue");
    }

    /** A room of two players launched, with a few events, ended with {@code outcome} (null: still running). */
    private static String played(Rooms rooms, Accounts.User a, Accounts.User b, String outcome) throws Exception {
        Thread.sleep(2);
        String id = Json.str(rooms.create(a, Json.map("mode", "soullink", "maxPlayers", 2L,
                "game", Json.map("gameId", "rubisfr", "name", "Pokémon Rubis", "gen", 3L, "platform", "gba", "lang", "fr",
                        "crc", "fichiercrc"))), "id");
        rooms.invite(id, a, b.id);
        rooms.join(rooms.get(id), b, false);
        for (Accounts.User u : List.of(a, b)) {
            rooms.rom(id, u, Json.map("status", "ready", "crc", "fichiercrc", "sourceCrc", "source", "gameId", "rubisfr", "lang", "fr"));
            rooms.ready(id, u, true);
        }
        rooms.launch(id, a);
        int uid = 500;
        for (int z = 0; z < 3; z++) {
            for (Accounts.User u : List.of(a, b)) {
                rooms.events(id, u, new ArrayList<>(List.of(
                        Json.map("type", "location", "map", (long) z, "zone", "Route " + z),
                        Json.map("type", "capture", "uid", String.valueOf(uid++), "species", (long) (z + 1), "speciesName",
                                "Espèce " + z, "level", 5L, "nickname", "SURNOM" + z, "zone", "Route " + z, "origin", "wild",
                                "inParty", true, "fainted", false, "shiny", false))));
            }
        }
        if (outcome != null) {
            rooms.end(id, a, outcome);
        }
        return id;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
