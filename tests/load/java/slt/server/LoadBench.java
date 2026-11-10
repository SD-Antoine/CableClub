package slt.server;

import slt.Json;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Load bench of the server (audit of 2026-11-10), run by {@code tests\load\load.ps1}, never against another
 * machine: the client only talks to 127.0.0.1.
 * <ul>
 *   <li>{@code seed <data> <players>}: made-up accounts with an open session, paired in running rooms of two
 *       players, written straight into a throwaway data directory before the server starts;</li>
 *   <li>{@code run <port> <data> <server pid> <warm-up s> <measure s>}: each player sends what the application
 *       sends in game (4 images of 15 KB/s, the partner's image 4 times/s, 5 positions/s, 1 batch of events/s,
 *       an open event stream: 14 requests/s), then the errors, the latency and the processor time of the server.
 *       The players arrive one after the other during the warm-up, and only the requests after it are measured.</li>
 * </ul>
 */
public final class LoadBench {

    private static final int SCREEN_BYTES = 15_000;
    private static final String PASSWORD = "mot-de-passe-de-charge";

    public static void main(String[] args) throws Exception {
        if (args.length >= 3 && args[0].equals("seed")) {
            seed(Path.of(args[1]), Integer.parseInt(args[2]));
        } else if (args.length >= 6 && args[0].equals("run")) {
            watching = args.length > 6 ? Integer.parseInt(args[6]) : 100;
            oldClient = args.length > 7 && args[7].equals("1");
            run(Integer.parseInt(args[1]), Path.of(args[2]), Long.parseLong(args[3]), Integer.parseInt(args[4]),
                    Integer.parseInt(args[5]));
        } else {
            System.err.println("LoadBench seed <data> <joueurs> | run <port> <data> <pid> <chauffe s> <mesure s>"
                    + " [<% d'écrans regardés> [<1 = ancien client>]]");
            System.exit(2);
        }
    }

    // ------------------------------------------------------------------- seed

