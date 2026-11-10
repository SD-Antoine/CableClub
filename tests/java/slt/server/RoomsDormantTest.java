package slt.server;

import slt.Http;
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
 * The finished rooms asleep (point 6b of the follow-up of the audit of 2026-11-10): after a restart they are kept as
 * a summary, and the server answers exactly as before (results, history, statistics, room, journal, code); a room
 * asked for wakes up and goes back to sleep after {@link Rooms#SLEEP_AFTER_MS}; deleting an account or a room works
 * on a room asleep as on a room awake. Throwaway data directories, made-up accounts.
 */
public final class RoomsDormantTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); // log lines of the rooms
        Path dir = Files.createTempDirectory("cableclub-dormant-test");
        Path copy = Files.createTempDirectory("cableclub-dormant-copy");
        Path copy2 = Files.createTempDirectory("cableclub-dormant-copy2");
        try {
            run(dir, copy, copy2);
        } finally {
            for (Path d : List.of(dir, copy, copy2)) {
                try (Stream<Path> files = Files.walk(d)) {
                    files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
                }
            }
        }
        System.out.println(failed == 0 ? "ok     parties terminées en résumé (mêmes réponses après un redémarrage)"
                : "ÉCHEC  parties terminées en résumé : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(Path dir, Path copy, Path copy2) throws Exception {
        Accounts accounts = new Accounts(dir);
        Rooms rooms = new Rooms(dir, accounts, new Hub());
        Accounts.User a = accounts.register("test_dort_a", "mot-de-passe-de-test", "Alice", true);
        Accounts.User b = accounts.register("test_dort_b", "mot-de-passe-de-test", "Bob", true);
        Accounts.User c = accounts.register("test_dort_c", "mot-de-passe-de-test", "Chloé", true);
        String won = played(rooms, a, b, "victory");
        String stopped = played(rooms, c, null, "abandon");
        String lost = played(rooms, a, c, "defeat");
        String running = played(rooms, b, c, null);
        String lobby = Json.str(rooms.create(b, Json.map("mode", "nuzlocke", "maxPlayers", 2L,
                "game", Json.map("gameId", "rougefr", "name", "Pokémon Rouge", "gen", 1L))), "id");
        String code = rooms.get(won).code;
        List<Accounts.User> users = List.of(a, b, c);
        String before = snapshot(rooms, users, won, a);
        copyTree(dir, copy);
        copyTree(dir, copy2);

        // restart: the finished rooms are asleep, nothing else changes
        Accounts acc2 = new Accounts(dir);
        Rooms r2 = new Rooms(dir, acc2, new Hub());
        List<Accounts.User> users2 = List.of(acc2.get(a.id), acc2.get(b.id), acc2.get(c.id));
        check(r2.isAsleep(won) && r2.isAsleep(stopped) && r2.isAsleep(lost), "parties terminées en résumé");
        check(!r2.isAsleep(running) && !r2.isAsleep(lobby), "salles en cours et en attente chargées");
        String quiet = snapshotWithoutRoom(r2, users2);
        check(r2.isAsleep(won), "résultats, historique et statistiques ne réveillent rien");
        check(quiet.equals(snapshotWithoutRoom(rooms, users)), "résultats, historique, statistiques : identiques");
        String after = snapshot(r2, users2, won, users2.get(0));
        check(before.equals(after), "mêmes réponses après le redémarrage");
        if (!before.equals(after)) {
            int i = 0;
            while (i < Math.min(before.length(), after.length()) && before.charAt(i) == after.charAt(i)) {
                i++;
            }
            int from = Math.max(0, i - 150);
            System.out.println("    avant : …" + before.substring(from, Math.min(before.length(), i + 150)));
            System.out.println("    après : …" + after.substring(from, Math.min(after.length(), i + 150)));
        }
        check(!r2.isAsleep(won), "une partie demandée se réveille");

        // back to sleep after its time, with the same answers
        long now = System.currentTimeMillis();
        r2.sweep(now);
        check(!r2.isAsleep(won), "pas tout de suite");
        r2.sweep(now + Rooms.SLEEP_AFTER_MS + 1);
        check(r2.isAsleep(won), "rendormie après " + Rooms.SLEEP_AFTER_MS / 60_000 + " min sans usage");
        check(!r2.isAsleep(running), "une salle en cours ne dort jamais");
        check(quiet.equals(snapshotWithoutRoom(r2, users2)), "mêmes réponses une fois rendormie");

        // a code still says the adventure is launched
        try {
            r2.join(r2.byCode(code), users2.get(2), true);
            check(false, "code d'une partie terminée : refusé");
        } catch (Http.Fail f) {
            check(f.status == 409, "code d'une partie terminée : 409 (" + f.status + ")");
        }

        // deleting an account: the same with the rooms asleep as with the rooms awake
        Accounts accAwake = new Accounts(copy);
        Rooms awake = new Rooms(copy, accAwake, new Hub());
        for (String id : List.of(won, stopped, lost)) {
            awake.get(id);
        }
        awake.accountDeleted(accAwake.get(b.id));
        Accounts accAsleep = new Accounts(copy2);
        Rooms sleeping = new Rooms(copy2, accAsleep, new Hub());
        sleeping.accountDeleted(accAsleep.get(b.id));
        check(snapshotWithoutRoom(awake, List.of(accAwake.get(a.id), accAwake.get(c.id)))
                        .equals(snapshotWithoutRoom(sleeping, List.of(accAsleep.get(a.id), accAsleep.get(c.id)))),
                "compte supprimé : même résultat, partie endormie ou non");
        check(Files.readString(copy2.resolve("salles").resolve(won + ".json")).equals(
                Files.readString(copy.resolve("salles").resolve(won + ".json"))), "compte supprimé : même fichier de salle");

        // deleting a room asleep
        r2.delete(stopped, users2.get(2));
        check(!r2.isAsleep(stopped) && r2.find(stopped) == null, "partie endormie supprimée");
        check(!Files.exists(dir.resolve("salles").resolve(stopped + ".json")), "son fichier aussi");
        check(r2.runs().stream().noneMatch(x -> x.roomId().equals(stopped)), "et ses résultats");
    }

    /** A room played to its end ({@code outcome} null: still running); b null for a solo run. */
    private static String played(Rooms rooms, Accounts.User a, Accounts.User b, String outcome) throws Exception {
        Thread.sleep(3); // distinct launch times: the history is ordered by them
        boolean duo = b != null;
        String id = Json.str(rooms.create(a, Json.map("mode", duo ? "soullink" : "nuzlocke", "maxPlayers", duo ? 2L : 1L,
                "game", Json.map("gameId", "rubisfr", "name", "Pokémon Rubis", "gen", 3L, "crc", "dortcrc"))), "id");
        List<Accounts.User> players = new ArrayList<>(List.of(a));
        if (duo) {
            rooms.invite(id, a, b.id);
            rooms.join(rooms.get(id), b, false);
            players.add(b);
        }
        for (Accounts.User u : players) {
            rooms.rom(id, u, Json.map("status", "ready", "crc", "dortcrc", "gameId", "rubisfr", "lang", "fr"));
            rooms.ready(id, u, true);
        }
        rooms.launch(id, a);
        int uid = 100;
        for (int z = 0; z < 6; z++) {
            for (Accounts.User u : players) {
                rooms.events(id, u, new ArrayList<>(List.of(
                        Json.map("type", "location", "map", (long) z, "zone", "Route " + z),
                        Json.map("type", "capture", "uid", String.valueOf(uid++), "species", (long) (z + 1), "speciesName",
                                "Espèce " + z, "level", 5L, "nickname", "SURNOM" + z, "zone", "Route " + z, "origin", "wild",
                                "inParty", true, "fainted", false, "shiny", false),
                        Json.map("type", "badge", "count", (long) Math.min(8, z + 1)))));
            }
        }
        if (outcome != null) {
            rooms.end(id, a, outcome);
        }
        return id;
    }

    /** What the server answers without asking for one room: results, history, statistics, counts of the rooms. */
    private static String snapshotWithoutRoom(Rooms rooms, List<Accounts.User> users) {
        List<String> runs = new ArrayList<>();
        for (Records.Run r : rooms.runs()) {
            runs.add(r.toString());
        }
        runs.sort(null);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("runs", runs);
        for (Accounts.User u : users) {
            m.put("history " + u.username, rooms.history(u));
            m.put("stats " + u.username, rooms.stats(u.id));
        }
        Map<String, Object> counts = new LinkedHashMap<>(rooms.metrics(new Hub()));
        counts.remove("asleep");
        m.put("rooms", counts);
        return Json.stringify(m);
    }

    /**
     * The same, then one room and its journal (which wakes it). Without "live", where a player stands right now:
     * never written, it is empty after any restart of the server, asleep or not.
     */
    private static String snapshot(Rooms rooms, List<Accounts.User> users, String id, Accounts.User viewer) {
        return snapshotWithoutRoom(rooms, users) + Json.stringify(withoutLive(rooms.view(id, viewer)))
                + Json.stringify(rooms.logs(id, viewer, 0, 2000));
    }

    @SuppressWarnings("unchecked")
    private static Object withoutLive(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!"live".equals(e.getKey())) {
                    out.put(String.valueOf(e.getKey()), withoutLive(e.getValue()));
                }
            }
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            for (Object x : l) {
                out.add(withoutLive(x));
            }
            return out;
        }
        return o;
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path p : files.toList()) {
                Path t = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(t);
                } else {
                    Files.copy(p, t, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
