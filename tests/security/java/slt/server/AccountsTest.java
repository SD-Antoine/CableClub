package slt.server;

import slt.Http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/**
 * Accounts under concurrency and abuse (point 3 of the follow-up of the audit of 2026-11-10): the requests with a
 * session do not wait for a password being hashed, one name gives one account however many ask at once, the
 * registrations are limited per address (an IPv6 /64 counts as one), and the current password cannot be guessed
 * without end from a session. Runs on a throwaway data directory with made-up accounts.
 */
public final class AccountsTest {

    private static final String PASSWORD = "mot-de-passe-de-test";
    private static int failed;

    public static void main(String[] args) throws Exception {
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); // one log line per account
        Path dir = Files.createTempDirectory("cableclub-accounts-test");
        try {
            Accounts accounts = new Accounts(dir);
            lockFree(accounts);
            sameName(accounts);
            perAddress(accounts);
            password(accounts, dir);
            check(new Accounts(dir).count() == accounts.count(), "comptes relus à l'identique");
            persistence();
            unreadable();
        } finally {
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     comptes (verrou, inscriptions simultanées, limite par adresse, mot de passe, écriture)"
                : "ÉCHEC  comptes : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** A session lookup during registrations takes far less than one hashing. */
    private static void lockFree(Accounts accounts) throws Exception {
        Accounts.User a = accounts.register("test_verrou", PASSWORD, "Verrou", true);
        String token = accounts.openSession(a);
        int n = 4;
        long start = System.nanoTime();
        Thread t = new Thread(() -> {
            for (int i = 0; i < n; i++) {
                accounts.register("test_verrou_" + i, PASSWORD, "Verrou", true);
            }
        });
        t.start();
        long worst = 0;
        while (t.isAlive()) {
            long s = System.nanoTime();
            check(accounts.session(token) == a, "session retrouvée pendant une inscription");
            worst = Math.max(worst, System.nanoTime() - s);
        }
        long each = (System.nanoTime() - start) / n;
        check(worst < each / 2, "une session ne patiente pas pendant un hachage (pire " + worst / 1_000_000 + " ms, hachage "
                + each / 1_000_000 + " ms)");
    }

    /** Eight registrations of the same name at once: one account. */
    private static void sameName(Accounts accounts) throws InterruptedException {
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger taken = new AtomicInteger();
        CountDownLatch go = new CountDownLatch(1);
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread t = new Thread(() -> {
                try {
                    go.await();
                    accounts.register("Test_Meme", PASSWORD, "Même", true);
                    ok.incrementAndGet();
                } catch (Http.Fail f) {
                    if (f.status == 409) {
                        taken.incrementAndGet();
                    }
                } catch (InterruptedException ignored) {
                    // not here
                }
            });
            t.start();
            threads.add(t);
        }
        go.countDown();
        for (Thread t : threads) {
            t.join();
        }
        check(ok.get() == 1 && taken.get() == 7, "un seul compte pour un nom demandé 8 fois à la fois (" + ok.get() + " créés)");
        check(accounts.byUsername("test_meme") != null, "le compte du nom demandé existe");
    }

    private static void perAddress(Accounts accounts) {
        int max = Accounts.MAX_REGISTRATIONS;
        for (int i = 0; i < max; i++) {
            accounts.register("test_ip4_" + i, PASSWORD, "IPv4", true, "203.0.113.5");
        }
        status(() -> accounts.register("test_ip4_x", PASSWORD, "IPv4", true, "203.0.113.5"), 429, "inscription au-delà de " + max + " par adresse refusée");
        accounts.register("test_ip4_y", PASSWORD, "IPv4", true, "198.51.100.7"); // another address is not slowed
        status(() -> accounts.register("test_meme", PASSWORD, "Même", true, "198.51.100.8"), 409, "nom déjà pris : refusé");
        status(() -> accounts.register("x", PASSWORD, "Court", true, "198.51.100.8"), 400, "identifiant invalide : refusé");
        // the refused attempts above cost no hashing and are not counted
        for (int i = 0; i < max - 1; i++) {
            accounts.register("test_ip4_z" + i, PASSWORD, "IPv4", true, "198.51.100.8");
        }
        accounts.register("test_ip4_zz", PASSWORD, "IPv4", true, "198.51.100.8");

        for (int i = 0; i < max; i++) {
            accounts.register("test_ip6_" + i, PASSWORD, "IPv6", true, "2001:db8:1:2:0:0:0:" + Integer.toHexString(i + 1));
        }
        status(() -> accounts.register("test_ip6_x", PASSWORD, "IPv6", true, "2001:db8:1:2:ffff:abcd:0:9"), 429,
                "une autre adresse du même /64 compte avec les autres");
        accounts.register("test_ip6_y", PASSWORD, "IPv6", true, "2001:db8:1:3:0:0:0:1");
        check(Accounts.address("::ffff:203.0.113.5").equals("203.0.113.5"), "adresse IPv4 dans IPv6 : comptée en IPv4");
        check(Accounts.address("2001:db8:1:2:0:0:0:1%3").equals(Accounts.address("2001:db8:1:2:aa:bb:cc:dd")), "zone IPv6 ignorée");
        check(Accounts.address("bidon:").equals("bidon:"), "adresse illisible gardée telle quelle");
    }

