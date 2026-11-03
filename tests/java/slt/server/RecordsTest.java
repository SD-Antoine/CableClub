package slt.server;

import slt.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Statistics and leaderboards (point 3): what a finished room gives (one run per player, won or not), the totals of a
 * player by family of modes, and a leaderboard that shows only the players who asked for it, filtered by mode, game
 * and size of room, abandoned runs left out. Made-up accounts in a throwaway directory.
 */
public final class RecordsTest {

    private static int failed;

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("cableclub-records-test");
        try {
            run(dir);
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     Records (statistiques, classements)" : "ÉCHEC  Records : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** A room of this mode that ended; {@code players}: userId, status, captures, deaths, badges, alive. */
    private static Room ended(String mode, String gameId, String outcome, Object[]... players) {
        Room r = new Room();
        r.mode = mode;
        r.rules = r.spec().defaults();
        r.game = Json.map("gameId", gameId, "gen", 3L);
        List<Object> summary = new ArrayList<>();
        for (Object[] p : players) {
            summary.add(Json.map("userId", p[0], "status", p[1], "captures", (long) (int) p[2], "deaths", (long) (int) p[3],
                    "badges", (long) (int) p[4], "alive", (long) (int) p[5]));
        }
        r.ended = Json.map("at", 1L, "outcome", outcome, "summary", summary);
        return r;
    }

    private static void run(Path dir) throws IOException {
        Accounts accounts = new Accounts(dir);
        RunReviews reviews = new RunReviews(dir);
        Accounts.User a = accounts.register("test_records_a", "mot-de-passe-de-test", "Alice", true);
        Accounts.User b = accounts.register("test_records_b", "mot-de-passe-de-test", "Bob", true);
        Accounts.User c = accounts.register("test_records_c", "mot-de-passe-de-test", "Chloé", true);

        List<Records.Run> runs = new ArrayList<>();
        // a solo Nuzlocke won by Alice, one lost by Bob, a Nuzlocke for two in another game
        runs.addAll(Records.of(ended("nuzlocke", "emerald", "victory", new Object[]{a.id, "champion", 20, 4, 8, 12})));
        runs.addAll(Records.of(ended("nuzlocke", "emerald", "defeat", new Object[]{b.id, "wiped", 10, 9, 3, 0})));
        runs.addAll(Records.of(ended("nuzlocke", "platinum", "victory",
                new Object[]{b.id, "champion", 30, 2, 8, 20}, new Object[]{c.id, "playing", 15, 6, 6, 4})));
        // a Soul Link won together (statuses not all « champion »), and an abandoned one
        runs.addAll(Records.of(ended("soullink", "emerald", "victory",
                new Object[]{a.id, "champion", 12, 3, 8, 6}, new Object[]{c.id, "playing", 12, 3, 8, 6})));
        runs.addAll(Records.of(ended("soullink", "emerald", "abandon", new Object[]{a.id, "playing", 2, 0, 1, 2},
                new Object[]{b.id, "playing", 2, 0, 1, 2})));
        Room running = ended("nuzlocke", "emerald", "victory", new Object[]{a.id, "champion", 1, 0, 1, 1});
        running.ended = null;
        check(Records.of(running).isEmpty(), "une salle pas terminée ne compte pas");
        check(runs.size() == 8, "une partie par joueur des salles terminées");
        check(runs.stream().filter(x -> x.mode().equals("soullink") && "victory".equals(x.outcome())).allMatch(Records.Run::won),
                "Soul Link gagné ensemble : gagné pour chacun");

        // statistics of a player
        Map<String, Object> sa = Records.stats(runs, a.id);
        Map<String, Object> nuz = Json.obj(Json.obj(sa.get("families")).get("nuzlocke"));
        Map<String, Object> sl = Json.obj(Json.obj(sa.get("families")).get("soullink"));
        check(Json.num(nuz, "runs", 0) == 1 && Json.num(nuz, "wins", 0) == 1 && Json.num(nuz, "survival", 0) == 60,
                "Alice en Nuzlocke : 1 partie, 1 victoire, 60 % de survie");
        check(Json.num(sl, "runs", 0) == 1 && Json.num(sl, "abandons", 0) == 1, "Alice en Soul Link : 1 partie, 1 abandon à part");
        check(Json.num(Json.obj(sa.get("all")), "captures", 0) == 32, "Alice, tous modes : 32 captures (abandon exclu)");

        // leaderboards: nobody without asking
        check(Records.leaderboard(runs, accounts, reviews, "nuzlocke", null, 0, a.id).isEmpty(), "personne n'apparaît sans l'avoir demandé");
        a.ranked = true;
        b.ranked = true;
        List<Object> nuzBoard = Records.leaderboard(runs, accounts, reviews, "nuzlocke", null, 0, a.id);
        check(nuzBoard.size() == 2, "seuls ceux qui l'ont demandé (Chloé non)");
        // Alice: 1 win, 4 deaths in 1 run; Bob: 1 win, 11 deaths in 2 runs (5.5 a run): Alice first
        Map<String, Object> first = Json.obj(nuzBoard.get(0));
        check(a.id.equals(Json.str(Json.obj(first.get("user")), "id")) && Json.num(first, "rank", 0) == 1,
                "à victoires égales, moins de morts par partie d'abord (Alice)");
        check(Boolean.TRUE.equals(Json.obj(nuzBoard.get(0)).get("me")), "le joueur se reconnaît");
        check(Records.leaderboard(runs, accounts, reviews, "nuzlocke", "platinum", 0, a.id).size() == 1, "filtre par jeu");
        check(Records.leaderboard(runs, accounts, reviews, "nuzlocke", null, 1, a.id).size() == 2
                && Records.leaderboard(runs, accounts, reviews, "nuzlocke", null, 2, a.id).size() == 1, "filtre par nombre de joueurs");
        check(Records.leaderboard(runs, accounts, reviews, "soullink", null, 0, a.id).size() == 1, "un abandon ne classe personne (Bob)");
        check(Records.leaderboard(runs, accounts, reviews, "sololocke", null, 0, a.id).isEmpty(), "un mode sans partie : vide");
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  " + what);
        }
    }
}
