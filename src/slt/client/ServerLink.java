package slt.client;

import slt.Http;
import slt.Invitation;
import slt.Json;
import slt.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Connection of this SLT to the SLT server: authenticated requests and the
 * event stream (friends, invitations, rooms, journal, directives), relayed
 * to the local interface and cached for the game player.
 */
final class ServerLink {

    private final HttpClient http;
    private final Config config;
    private final LocalHub hub;
    private final Map<String, Map<String, Object>> directives = new ConcurrentHashMap<>();
    private volatile boolean connected;
    private volatile long generation;
    private volatile InputStream current;

    ServerLink(Config config, LocalHub hub) {
        this.config = config;
        this.hub = hub;
        // encrypted connections: the server is recognised by its certificate's fingerprint, until it gets a
        // real certificate for its name (then the usual checks are enough and the fingerprint is forgotten)
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .version(HttpClient.Version.HTTP_1_1)
                .sslContext(PinnedTls.context(() -> config.str("serverPin"), null, false, () -> {
                    if (config.str("serverPin") != null) {
                        Log.info("Le serveur a maintenant un certificat reconnu : son ancienne empreinte est oubliée");
                        config.set("serverPin", null);
                    }
                }))
                .build();
    }

    String base() {
        return config.str("server");
    }

    boolean loggedIn() {
        return config.str("token") != null && base() != null;
    }

    Map<String, Object> status() {
        return Json.map("server", base(), "connected", connected, "loggedIn", loggedIn(), "user", config.get("user"));
    }

    // ------------------------------------------------------------- server

    /** A server that answered: its address, its certificate fingerprint (null for plain HTTP), what it said. */
    private record Found(String url, String pin, Map<String, Object> info) {
    }

    /**
     * Addresses to try for what the player typed: "192.168.1.20", "2a01:…",
     * "monserveur.fr:47330", "https://…". Without a scheme, encrypted first.
     */
    static List<String> candidatesFor(String address) {
        String a = address == null ? "" : address.trim();
        if (a.isEmpty()) {
            throw new Http.Fail(400, "Indiquez l'adresse du serveur ou collez le code d'invitation.");
        }
        while (a.endsWith("/")) {
            a = a.substring(0, a.length() - 1);
        }
        String lower = a.toLowerCase(Locale.ROOT);
        boolean explicit = lower.startsWith("http://") || lower.startsWith("https://");
        String hostPort = explicit ? a.substring(a.indexOf("://") + 3) : a;
        if (hostPort.indexOf(':') != hostPort.lastIndexOf(':') && !hostPort.startsWith("[")) {
            hostPort = "[" + hostPort + "]"; // IPv6 address typed without brackets
        }
        URI u;
        try {
            u = URI.create("http://" + hostPort);
        } catch (IllegalArgumentException e) {
            throw new Http.Fail(400, "Adresse invalide : " + address);
        }
        if (u.getHost() == null) {
            throw new Http.Fail(400, "Adresse invalide : " + address);
        }
        if (u.getPort() < 0) {
            hostPort = hostPort + ":" + slt.server.SltServer.DEFAULT_PORT;
        }
        if (explicit) {
            return List.of(a.substring(0, a.indexOf("://") + 3) + hostPort);
        }
        return List.of("https://" + hostPort, "http://" + hostPort);
    }

    /**
     * Uses a server: an invitation code (every address of the server and its
     * certificate fingerprint) or a typed address. All the addresses are tried
     * at once; the first that answers like the expected SLT server is kept.
     */
    Map<String, Object> useServer(String input) {
        Invitation.Code code = Invitation.decode(input);
        if (code == null && Invitation.looksLike(input)) {
            throw new Http.Fail(400, "Code d'invitation incomplet : copiez-le en entier.");
        }
        List<String> candidates = code != null ? code.urls() : candidatesFor(input);
        String pin = code != null ? code.fingerprint() : null;
        Probe probe = probe(candidates, pin);
        for (Attempt a : probe.attempts()) {
            Log.info("Connexion à " + a.url() + " : " + label(a.reason()));
        }
        Found f = probe.found();
        if (f == null) {
            if (code != null) {
                throw new Http.Fail(502, "Le serveur de ce code ne répond pas. " + diagnose(probe.attempts(), hasIpv6()));
            }
            String host = URI.create(candidates.get(0)).getHost();
            if (host != null && Hosting.isLocalAddress(host)) {
                throw new Http.Fail(502, "Aucun serveur Cable Club ne répond à " + input.trim() + ". Cette adresse ne fonctionne que sur la "
                        + "même box (même Wi-Fi) que l'hôte. À distance, demandez à l'hôte son code d'invitation (Réglages > Serveur).");
            }
            boolean refused = probe.attempts().stream().allMatch(a -> "refused".equals(a.reason()));
            throw new Http.Fail(502, "Aucun serveur Cable Club ne répond à " + input.trim()
                    + (refused ? " (connexion refusée : Cable Club n'y est pas lancé, ou ce n'est pas le bon port)."
                    : " (pas de réponse : adresse erronée, ou connexion bloquée chez l'hôte par le pare-feu ou la box).")
                    + " Le plus simple : demandez à l'hôte son code d'invitation (Réglages > Serveur).");
        }
        switchTo(f.url(), f.pin(), code != null ? candidates : List.of(f.url()));
        return f.info();
    }