    private static void seed(Path dir, int players) throws Exception {
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); // one log line per account
        Files.createDirectories(dir);
        Accounts accounts = new Accounts(dir);
        Rooms rooms = new Rooms(dir, accounts, new Hub());
        Map<String, Object> body = Json.map("mode", "soullink", "maxPlayers", 2L,
                "game", Json.map("gameId", "rubisfr", "name", "Pokémon Rubis", "gen", 3L, "platform", "gba", "lang", "fr",
                        "crc", "chargecrc"));
        Map<String, Object> rom = Json.map("status", "ready", "crc", "chargecrc", "gameId", "rubisfr", "lang", "fr");
        StringBuilder out = new StringBuilder();
        long start = System.nanoTime();
        for (int i = 0; i + 1 < players; i += 2) {
            Accounts.User a = accounts.register(String.format("charge_%05d", i), PASSWORD, "Charge " + i, true);
            Accounts.User b = accounts.register(String.format("charge_%05d", i + 1), PASSWORD, "Charge " + (i + 1), true);
            String id = Json.str(rooms.create(a, body), "id");
            rooms.invite(id, a, b.id);
            rooms.join(rooms.get(id), b, false);
            for (Accounts.User u : List.of(a, b)) {
                rooms.rom(id, u, rom);
                rooms.ready(id, u, true);
            }
            rooms.launch(id, a);
            out.append(id).append(' ').append(a.id).append(' ').append(accounts.openSession(a)).append(' ')
                    .append(b.id).append(' ').append(accounts.openSession(b)).append('\n');
            if ((i / 2) % 50 == 49) {
                System.out.printf("  %d joueurs préparés (%d s)%n", i + 2, (System.nanoTime() - start) / 1_000_000_000L);
            }
        }
        Files.writeString(dir.resolve("charge-salles.txt"), out.toString());
    }

    // -------------------------------------------------------------------- run

    /**
     * One player: their own HTTP client (connections of their own, as a browser), room and partner; whether they
     * look at their partner's screen, and what the server last said of theirs (watched or not).
     */
    private record Player(HttpClient http, String room, String id, String token, String partner, byte[] screen,
                          AtomicLong seen, java.util.concurrent.atomic.AtomicBoolean[] busy, boolean watcher,
                          java.util.concurrent.atomic.AtomicBoolean watched, AtomicLong lastScreen) {
    }

    /** Share of the players who look at their partner's screen (%), and whether the senders ignore "watched". */
    private static int watching = 100;
    private static boolean oldClient;
    /** As the application (LiveCast): one image every 2 s while the server says nobody looks. */
    private static final long IDLE_MS = 2_000;
    private static final AtomicLong upBytes = new AtomicLong();
    private static final AtomicLong downBytes = new AtomicLong();

    private static final AtomicLong done = new AtomicLong();
    private static final AtomicLong errors = new AtomicLong();
    private static final AtomicLong streams = new AtomicLong();
    private static final AtomicLong skipped = new AtomicLong();
    /** Latency of the measured requests, by millisecond (the last bucket: 10 s and more). */
    private static final AtomicLongArray latency = new AtomicLongArray(10_001);
    private static final Map<String, AtomicLong> errorKinds = new ConcurrentHashMap<>();
    private static volatile boolean measuring;

    private static void run(int port, Path dir, long pid, int warmup, int seconds) throws Exception {
        String base = "https://127.0.0.1:" + port;
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {
            }

            public void checkServerTrusted(X509Certificate[] c, String a) {
            }

            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }}, null);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpClient probe = client(ssl, executor);
        waitForServer(probe, base);

        List<Player> players = new ArrayList<>();
        Random random = new Random(1);
        for (String line : Files.readAllLines(dir.resolve("charge-salles.txt"))) {
            String[] f = line.trim().split(" ");
            if (f.length < 5) {
                continue;
            }
            for (int k = 0; k < 2; k++) {
                byte[] screen = new byte[SCREEN_BYTES];
                random.nextBytes(screen);
                java.util.concurrent.atomic.AtomicBoolean[] busy = new java.util.concurrent.atomic.AtomicBoolean[4];
                for (int j = 0; j < busy.length; j++) {
                    busy[j] = new java.util.concurrent.atomic.AtomicBoolean();
                }
                players.add(new Player(client(ssl, executor), f[0], f[1 + 2 * k], f[2 + 2 * k], f[3 - 2 * k], screen,
                        new AtomicLong(), busy, random.nextInt(100) < watching,
                        new java.util.concurrent.atomic.AtomicBoolean(true), new AtomicLong(Long.MIN_VALUE / 2)));
            }
        }
        System.out.printf("%d joueurs, %d salles : connexion…%n", players.size(), players.size() / 2);

        ScheduledExecutorService timer = Executors.newScheduledThreadPool(4);
        // the players arrive over the first 60 % of the warm-up: all at once is another test (a restart of the server)
        double step = warmup * 600.0 / Math.max(1, players.size());
        for (int i = 0; i < players.size(); i++) {
            Player p = players.get(i);
            long d = (long) (i * step) + random.nextInt(250);
            timer.schedule(() -> stream(p, base), d, TimeUnit.MILLISECONDS);
            timer.scheduleAtFixedRate(() -> {
                long now = System.currentTimeMillis();
                if (!oldClient && !p.watched.get() && now - p.lastScreen.get() < IDLE_MS) {
                    return;
                }
                p.lastScreen.set(now);
                send(p, HttpRequest.newBuilder(URI.create(base + "/api/rooms/" + p.room + "/screen"))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(p.screen)), 0);
            }, d, 250, TimeUnit.MILLISECONDS);
            if (p.watcher) {
                timer.scheduleAtFixedRate(() -> send(p, HttpRequest.newBuilder(URI.create(base + "/api/rooms/" + p.room
                        + "/screen/" + p.partner + "?since=" + p.seen.get())).GET(), 1), d + 125, 250, TimeUnit.MILLISECONDS);
            }
            timer.scheduleAtFixedRate(() -> send(p, json(base + "/api/rooms/" + p.room + "/ghost",
                    "{\"map\":3,\"x\":" + random.nextInt(40) + ",\"y\":" + random.nextInt(40) + ",\"st\":0}"), 2),
                    d + 60, 200, TimeUnit.MILLISECONDS);
            timer.scheduleAtFixedRate(() -> send(p, json(base + "/api/rooms/" + p.room + "/events", "{\"events\":[]}"), 3),
                    d + 500, 1000, TimeUnit.MILLISECONDS);
        }

        Thread.sleep(warmup * 1000L);
        ProcessHandle server = ProcessHandle.of(pid).orElseThrow(() -> new IllegalStateException("serveur arrêté"));
        long cpu0 = cpu(server);
        long t0 = System.nanoTime();
        measuring = true;
        Thread.sleep(seconds * 1000L);
        measuring = false;
        long wall = System.nanoTime() - t0;
        long cpu1 = cpu(server);
        timer.shutdownNow();

        long n = done.get();
        System.out.println();
        System.out.printf("Joueurs           : %d (flux d'événements ouverts : %d)%n", players.size(), streams.get());
        System.out.printf("Requêtes mesurées : %d en %d s (%.0f/s)%n", n, seconds, n / (wall / 1e9));
        System.out.printf("Envois sautés     : %d (requête précédente encore en route)%n", skipped.get());
        System.out.printf("Erreurs           : %d%n", errors.get());
        errorKinds.forEach((k, v) -> System.out.printf("    %6d × %s%n", v.get(), k));
        System.out.printf("Latence           : médiane %d ms, 95 %% %d ms, 99 %% %d ms, max %d ms%n",
                percentile(n, 0.50), percentile(n, 0.95), percentile(n, 0.99), percentile(n, 1.0));
        System.out.printf("Processeur serveur: %.2f cœur%n", (cpu1 - cpu0) / (double) wall);
        double perPlayer = players.size() * (wall / 1e9) * 1e6 / 8; // bytes in a Mbit/s of one player
        System.out.printf("Débit par joueur  : %.2f Mbit/s reçus par le serveur, %.2f envoyés (corps des requêtes)%n",
                upBytes.get() / perPlayer, downBytes.get() / perPlayer);
        System.out.printf("Écrans regardés   : %d %% des joueurs%s%n", watching, oldClient ? ", ancien client (ignore « watched »)" : "");
        System.exit(errors.get() == 0 && streams.get() == players.size() ? 0 : 1);
    }

    private static HttpClient client(SSLContext ssl, java.util.concurrent.Executor executor) {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).sslContext(ssl).executor(executor)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    private static HttpRequest.Builder json(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private static void waitForServer(HttpClient http, String base) throws InterruptedException {
        for (int i = 0; i < 120; i++) {
            try {
                if (http.send(HttpRequest.newBuilder(URI.create(base + "/api/server")).build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200) {
                    return;
                }
            } catch (Exception ignored) {
                // not up yet
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("le serveur ne répond pas sur " + base);
    }

    /** The event stream of a player, kept open and read until the end. */
    private static void stream(Player p, String base) {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/api/stream?token=" + p.token)).GET().build();
        p.http.sendAsync(r, HttpResponse.BodyHandlers.ofLines()).thenAccept(res -> {
            if (res.statusCode() == 200) {
                streams.incrementAndGet();
                Thread.ofVirtual().start(() -> res.body().forEach(l -> {
                }));
            }
        });
    }

    /**
     * One request of one of the 4 loops of a player (0 image sent, 1 image read, 2 position, 3 events). As the
     * application, a loop waits for its answer: a time come while it is still on its way is skipped.
     */
    private static void send(Player p, HttpRequest.Builder b, int loop) {
        if (!p.busy[loop].compareAndSet(false, true)) {
            if (measuring) {
                skipped.incrementAndGet();
            }
            return;
        }
        boolean screen = loop == 1;
        HttpRequest r = b.header("Authorization", "Bearer " + p.token).timeout(Duration.ofSeconds(30)).build();
        long start = System.nanoTime();
        boolean counted = measuring;
        long sent = r.bodyPublisher().map(HttpRequest.BodyPublisher::contentLength).orElse(0L);
        p.http.sendAsync(r, HttpResponse.BodyHandlers.ofByteArray()).whenComplete((res, e) -> {
            p.busy[loop].set(false);
            if (loop == 0 && res != null && res.statusCode() == 200) {
                p.watched.set(!new String(res.body(), java.nio.charset.StandardCharsets.UTF_8).contains("\"watched\":false"));
            }
            if (!counted) {
                return;
            }
            upBytes.addAndGet(Math.max(0, sent));
            if (res != null && res.body() != null) {
                downBytes.addAndGet(res.body().length);
            }
            String kind = null;
            if (e != null) {
                Throwable c = e.getCause() != null ? e.getCause() : e;
                kind = c.getClass().getSimpleName() + (c.getMessage() == null ? "" : " : " + c.getMessage());
            } else if (res.statusCode() != 200 && res.statusCode() != 204) {
                kind = "HTTP " + res.statusCode() + " " + r.uri().getPath().replaceAll("/[a-z0-9]{10,}", "/…");
            } else if (screen && res.statusCode() == 200) {
                res.headers().firstValue("X-Seq").ifPresent(s -> p.seen.set(Long.parseLong(s)));
            }
            done.incrementAndGet();
            if (kind != null) {
                errors.incrementAndGet();
                errorKinds.computeIfAbsent(kind.length() > 120 ? kind.substring(0, 120) : kind, k -> new AtomicLong())
                        .incrementAndGet();
            }
            latency.incrementAndGet((int) Math.min(10_000, (System.nanoTime() - start) / 1_000_000));
        });
    }

    private static long percentile(long n, double q) {
        long target = Math.max(1, (long) Math.ceil(n * q));
        long sum = 0;
        for (int i = 0; i < latency.length(); i++) {
            sum += latency.get(i);
            if (sum >= target) {
                return i;
            }
        }
        return latency.length() - 1;
    }

    private static long cpu(ProcessHandle p) {
        return p.info().totalCpuDuration().map(Duration::toNanos).orElse(0L);
    }

    static {
        // the server's own certificate names no host
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    private LoadBench() {
    }
}
