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
    private final Chat chat;
    private final Avatars avatars;
    private final Battles battles;
    private final Reports reports;
    private final Http.Router router = new Http.Router();

    ServerApi(SltServer server) {
        this.server = server;
        this.accounts = server.accounts;
        this.rooms = server.rooms;
        this.hub = server.hub;
        this.chat = server.chat;
        this.avatars = server.avatars;
        this.battles = server.battles;
        this.reports = server.reports;
        routes();
    }

    void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if (!path.startsWith("/api/")) {
                if (page(ex, path)) {
                    return;
                }
                Http.json(ex, 200, Json.map("app", "SLT", "role", "server", "version", SltServer.VERSION,
                        "message", "Serveur Cable Club. Connectez-vous depuis l'application Cable Club."));
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

    /** The public pages of the server (web/legal): privacy policy, terms of use, deleting an account without the app. */
    private static final Map<String, String> PAGES = Map.of(
            "/confidentialite", "confidentialite.html",
            "/conditions", "conditions.html",
            "/compte/suppression", "suppression.html");

    private boolean page(HttpExchange ex, String path) throws java.io.IOException {
        String file = PAGES.get(path.endsWith("/") && path.length() > 1 ? path.substring(0, path.length() - 1) : path);
        if (file == null && path.matches("/legal/[a-z0-9-]+\\.(css|svg|png)")) {
            file = path.substring("/legal/".length());
        }
        if (file == null || !"GET".equals(ex.getRequestMethod())) {
            return false;
        }
        byte[] data;
        try (java.io.InputStream in = ServerApi.class.getResourceAsStream("/web/legal/" + file)) {
            if (in == null) {
                return false;
            }
            data = in.readAllBytes();
        }
        String type = file.endsWith(".html") ? "text/html; charset=utf-8" : file.endsWith(".css") ? "text/css; charset=utf-8"
                : file.endsWith(".svg") ? "image/svg+xml" : "image/png";
        ex.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        Http.bytes(ex, 200, type, data);
        return true;
    }

    /**
     * Deletes an account and what it left on the server: its place in the rooms (anonymised once the adventure
     * started), its messages, its picture, the reports about it.
     */
    private void deleteAccount(Accounts.User u) {
        List<String> told = new ArrayList<>(u.friends);
        told.addAll(u.incoming);
        told.addAll(u.outgoing);
        rooms.accountDeleted(u);
        chat.accountDeleted(u.id);
        reports.forget(u.id);
        avatars.delete(u.id);
        accounts.delete(u);
        for (String f : told) {
            hub.send(f, "friends", Json.map("reason", "removed"));
        }
    }

    private Accounts.User admin(HttpExchange ex) {
        Accounts.User u = user(ex);
        if (!chat.admin(u)) {
            throw new Http.Fail(403, "Réservé aux modérateurs.");
        }
        return u;
    }

    private Accounts.User user(HttpExchange ex) {
        Accounts.User u = accounts.session(Http.bearer(ex));
        if (u == null) {
            throw new Http.Fail(401, "Session expirée : reconnectez-vous.");
        }
        return u;
    }

    private Map<String, Object> me(Accounts.User u) {
        Map<String, Object> m = u.profile();
        boolean admin = chat.admin(u);
        m.put("admin", admin);
        if (admin) {
            m.put("reports", reports.open());
        }
        return m;
    }

    private void routes() {
        Http.Router r = router;

        r.on("GET", "/api/server", (ex, p) -> Http.json(ex, 200, Json.map("app", "SLT", "role", "server",
                "version", SltServer.VERSION, "name", server.name, "users", (long) server.userCount())));

        // ------------------------------------------------------------ auth
        r.on("POST", "/api/auth/register", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            Accounts.User u = accounts.register(Json.str(b, "username"), Json.str(b, "password"), Json.str(b, "name"),
                    Json.bool(b, "terms", false));
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
            rooms.renamed(u);
            Http.json(ex, 200, me(u));
        });
        // profile: name, bio, favourite game and Pokémon (only the fields given change)
        r.on("POST", "/api/me/profile", (ex, p) -> {
            Accounts.User u = user(ex);
            accounts.updateProfile(u, Http.readJson(ex));
            rooms.renamed(u);
            Http.json(ex, 200, me(u));
        });
        // profile picture: any image (base64), stored as a 128 x 128 PNG
        r.on("POST", "/api/me/avatar", (ex, p) -> {
            Accounts.User u = user(ex);
            String data = Json.str(Http.readJson(ex), "data");
            byte[] img;
            try {
                String b64 = data == null ? "" : data.replaceFirst("^data:[^,]*,", "");
                img = java.util.Base64.getDecoder().decode(b64.trim());
            } catch (IllegalArgumentException e) {
                throw new Http.Fail(400, "Image illisible.");
            }
            avatars.put(u.id, img);
            accounts.avatarChanged(u, true);
            Http.json(ex, 200, me(u));
        });
        r.on("POST", "/api/me/avatar/remove", (ex, p) -> {
            Accounts.User u = user(ex);
            avatars.delete(u.id);
            accounts.avatarChanged(u, false);
            Http.json(ex, 200, me(u));
        });
        r.on("GET", "/api/users/{id}", (ex, p) -> {
            Accounts.User u = user(ex);
            Accounts.User o = other(p.get("id"));
            Map<String, Object> m = o.profile();
            m.put("online", hub.online(o.id));
            m.put("friend", u.friends.contains(o.id) ? "friends" : u.outgoing.contains(o.id) ? "sent"
                    : u.incoming.contains(o.id) ? "received" : o.id.equals(u.id) ? "me" : "none");
            m.put("blocked", u.blocked.contains(o.id));
            m.put("soullink", rooms.stats(o.id));
            Http.json(ex, 200, m);
        });
        r.on("GET", "/api/users/{id}/avatar", (ex, p) -> {
            user(ex);
            byte[] img = avatars.get(p.get("id"));
            if (img == null) {
                throw new Http.Fail(404, "Pas de photo.");
            }
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=86400");
            Http.bytes(ex, 200, "image/png", img);
        });
        r.on("POST", "/api/users/{id}/block", (ex, p) -> {
            Accounts.User u = user(ex);
            accounts.block(u, other(p.get("id")), Json.bool(Http.readJson(ex), "on", true));
            Http.ok(ex);
        });

        // ------------------------------------------------------------ chat
        r.on("GET", "/api/chat", (ex, p) -> Http.json(ex, 200, chat.list(user(ex))));
        r.on("POST", "/api/chat/mute", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            chat.mute(user(ex), Json.str(b, "userId"), Json.num(b, "minutes", 60));
            Http.ok(ex);
        });
        r.on("GET", "/api/chat/{ch}", (ex, p) -> {
            Map<String, String> q = Http.query(ex);
            Http.json(ex, 200, chat.history(user(ex), p.get("ch"), parse(q.get("before"), 0), (int) parse(q.get("limit"), 60)));
        });
        r.on("POST", "/api/chat/{ch}", (ex, p) -> Http.json(ex, 200,
                chat.post(user(ex), p.get("ch"), Json.str(Http.readJson(ex), "text"))));
        r.on("POST", "/api/chat/{ch}/read", (ex, p) -> {
            chat.read(user(ex), p.get("ch"), Json.num(Http.readJson(ex), "id", 0));
            Http.ok(ex);
        });
        r.on("POST", "/api/chat/{ch}/{msg}/delete", (ex, p) -> {
            chat.delete(user(ex), p.get("ch"), parse(p.get("msg"), -1));
            Http.ok(ex);
        });
        // ---------------------------------------------- account deletion
        // from the app (session + password)
        r.on("POST", "/api/me/delete", (ex, p) -> {
            Accounts.User u = user(ex);
            if (!accounts.passwordIs(u, Json.str(Http.readJson(ex), "password"))) {
                throw new Http.Fail(403, "Mot de passe incorrect.");
            }
            deleteAccount(u);
            Http.ok(ex);
        });
        // from the web page /compte/suppression (identifier + password, throttled as a login)
        r.on("POST", "/api/account/delete", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            deleteAccount(accounts.login(Json.str(b, "username"), Json.str(b, "password"), Http.clientIp(ex)));
            Http.ok(ex);
        });

        // ------------------------------------------------------ moderation
        // a player reports a message ({userId, ch, msg}) or a player ({userId})
        r.on("POST", "/api/report", (ex, p) -> {
            Accounts.User u = user(ex);
            Map<String, Object> b = Http.readJson(ex);
            Accounts.User about = other(Json.str(b, "userId"));
            if (about.id.equals(u.id)) {
                throw new Http.Fail(400, "Vous ne pouvez pas vous signaler vous-même.");
            }
            String ch = Json.str(b, "ch");
            Map<String, Object> msg = null;
            String key = null;
            if (ch != null) {
                key = chat.channelKey(u, ch);
                msg = chat.message(u, ch, Json.num(b, "msg", -1));
                if (msg == null || !about.id.equals(Json.str(msg, "from"))) {
                    throw new Http.Fail(404, "Message introuvable.");
                }
            }
            Map<String, Object> report = reports.add(u, about, key, msg, Json.str(b, "reason"));
            hub.send(chat.onlineAdmins(), "report", Json.map("open", reports.open(), "report", report));
            Http.ok(ex);
        });
        r.on("GET", "/api/admin/reports", (ex, p) -> {
            admin(ex);
            Http.json(ex, 200, Json.map("reports", reports.all()));
        });
        r.on("POST", "/api/admin/reports/{id}/done", (ex, p) -> {
            Accounts.User u = admin(ex);
            reports.done(parse(p.get("id"), -1), u, Json.bool(Http.readJson(ex), "done", true));
            hub.send(chat.onlineAdmins(), "report", Json.map("open", reports.open()));
            Http.ok(ex);
        });
        // a moderator deletes an account (serious or repeated breaches of the terms of use)
        r.on("POST", "/api/admin/users/{id}/delete", (ex, p) -> {
            Accounts.User u = admin(ex);
            Accounts.User target = other(p.get("id"));
            if (target.id.equals(u.id)) {
                throw new Http.Fail(400, "Supprimez votre propre compte depuis votre profil.");
            }
            Log.info("Serveur : " + u.username + " supprime le compte " + target.username + " (modération)");
            deleteAccount(target);
            Http.ok(ex);
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
        // public rooms: ?mode=soullink&q=words&size=1..4&full=1
        r.on("GET", "/api/rooms/public", (ex, p) -> {
            Map<String, String> q = Http.query(ex);
            Http.json(ex, 200, Json.map("rooms", rooms.publicRooms(user(ex), q.get("mode"), q.get("q"),
                    (int) parse(q.get("size"), 0), "1".equals(q.get("full")))));
        });
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
        // battles between the players of a room (simulated by their interfaces; see Battles)
        r.on("GET", "/api/rooms/{id}/battles", (ex, p) -> Http.json(ex, 200,
                Json.map("battles", battles.list(p.get("id"), user(ex)))));
        r.on("GET", "/api/rooms/{id}/battles/{battle}", (ex, p) -> Http.json(ex, 200,
                battles.one(p.get("id"), p.get("battle"), user(ex))));
        r.on("POST", "/api/rooms/{id}/battles", (ex, p) -> Http.json(ex, 200,
                battles.act(p.get("id"), user(ex), Http.readJson(ex))));
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
