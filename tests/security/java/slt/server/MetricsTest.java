package slt.server;

import slt.Json;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The state of the server for the moderators (point 5 of the follow-up of the audit of 2026-11-10): the route is
 * refused without a session and to a player, gives a moderator the counts (requests, rooms by state), and names
 * nobody. Throwaway server on 127.0.0.1, made-up accounts.
 */
public final class MetricsTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        System.setErr(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())); // log lines of the server
        Path dir = Files.createTempDirectory("cableclub-metrics-test");
        Files.writeString(dir.resolve("admins.txt"), "test_metrique_m\n");
        SltServer server = new SltServer(dir, "Serveur de test");
        try {
            server.start("127.0.0.1", 0, null, null);
            run(server, "http://127.0.0.1:" + server.port());
        } finally {
            server.stop();
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     état du serveur pour les modérateurs" : "ÉCHEC  état du serveur : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static void run(SltServer server, String base) throws Exception {
        Accounts.User player = server.accounts.register("test_metrique_j", "mot-de-passe-de-test", "Joueuse Métrique", true);
        Accounts.User other = server.accounts.register("test_metrique_k", "mot-de-passe-de-test", "Autre", true);
        Accounts.User mod = server.accounts.register("test_metrique_m", "mot-de-passe-de-test", "Modo", true);
        String playerToken = server.accounts.openSession(player);
        String modToken = server.accounts.openSession(mod);

        // a running room of two
        Rooms rooms = server.rooms;
        String id = Json.str(rooms.create(player, Json.map("mode", "soullink", "maxPlayers", 2L,
                "game", Json.map("gameId", "rubisfr", "name", "Pokémon Rubis", "gen", 3L, "crc", "metriquecrc"))), "id");
        rooms.invite(id, player, other.id);
        rooms.join(rooms.get(id), other, false);
        for (Accounts.User u : List.of(player, other)) {
            rooms.rom(id, u, Json.map("status", "ready", "crc", "metriquecrc", "gameId", "rubisfr", "lang", "fr"));
            rooms.ready(id, u, true);
        }
        rooms.launch(id, player);

        HttpClient http = HttpClient.newHttpClient();
        check(get(http, base, null).statusCode() == 401, "sans session : 401");
        check(get(http, base, playerToken).statusCode() == 403, "joueur ordinaire : 403");
        for (int i = 0; i < 5; i++) {
            get(http, base, playerToken);
        }
        HttpResponse<String> res = get(http, base, modToken);
        check(res.statusCode() == 200, "modérateur : 200 (" + res.statusCode() + ")");
        if (res.statusCode() != 200) {
            return;
        }
        Map<String, Object> m = Json.parseObject(res.body());
        Map<String, Object> q = Json.obj(m.get("requests"));
        check(Json.num(q, "total", 0) >= 7, "requêtes comptées (" + Json.num(q, "total", 0) + ")");
        check(Json.num(q, "clientErrors", 0) >= 6, "refus comptés (" + Json.num(q, "clientErrors", 0) + ")");
        Map<String, Object> r = Json.obj(m.get("rooms"));
        check(Json.num(r, "running", 0) == 1, "salle en cours comptée");
        check(Json.num(m, "accounts", 0) == 3, "comptes comptés");
        check(Json.num(Json.obj(m.get("memory")), "maxMb", 0) > 0, "mémoire");
        check(Json.num(Json.obj(m.get("cpu")), "cores", 0) > 0, "cœurs");
        for (String s : List.of(player.id, other.id, mod.id, "test_metrique", "Joueuse", id)) {
            check(!res.body().contains(s), "personne n'est nommé (" + s + ")");
        }
    }

    private static HttpResponse<String> get(HttpClient http, String base, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/admin/metrics")).GET();
        if (token != null) {
            b.header("Authorization", "Bearer " + token);
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
