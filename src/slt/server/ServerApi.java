package slt.server;

import com.sun.net.httpserver.HttpExchange;
import slt.Http;
import slt.Json;
import slt.Log;
import slt.Sse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** HTTP API of the SLT server (JSON, bearer tokens, one event stream per client). */
final class ServerApi {

    private final SltServer server;
    private final Accounts accounts;
    private final Rooms rooms;
    private final Hub hub;
    private final Http.Router router = new Http.Router();

    ServerApi(SltServer server) {
        this.server = server;
        this.accounts = server.accounts;
        this.rooms = server.rooms;
        this.hub = server.hub;
        routes();
    }

    void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if (!path.startsWith("/api/")) {
                Http.json(ex, 200, Json.map("app", "SLT", "role", "server", "version", SltServer.VERSION,
                        "message", "Serveur SLT — Soul Link Together. Connectez-vous depuis l'application SLT."));
                return;
            }
            if (!router.dispatch(ex, path)) {
                Http.error(ex, 404, "Route inconnue : " + path);
            }
        } catch (Http.Fail f) {
            Http.error(ex, f.status, f.getMessage());
        } catch (Exception e) {
            Log.error("Serveur : erreur sur " + ex.getRequestMethod() + " " + path, e);
            Http.error(ex, 500, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private Accounts.User user(HttpExchange ex) {
        Accounts.User u = accounts.session(Http.bearer(ex));
        if (u == null) {
            throw new Http.Fail(401, "Session expirée : reconnectez-vous.");
        }
        return u;
    }

    private Map<String, Object> me(Accounts.User u) {
        return Json.map("id", u.id, "username", u.username, "name", u.display, "createdAt", u.createdAt);
    }

    private void routes() {
        Http.Router r = router;

        r.on("GET", "/api/server", (ex, p) -> Http.json(ex, 200, Json.map("app", "SLT", "role", "server",
                "version", SltServer.VERSION, "name", server.name, "users", (long) server.userCount())));

        // ------------------------------------------------------------ auth
        r.on("POST", "/api/auth/register", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            Accounts.User u = accounts.register(Json.str(b, "username"), Json.str(b, "password"), Json.str(b, "name"));
            Http.json(ex, 200, Json.map("token", accounts.openSession(u), "user", me(u)));
        });
        r.on("POST", "/api/auth/login", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            Accounts.User u = accounts.login(Json.str(b, "username"), Json.str(b, "password"), Http.clientIp(ex));
            Http.json(ex, 200, Json.map("token", accounts.openSession(u), "user", me(u)));
        });
        r.on("POST", "/api/auth/logout", (ex, p) -> {
            accounts.closeSession(Http.bearer(ex));
            Http.ok(ex);
        });
        r.on("GET", "/api/me", (ex, p) -> Http.json(ex, 200, me(user(ex))));
        r.on("POST", "/api/me", (ex, p) -> {
            Accounts.User u = user(ex);
            accounts.rename(u, Json.str(Http.readJson(ex), "name"));
            Http.json(ex, 200, me(u));
        });
        r.on("POST", "/api/me/password", (ex, p) -> {
            Accounts.User u = user(ex);
            Map<String, Object> b = Http.readJson(ex);
            accounts.changePassword(u, Json.str(b, "old"), Json.str(b, "new"));
            Http.json(ex, 200, Json.map("token", accounts.openSession(u)));
        });

        // ---------------------------------------------------------- stream
        r.on("GET", "/api/stream", (ex, p) -> {
            Accounts.User u = user(ex);
            Sse.serve(ex, Json.stringify(Json.map("user", me(u))), s -> hub.connect(u.id, s), s -> hub.disconnect(u.id, s));
        });

        // --------------------------------------------------------- friends
        r.on("GET", "/api/friends", (ex, p) -> Http.json(ex, 200, friends(user(ex))));
        r.on("GET", "/api/users/search", (ex, p) -> {
            Accounts.User u = user(ex);
            Http.json(ex, 200, Json.map("users", accounts.search(Http.query(ex).get("q"), u.id)));
        });
        r.on("POST", "/api/friends/request", (ex, p) -> {
            Accounts.User u = user(ex);
            Accounts.User other = accounts.byUsername(Json.str(Http.readJson(ex), "username"));
            if (other == null) {
                throw new Http.Fail(404, "Aucun joueur avec cet identifiant.");
            }
            String result = accounts.requestFriend(u, other);
            hub.send(other.id, "friends", Json.map("reason", result.equals("friends") ? "accepted" : "request", "from", u.display));
            hub.send(u.id, "friends", Json.map("reason", result));
            Http.json(ex, 200, Json.map("result", result, "name", other.display));
        });
        r.on("POST", "/api/friends/{id}/accept", (ex, p) -> {
            Accounts.User u = user(ex);
            Accounts.User other = other(p.get("id"));
            accounts.accept(u, other);
            hub.send(other.id, "friends", Json.map("reason", "accepted", "from", u.display));
            hub.send(u.id, "friends", Json.map("reason", "accepted"));
            Http.ok(ex);
        });
        r.on("POST", "/api/friends/{id}/remove", (ex, p) -> {
            Accounts.User u = user(ex);
            Accounts.User other = other(p.get("id"));
            accounts.unlink(u, other);
            hub.send(other.id, "friends", Json.map("reason", "removed"));
            hub.send(u.id, "friends", Json.map("reason", "removed"));
            Http.ok(ex);
        });

        // ----------------------------------------------------------- rooms
        r.on("GET", "/api/rooms", (ex, p) -> Http.json(ex, 200, Json.map("rooms", rooms.mine(user(ex)))));
        r.on("GET", "/api/history", (ex, p) -> Http.json(ex, 200, Json.map("rooms", rooms.history(user(ex)))));
        r.on("POST", "/api/rooms", (ex, p) -> Http.json(ex, 200, rooms.create(user(ex), Http.readJson(ex))));
        r.on("POST", "/api/rooms/join", (ex, p) -> {
            Accounts.User u = user(ex);
            Room room = rooms.byCode(Json.str(Http.readJson(ex), "code"));
            Http.json(ex, 200, rooms.join(room, u, true));
        });
        r.on("GET", "/api/rooms/{id}", (ex, p) -> Http.json(ex, 200, rooms.view(p.get("id"), user(ex))));
        r.on("POST", "/api/rooms/{id}/settings", (ex, p) -> {
            rooms.settings(p.get("id"), user(ex), Http.readJson(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/invite", (ex, p) -> {
            rooms.invite(p.get("id"), user(ex), Json.str(Http.readJson(ex), "userId"));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/join", (ex, p) -> Http.json(ex, 200, rooms.join(rooms.get(p.get("id")), user(ex), false)));
        r.on("POST", "/api/rooms/{id}/decline", (ex, p) -> {
            rooms.decline(p.get("id"), user(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/leave", (ex, p) -> {
            rooms.leave(p.get("id"), user(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/kick", (ex, p) -> {
            rooms.kick(p.get("id"), user(ex), Json.str(Http.readJson(ex), "userId"));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/delete", (ex, p) -> {
            rooms.delete(p.get("id"), user(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/rom", (ex, p) -> {
            rooms.rom(p.get("id"), user(ex), Http.readJson(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/ready", (ex, p) -> {
            rooms.ready(p.get("id"), user(ex), Json.bool(Http.readJson(ex), "ready", true));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/launch", (ex, p) -> {
            rooms.launch(p.get("id"), user(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/adventure", (ex, p) -> {
            rooms.adventure(p.get("id"), user(ex), Json.bool(Http.readJson(ex), "open", true));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/end", (ex, p) -> {
            rooms.end(p.get("id"), user(ex), Json.str(Http.readJson(ex), "outcome"));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/events", (ex, p) -> {
            List<Object> events = Json.arr(Http.readJson(ex).get("events"));
            Http.json(ex, 200, Json.map("notices", rooms.events(p.get("id"), user(ex), events == null ? new ArrayList<>() : events)));
        });
        r.on("POST", "/api/rooms/{id}/trades", (ex, p) -> {
            rooms.trade(p.get("id"), user(ex), Http.readJson(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/trade-player", (ex, p) -> {
            rooms.tradePlayer(p.get("id"), user(ex), Http.readJson(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/name", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            rooms.name(p.get("id"), user(ex), Json.str(b, "zone"), Json.str(b, "name"));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/ack", (ex, p) -> {
            rooms.ack(p.get("id"), user(ex), Json.arr(Http.readJson(ex).get("refunds")));
            Http.ok(ex);
        });
        r.on("GET", "/api/rooms/{id}/logs", (ex, p) -> {
            Map<String, String> q = Http.query(ex);
            long after = parse(q.get("after"), 0);
            int limit = (int) Math.max(1, Math.min(5000, parse(q.get("limit"), 1000)));
            Http.json(ex, 200, Json.map("logs", rooms.logs(p.get("id"), user(ex), after, limit)));
        });
        r.on("POST", "/api/rooms/{id}/screen", (ex, p) -> {
            Accounts.User u = user(ex);
            rooms.putScreen(p.get("id"), u, Http.readBody(ex, 400_000));
            Http.ok(ex);
        });
        r.on("GET", "/api/rooms/{id}/screen/{user}", (ex, p) -> {
            Map<String, Object> s = rooms.screen(p.get("id"), user(ex), p.get("user"), parse(Http.query(ex).get("since"), 0));
            if (s == null) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            ex.getResponseHeaders().set("X-Seq", String.valueOf(s.get("seq")));
            Http.bytes(ex, 200, "image/jpeg", (byte[]) s.get("jpeg"));
        });
        r.on("POST", "/api/rooms/{id}/ghost", (ex, p) -> {
            rooms.ghost(p.get("id"), user(ex), Http.readJson(ex));
            Http.ok(ex);
        });
        r.on("GET", "/api/rooms/{id}/ghosts", (ex, p) -> Http.json(ex, 200, Json.map("ghosts", rooms.ghosts(p.get("id"), user(ex)))));
        r.on("POST", "/api/rooms/{id}/save", (ex, p) -> {
            Accounts.User u = user(ex);
            rooms.putSave(p.get("id"), u, Http.readBody(ex, 4 * 1024 * 1024), Http.query(ex).get("name"));
            Http.ok(ex);
        });
        r.on("GET", "/api/rooms/{id}/save", (ex, p) -> {
            byte[] data = rooms.getSave(p.get("id"), user(ex));
            if (data == null) {
                throw new Http.Fail(404, "Aucune sauvegarde sur le serveur pour cette salle.");
            }
            Http.bytes(ex, 200, "application/octet-stream", data);
        });
        r.on("GET", "/api/rooms/{id}/save/info", (ex, p) -> Http.json(ex, 200,
                Json.map("save", rooms.saveInfo(p.get("id"), user(ex)))));
    }

    private Accounts.User other(String id) {
        Accounts.User o = accounts.get(id);
        if (o == null) {
            throw new Http.Fail(404, "Joueur introuvable.");
        }
        return o;
    }

    private Map<String, Object> friends(Accounts.User u) {
        List<Object> friends = new ArrayList<>();
        for (String id : u.friends) {
            Accounts.User f = accounts.get(id);
            if (f != null) {
                Map<String, Object> x = f.pub();
                x.put("online", hub.online(f.id));
                friends.add(x);
            }
        }
        friends.sort((a, b) -> {
            boolean oa = Json.bool(Json.obj(a), "online", false);
            boolean ob = Json.bool(Json.obj(b), "online", false);
            if (oa != ob) {
                return oa ? -1 : 1;
            }
            return String.CASE_INSENSITIVE_ORDER.compare(Json.str(Json.obj(a), "name"), Json.str(Json.obj(b), "name"));
        });
        List<Object> incoming = new ArrayList<>();
        for (String id : u.incoming) {
            Accounts.User f = accounts.get(id);
            if (f != null) {
                incoming.add(f.pub());
            }
        }
        List<Object> outgoing = new ArrayList<>();
        for (String id : u.outgoing) {
            Accounts.User f = accounts.get(id);
            if (f != null) {
                outgoing.add(f.pub());
            }
        }
        return Json.map("friends", friends, "incoming", incoming, "outgoing", outgoing);
    }

    private static long parse(String s, long def) {
        try {
            return s == null ? def : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