    private static void password(Accounts accounts, Path dir) throws Exception {
        Accounts.User u = accounts.register("test_mdp", PASSWORD, "Mdp", true);
        String other = accounts.openSession(u);
        for (int i = 0; i < 8; i++) {
            status(() -> accounts.changePassword(u, "mauvais-mot-de-passe", "nouveau-mot-de-passe"), 403, "ancien mot de passe faux");
        }
        status(() -> accounts.changePassword(u, PASSWORD, "nouveau-mot-de-passe"), 429, "essais limités depuis une session");
        check(accounts.session(other) == u, "session gardée tant que rien n'a changé");

        Accounts.User v = accounts.register("test_mdp2", PASSWORD, "Mdp", true);
        String session = accounts.openSession(v);
        check(!accounts.passwordIs(v, "mauvais-mot-de-passe") && accounts.passwordIs(v, PASSWORD), "passwordIs");
        // two devices change it at once: one of them only
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger refused = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            String next = "nouveau-mot-de-passe-" + i;
            Thread t = new Thread(() -> {
                try {
                    accounts.changePassword(v, PASSWORD, next);
                    ok.incrementAndGet();
                } catch (Http.Fail f) {
                    if (f.status == 409 || f.status == 403) {
                        refused.incrementAndGet();
                    }
                }
            });
            t.start();
            threads.add(t);
        }
        for (Thread t : threads) {
            t.join();
        }
        check(ok.get() == 1 && refused.get() == 1, "deux changements à la fois : un seul passe (" + ok.get() + ")");
        check(accounts.session(session) == null, "les autres sessions sont fermées après le changement");
        check(new Accounts(dir).session(session) == null, "et le restent après un redémarrage");
        int works = 0;
        for (int i = 0; i < 2; i++) {
            try {
                accounts.login("test_mdp2", "nouveau-mot-de-passe-" + i, "192.0.2.1");
                works++;
            } catch (Http.Fail ignored) {
                // the other one
            }
        }
        check(works == 1, "connexion avec le nouveau mot de passe");
    }

    /** 1000 changes from 20 threads: the file holds every one, written fewer times; a copy a day, 7 kept. */
    private static void persistence() throws Exception {
        Path dir = Files.createTempDirectory("cableclub-accounts-write");
        try {
            for (int d = 1; d <= 9; d++) {
                Files.writeString(dir.resolve("comptes-2026-01-0" + d + ".json"), "{}");
            }
            Accounts accounts = new Accounts(dir);
            List<Accounts.User> users = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                users.add(accounts.register("test_ecrit_" + i, PASSWORD, "Ecrit " + i, true));
            }
            long before = accounts.writes.get();
            int threads = 20;
            int each = 50;
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> list = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                int me = t;
                Thread th = new Thread(() -> {
                    try {
                        go.await();
                    } catch (InterruptedException e) {
                        return;
                    }
                    Accounts.User u = users.get(me);
                    for (int k = 0; k < each; k++) {
                        switch (k % 3) {
                            case 0 -> accounts.rename(u, "Nom " + me + "-" + k);
                            case 1 -> accounts.updateProfile(u, java.util.Map.of("bio", "Bio " + k));
                            default -> accounts.requestFriend(u, users.get((me + 1 + k) % threads));
                        }
                    }
                });
                th.start();
                list.add(th);
            }
            go.countDown();
            for (Thread th : list) {
                th.join();
            }
            long written = accounts.writes.get() - before;
            check(written < (long) threads * each, "modifications groupées en moins d'écritures (" + written + " pour "
                    + threads * each + ")");
            Accounts again = new Accounts(dir);
            boolean same = true;
            for (Accounts.User u : users) {
                Accounts.User r = again.get(u.id);
                same &= r != null && r.display.equals(u.display) && r.bio.equals(u.bio) && r.friends.equals(u.friends)
                        && r.incoming.equals(u.incoming) && r.outgoing.equals(u.outgoing);
            }
            check(same, "chaque modification est dans le fichier relu");
            List<Path> copies = Accounts.backups(dir);
            String today = "comptes-" + java.time.LocalDate.now() + ".json";
            check(copies.size() == Accounts.BACKUPS, "copies de secours gardées : " + copies.size());
            check(copies.stream().anyMatch(c -> c.getFileName().toString().equals(today)), "copie du jour");
            check(!Files.exists(dir.resolve("comptes-2026-01-01.json")), "les plus vieilles copies sont effacées");
            check(!Files.exists(dir.resolve("comptes.json.tmp")), "pas de fichier temporaire laissé");
        } finally {
            delete(dir);
        }
    }

    /** An unreadable comptes.json stops the server and names the latest copy, never loaded on its own. */
    private static void unreadable() throws IOException {
        Path dir = Files.createTempDirectory("cableclub-accounts-broken");
        try {
            Files.writeString(dir.resolve("comptes.json"), "{\"users\": [ coupé");
            Files.writeString(dir.resolve("comptes-2026-01-04.json"), "{}");
            Files.writeString(dir.resolve("comptes-2026-01-05.json"), "{}");
            try {
                new Accounts(dir);
                check(false, "comptes illisibles : le serveur ne démarre pas");
            } catch (IllegalStateException e) {
                check(e.getMessage().contains("comptes-2026-01-05.json"), "message : la copie la plus récente (" + e.getMessage() + ")");
            }
        } finally {
            delete(dir);
        }
    }

    private static void delete(Path dir) throws IOException {
        try (Stream<Path> files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private static void status(Runnable r, int expected, String what) {
        try {
            r.run();
            check(false, what + " (accepté)");
        } catch (Http.Fail f) {
            check(f.status == expected, what + " (" + f.status + ")");
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
