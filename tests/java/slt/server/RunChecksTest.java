package slt.server;

import slt.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The checks of the runs (point 3e), with a made-up check (the real ones stay out of the public repository): a check
 * sees each event and may flag the run, keeps its memory with the room, never breaks the room when it fails; a
 * flagged run enters no leaderboard until a moderator clears it, and the player sees it waiting; a removed run
 * leaves the leaderboards. Without checks, nothing changes in what a room stores (the golden references stay).
 */
public final class RunChecksTest {

    private static int failed;

    public static void main(String[] args) throws IOException {
        Path dir = Files.createTempDirectory("cableclub-checks-test");
        try {
            run(dir);
        } finally {
            Room.checks = List.of();
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     contrôles des parties (mécanique)" : "ÉCHEC  contrôles des parties : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Made up for the test: a capture above level 50 is flagged; every capture is counted in the check's memory. */
    private static final RunCheck HIGH_LEVEL = (run, ev) -> {
        if (!"capture".equals(Json.str(ev, "type"))) {
            return List.of();
        }
        run.state().put("test.captures", Json.num(run.state(), "test.captures", 0) + 1);
        return Json.num(ev, "level", 0) > 50 ? List.of(new RunCheck.Flag("test.level", "capture au niveau " + Json.num(ev, "level", 0))) : List.of();
    };
    private static final RunCheck BROKEN = (run, ev) -> {
        throw new IllegalStateException("contrôle défaillant");
    };

    private static Map<String, Object> capture(String uid, int level) {
        return Json.map("type", "capture", "uid", uid, "species", 16L, "speciesName", "Roucool", "level", (long) level,
                "nickname", "ROUCOOL", "zone", "Route 1", "origin", "new", "inParty", true, "fainted", false, "shiny", false);
    }

    private static void run(Path dir) throws IOException {
        Accounts accounts = new Accounts(dir);
        Accounts.User alice = accounts.register("test_controle_a", "mot-de-passe-de-test", "Alice", true);
        Accounts.User modo = accounts.register("test_controle_m", "mot-de-passe-de-test", "Modo", true);
        alice.ranked = true;
        RunReviews reviews = new RunReviews(dir);

        // without checks, a room stores no flag
        Room plain = room(alice);
        plain.event(plain.members.get(0), capture("2001", 60), new Room.Out());
        check(!Json.stringify(plain.toStore()).contains("\"flags\""), "sans contrôle, rien de plus dans la sauvegarde de la salle");

        Room.checks = List.of(BROKEN, HIGH_LEVEL);
        Room r = room(alice);
        Room.Member m = r.members.get(0);
        r.event(m, capture("1001", 5), new Room.Out());
        r.event(m, capture("1002", 60), new Room.Out());
        check(m.mons.containsKey("1002"), "un contrôle défaillant ne casse pas la salle");
        check(m.flags.size() == 1 && "test.level".equals(Json.str(m.flags.get(0), "code")), "la capture au niveau 60 est signalée");
        check(Json.num(m.checkState, "test.captures", 0) == 2, "le contrôle garde sa mémoire de la partie");
        Room again = Room.fromStore(Json.parseObject(Json.stringify(r.toStore())));
        check(again.members.get(0).flags.size() == 1 && Json.num(again.members.get(0).checkState, "test.captures", 0) == 2,
                "signalements et mémoire relus avec la salle");
        check(!Json.stringify(r.view(new Hub(), true)).contains("test.level"), "les joueurs ne voient pas les signalements");

        // the run ends: it waits for a moderator
        r.state = "finished";
        r.ended = Json.map("at", 1L, "outcome", "victory", "reason", "test", "summary", r.summary());
        List<Records.Run> runs = Records.of(r);
        check(Records.leaderboard(runs, accounts, reviews, "nuzlocke", null, 0, alice.id).isEmpty(), "partie signalée : pas au classement");
        check(Records.pending(runs, reviews, "nuzlocke", null, 0, alice.id) == 1, "le joueur la voit en cours de vérification");
        check(Records.forModeration(runs, accounts, reviews, null, null).size() == 1, "les modérateurs la voient");
        reviews.decide(r.id, alice.id, RunReviews.CLEARED, modo);
        check(Records.leaderboard(runs, accounts, reviews, "nuzlocke", null, 0, alice.id).size() == 1
                && Records.pending(runs, reviews, "nuzlocke", null, 0, alice.id) == 0, "vérifiée par un modérateur : au classement");
        reviews.decide(r.id, alice.id, RunReviews.REMOVED, modo);
        check(Records.leaderboard(runs, accounts, reviews, "nuzlocke", null, 0, alice.id).isEmpty(), "retirée : plus au classement");
        check(RunReviews.REMOVED.equals(new RunReviews(dir).verdict(r.id, alice.id)), "décision relue après un redémarrage");

        // a run nobody flagged counts, unless removed
        Room clean = room(alice);
        clean.state = "finished";
        clean.ended = Json.map("at", 2L, "outcome", "victory", "reason", "test", "summary", clean.summary());
        List<Records.Run> cleanRuns = Records.of(clean);
        check(Records.leaderboard(cleanRuns, accounts, reviews, "nuzlocke", null, 0, alice.id).size() == 1, "partie sans signalement : au classement");
    }

    private static int rooms;

    private static Room room(Accounts.User user) {
        Room r = new Room();
        r.id = "salle-test-" + (++rooms);
        r.name = "Salle de test";
        r.mode = "nuzlocke";
        r.rules = r.spec().defaults();
        r.game = Json.map("gameId", "red", "name", "Pokémon Rouge", "gen", 1L);
        Room.Member m = new Room.Member();
        m.userId = user.id;
        m.name = user.display;
        m.slot = 1;
        m.hasBalls = true;
        r.members.add(m);
        r.leaderId = user.id;
        r.launch(new Room.Out());
        return r;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  " + what);
        }
    }
}