    /** This application's own server (hosted here): known address and certificate, nothing to probe. */
    void useLocal(String url, String pin) {
        // same server as before (now encrypted): the session is kept
        boolean same = isLoopback(base(), URI.create(url).getPort());
        switchTo(url, pin, List.of(url), same);
    }

    /** Whether an address is this PC's own server on that port. */
    boolean isLoopback(String url, int port) {
        if (url == null) {
            return false;
        }
        try {
            URI u = URI.create(url);
            return u.getPort() == port && ("127.0.0.1".equals(u.getHost()) || "localhost".equals(u.getHost()));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private void switchTo(String url, String pin, List<String> candidates) {
        switchTo(url, pin, candidates, false);
    }

    private void switchTo(String url, String pin, List<String> candidates, boolean sameServer) {
        String before = identity(config.str("serverPin"), base());
        String after = identity(pin, url);
        if (!sameServer && !after.equals(before)) {
            // another server: its accounts are not ours
            stopStream();
            config.set("token", null);
            config.set("user", null);
        }
        config.set("server", url);
        config.set("serverPin", pin);
        config.set("serverCandidates", new ArrayList<Object>(candidates));
    }

    private static String identity(String pin, String url) {
        return pin != null ? "pin:" + pin.toUpperCase(Locale.ROOT) : "url:" + url;
    }

    /** One address tried: the server that answered there, or why it failed (see {@link #reason}). */
    private record Attempt(String url, Found found, String reason) {
    }

    /** Every address of a server tried at once. */
    private record Probe(Found found, List<Attempt> attempts) {
    }

    /** Tries every address at once; the first SLT server with the right certificate wins. */
    private Probe probe(List<String> candidates, String pin) {
        if (candidates.isEmpty()) {
            return new Probe(null, List.of());
        }
        java.util.concurrent.CompletableFuture<Found> first = new java.util.concurrent.CompletableFuture<>();
        Map<String, Attempt> done = new java.util.concurrent.ConcurrentHashMap<>();
        java.util.concurrent.atomic.AtomicInteger left = new java.util.concurrent.atomic.AtomicInteger(candidates.size());
        for (String url : candidates) {
            Thread.startVirtualThread(() -> {
                try {
                    Attempt a = probeOne(url, pin);
                    done.put(url, a);
                    if (a.found() != null) {
                        first.complete(a.found());
                    }
                } finally {
                    if (left.decrementAndGet() == 0) {
                        first.complete(null);
                    }
                }
            });
        }
        Found f = null;
        try {
            f = first.get(9, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            // nothing answered in time
        }
        List<Attempt> attempts = new ArrayList<>();
        for (String url : candidates) {
            attempts.add(done.getOrDefault(url, new Attempt(url, null, f != null ? "skipped" : "timeout")));
        }
        return new Probe(f, attempts);
    }

    private Attempt probeOne(String url, String pin) {
        // a typed address first gets the usual certificate checks (server behind a real certificate, such as
        // Let's Encrypt's, renewed regularly: nothing to remember); only a certificate of its own is then
        // accepted and remembered at the first contact
        Attempt strict = probeOne(url, pin, false);
        if (pin == null && url.startsWith("https://") && "tls".equals(strict.reason())) {
            return probeOne(url, null, true);
        }
        return strict;
    }

    private Attempt probeOne(String url, String pin, boolean acceptAny) {
        java.util.concurrent.atomic.AtomicReference<String> seen = new java.util.concurrent.atomic.AtomicReference<>();
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).version(HttpClient.Version.HTTP_1_1);
        if (url.startsWith("https://")) {
            b.sslContext(PinnedTls.context(() -> pin, seen, acceptAny));
        }
        try {
            HttpResponse<String> r = b.build().send(HttpRequest.newBuilder(URI.create(url + "/api/server"))
                    .timeout(Duration.ofSeconds(7)).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, Object> info;
            try {
                info = Json.parseObject(r.body());
            } catch (RuntimeException e) {
                return new Attempt(url, null, "not-slt");
            }
            if (!"SLT".equals(Json.str(info, "app"))) {
                return new Attempt(url, null, "not-slt");
            }
            String fp = url.startsWith("https://") ? (pin != null ? pin : acceptAny ? seen.get() : null) : null;
            return new Attempt(url, new Found(url, fp, info), "ok");
        } catch (IOException | RuntimeException e) {
            return new Attempt(url, null, reason(e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt(url, null, "error");
        }
    }

    /**
     * Why an address failed: "timeout" (no answer: blocked on the way, by a
     * firewall or a box that does not forward the port), "refused" (the
     * machine answers but nothing listens on that port), "unreachable" (no
     * route, e.g. IPv6 on a PC without IPv6), "other-server" (another
     * certificate), "tls" (not an encrypted SLT server), "error".
     */
    static String reason(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof java.net.http.HttpTimeoutException) {
                return "timeout";
            }
            if (t instanceof PinnedTls.Mismatch) {
                return "other-server";
            }
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof javax.net.ssl.SSLException) {
                return "tls";
            }
            if (t instanceof java.net.NoRouteToHostException
                    || (t instanceof java.net.SocketException && String.valueOf(t.getMessage()).toLowerCase(Locale.ROOT).contains("unreachable"))) {
                return "unreachable";
            }
            if (t instanceof java.net.ConnectException) {
                return "refused";
            }
        }
        return "error";
    }

    private static String label(String reason) {
        return switch (reason) {
            case "ok" -> "serveur trouvé";
            case "skipped" -> "pas attendue (serveur déjà trouvé)";
            case "timeout" -> "pas de réponse";
            case "refused" -> "connexion refusée";
            case "unreachable" -> "réseau inaccessible";
            case "other-server" -> "autre serveur (certificat différent)";
            case "tls" -> "pas un serveur Cable Club chiffré";
            case "not-slt" -> "pas un serveur Cable Club";
            default -> "erreur";
        };
    }

    /** Whether this PC has a public IPv6 address (otherwise the IPv6 addresses of a code cannot work from here). */
    private static boolean hasIpv6() {
        return Hosting.networks().stream().anyMatch(n -> n[0].contains(":"));
    }

    /**
     * What went wrong when no address of an invitation code answered, from
     * what the code contains and how each address failed.
     */
    static String diagnose(List<Attempt> attempts, boolean ipv6Here) {
        List<Attempt> internet = new ArrayList<>();
        List<Attempt> v6 = new ArrayList<>();
        for (Attempt a : attempts) {
            String host = URI.create(a.url()).getHost();
            String kind = Hosting.addressKind(host);
            if ("ipv6".equals(kind)) {
                v6.add(a);
            } else if ("public".equals(kind)) {
                internet.add(a);
            }
        }
        if (attempts.stream().anyMatch(a -> "other-server".equals(a.reason()))) {
            return "Un autre serveur Cable Club répond à l'une de ses adresses (certificat différent) : demandez à l'hôte de vous renvoyer son code.";
        }
        if (!internet.isEmpty()) {
            String ip = URI.create(internet.get(0).url()).getHost();
            if (internet.stream().anyMatch(a -> "tls".equals(a.reason()) || "not-slt".equals(a.reason()))) {
                return "Un serveur répond à " + ip + " mais pas comme SLT " + slt.Version.VERSION
                        + " : l'hôte et vous devez avoir la même version de Cable Club.";
            }
            if (internet.stream().allMatch(a -> "refused".equals(a.reason()))) {
                return "L'adresse Internet de l'hôte (" + ip + ") refuse la connexion : Cable Club n'y tourne plus (fermé, hébergement arrêté) "
                        + "ou sa box ne redirige plus le port. Demandez-lui de vérifier Réglages > Serveur, puis de vous renvoyer son code.";
            }
            return "L'adresse Internet de l'hôte (" + ip + ") ne répond pas : la connexion est bloquée chez l'hôte, le plus souvent par le "
                    + "pare-feu Windows (chez lui, Réglages > Serveur le signale avec un bouton « Autoriser Cable Club ») ou par sa box.";
        }
        String v6note = v6.isEmpty() ? ""
                : !ipv6Here ? " (Les adresses IPv6 de l'hôte ne servent pas ici : ce PC n'a pas d'IPv6.)"
                : " (Les adresses IPv6 de l'hôte ne répondent pas non plus : bloquées par le pare-feu de sa box ou de Windows.)";
        return (v6.isEmpty() ? "Le code ne contient que des adresses du réseau local de l'hôte (même box)"
                : "Le code ne contient pas l'adresse Internet IPv4 de l'hôte")
                + " : sa box n'avait pas encore ouvert le port quand il a copié le code. Il doit activer « Accessible depuis Internet » "
                + "dans Réglages > Serveur, attendre que la ligne passe au vert, puis vous renvoyer le code." + v6note;
    }

    /** The address in use stopped answering: try the other addresses of the same server. */
    private void reprobe() {
        List<Object> list = Json.arr(config.get("serverCandidates"));
        String pin = config.str("serverPin");
        if (list == null || list.size() < 2 || pin == null) {
            return;
        }
        List<String> urls = new ArrayList<>();
        for (Object o : list) {
            urls.add(String.valueOf(o));
        }
        Found f = probe(urls, pin).found();
        if (f != null && !f.url().equals(base())) {
            Log.info("Serveur SLT joint par une autre adresse : " + f.url());
            config.set("server", f.url());
        }
    }

    // -------------------------------------------------------------- auth

    Map<String, Object> login(String path, Map<String, Object> body) {
        if (base() == null) {
            throw new Http.Fail(400, "Choisissez d'abord un serveur.");
        }
        Map<String, Object> r = Json.obj(request("POST", path, body, false));
        config.set("token", Json.str(r, "token"));
        config.set("user", r.get("user"));
        startStream();
        // answer once the live connection is up, so the interface starts "online"
        for (int i = 0; i < 60 && !connected; i++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return status();
    }

    void logout() {
        if (loggedIn()) {
            try {
                request("POST", "/api/auth/logout", Json.map(), true);
            } catch (Http.Fail ignored) {
                // offline: the local session is forgotten anyway
            }
        }
        stopStream();
        config.set("token", null);
        config.set("user", null);
        directives.clear();
        hub.send("session", status());
    }

    // ---------------------------------------------------------- requests

    /** JSON request to the server; errors become {@link Http.Fail} with the server's message. */
    Object call(String method, String path, Object body) {
        return request(method, path, body, true);
    }

    private Object request(String method, String path, Object body, boolean auth) {
        String b = base();
        if (b == null) {
            throw new Http.Fail(400, "Aucun serveur configuré.");
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(b + path)).timeout(Duration.ofSeconds(20));
        if (auth) {
            String token = config.str("token");
            if (token == null) {
                throw new Http.Fail(401, "Connectez-vous d'abord.");
            }
            rb.header("Authorization", "Bearer " + token);
        }
        if (body != null && !"GET".equals(method)) {
            rb.header("Content-Type", "application/json");
            rb.method(method, HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8));
        } else {
            rb.method(method, HttpRequest.BodyPublishers.noBody());
        }
        HttpResponse<String> r;
        try {
            r = http.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new Http.Fail(502, "Serveur Cable Club injoignable (" + b + ").");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Http.Fail(500, "Interrompu");
        }
        Object data = null;
        if (!r.body().isEmpty()) {
            try {
                data = Json.parse(r.body());
            } catch (IllegalArgumentException e) {
                data = r.body();
            }
        }
        if (r.statusCode() == 401 && auth) {
            sessionLost();
        }
        if (r.statusCode() >= 400) {
            String msg = data instanceof Map ? Json.str(Json.obj(data), "error") : null;
            throw new Http.Fail(r.statusCode(), msg == null ? "Erreur du serveur (" + r.statusCode() + ")" : msg);
        }
        return data;
    }

    /** Binary GET (saves); null when the server has nothing (204 / 404). */
    byte[] getBytes(String path) {
        HttpResponse<byte[]> r = getResponse(path);
        return r == null ? null : r.body();
    }

    /** Binary GET with its headers (live images carry their number); null when there is nothing. */
    HttpResponse<byte[]> getResponse(String path) {
        String token = config.str("token");
        if (base() == null || token == null) {
            return null;
        }
        try {
            HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            return r.statusCode() == 200 ? r : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    boolean postBytes(String path, byte[] data) {
        String token = config.str("token");
        if (base() == null || token == null) {
            return false;
        }
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(base() + path))
                    .timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/octet-stream")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(data)).build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private void sessionLost() {
        stopStream();
        config.set("token", null);
        hub.send("session", status());
    }

    // ----------------------------------------------------------- directives

    /** What the server last said this player's game must apply in a room (null = unknown yet). */
    Map<String, Object> directives(String roomId) {
        Map<String, Object> d = directives.get(roomId);
        if (d == null && loggedIn()) {
            try {
                Map<String, Object> room = Json.obj(call("GET", "/api/rooms/" + roomId, null));
                d = Json.obj(room.get("directives"));
                if (d != null) {
                    directives.put(roomId, d);
                }
            } catch (Http.Fail e) {
                return null;
            }
        }
        return d;
    }

    // --------------------------------------------------------------- stream

    synchronized void startStream() {
        stopStream();
        if (!loggedIn()) {
            return;
        }
        long gen = ++generation;
        Thread t = new Thread(() -> streamLoop(gen), "slt-flux");
        t.setDaemon(true);
        t.start();
    }

    synchronized void stopStream() {
        generation++;
        InputStream in = current;
        current = null;
        if (in != null) {
            try {
                in.close();
            } catch (IOException ignored) {
                // closing to unblock the reader
            }
        }
        if (connected) {
            connected = false;
            hub.send("link", status());
        }
    }

    private void streamLoop(long gen) {
        long backoff = 1000;
        int failures = 0;
        while (gen == generation && loggedIn()) {
            try {
                HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/stream"))
                        .header("Authorization", "Bearer " + config.str("token")).GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                if (r.statusCode() == 401) {
                    r.body().close();
                    sessionLost();
                    return;
                }
                if (r.statusCode() != 200) {
                    r.body().close();
                    throw new IOException("statut " + r.statusCode());
                }
                current = r.body();
                if (gen != generation) {
                    current.close();
                    return;
                }
                connected = true;
                backoff = 1000;
                failures = 0;
                hub.send("link", status());
                read(r.body(), gen);
            } catch (IOException e) {
                // reconnect below
            } catch (InterruptedException e) {
                return;
            }
            if (gen != generation) {
                return;
            }
            if (connected) {
                connected = false;
                hub.send("link", status());
                Log.info("Connexion au serveur SLT perdue, nouvelle tentative…");
            }
            if (++failures % 3 == 0) {
                reprobe();
            }
            try {
                Thread.sleep(backoff);
            } catch (InterruptedException e) {
                return;
            }
            backoff = Math.min(15_000, backoff * 2);
        }
    }

    private void read(InputStream in, long gen) throws IOException {
        BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        String event = null;
        StringBuilder data = new StringBuilder();
        String line;
        while (gen == generation && (line = br.readLine()) != null) {
            if (line.isEmpty()) {
                if (event != null) {
                    dispatch(event, data.toString());
                }
                event = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                event = line.substring(6).trim();
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) {
                    data.append('\n');
                }
                data.append(line.substring(5).trim());
            }
        }
    }

    private void dispatch(String event, String json) {
        try {
            if ("hello".equals(event)) {
                // the server's greeting (who we are there) is not relayed: the interface's own "hello"
                // carries its whole state, and taking this one for it logged remote players out (0.4.3)
                Map<String, Object> user = Json.obj(Json.parseObject(json).get("user"));
                if (user != null && !user.equals(config.get("user"))) {
                    config.set("user", user);
                    hub.send("link", status());
                }
                return;
            }
            if ("room".equals(event)) {
                Map<String, Object> room = Json.parseObject(json);
                Map<String, Object> d = Json.obj(room.get("directives"));
                if (d != null) {
                    directives.put(Json.str(room, "id"), d);
                }
            } else if ("room-deleted".equals(event)) {
                directives.remove(Json.str(Json.parseObject(json), "roomId"));
            }
        } catch (RuntimeException e) {
            Log.warn("Événement du serveur illisible (" + event + ") : " + e.getMessage());
        }
        hub.send(event, json);
    }
}
