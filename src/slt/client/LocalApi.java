package slt.client;

import com.sun.net.httpserver.HttpExchange;
import slt.Desktop;
import slt.FileBrowser;
import slt.Http;
import slt.Json;
import slt.Log;
import slt.Sse;
import slt.Version;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Local API (127.0.0.1 only) used by the SLT interface and by the game
 * player process. Server calls are relayed with the session token, which
 * never reaches the interface.
 */
final class LocalApi {

    private final ClientApp app;
    private final Http.Router router = new Http.Router();
    /** ROM preparation running for a room (one at a time). */
    private final Map<String, String> preparing = new java.util.HashMap<>();

    LocalApi(ClientApp app) {
        this.app = app;
        routes();
    }

    void handle(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if (!path.startsWith("/api/")) {
                Http.serveStatic(ex, app.webDir);
                return;
            }
            boolean player = path.startsWith("/api/player/");
            if (player) {
                if (!app.players.checkSecret(ex.getRequestHeaders().getFirst("X-SLT-Player"))) {
                    throw new Http.Fail(403, "Lecteur inconnu");
                }
            } else if (!"1".equals(ex.getRequestHeaders().getFirst("X-SLT")) && !openGet(ex, path)
                    && !("POST".equals(ex.getRequestMethod()) && path.equals("/api/ui/bye"))) {
                // a custom header cannot be sent cross-site without a preflight we never accept
                throw new Http.Fail(403, "Requête refusée");
            }
            if (!router.dispatch(ex, path)) {
                Http.error(ex, 404, "Route inconnue : " + path);
            }
        } catch (Http.Fail f) {
            Http.error(ex, f.status, f.getMessage());
        } catch (Exception e) {
            Log.error("Erreur sur " + ex.getRequestMethod() + " " + path, e);
            Http.error(ex, 500, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private Map<String, Object> state() {
        return Json.map("app", "SLT", "version", Version.VERSION, "config", app.config.publicView(),
                "link", app.link.status(), "host", app.hosting.status(), "player", app.players.state(),
                "dataDir", app.dataDir.toString(), "update", app.updates.status(), "uiToken", app.uiToken,
                "platform", ClientApp.platform());
    }

    private void routes() {
        Http.Router r = router;

        r.on("GET", "/api/state", (ex, p) -> Http.json(ex, 200, state()));
        r.on("POST", "/api/config", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            for (Map.Entry<String, Object> e : b.entrySet()) {
                if (Config.UI_KEYS.contains(e.getKey())) {
                    app.config.set(e.getKey(), e.getValue());
                }
            }
            Http.json(ex, 200, state());
        });
        r.on("GET", "/api/update", (ex, p) -> Http.json(ex, 200, app.updates.status()));
        // the patch notes of every version (the interface shows the one installed)
        r.on("GET", "/api/changelog", (ex, p) -> Http.json(ex, 200, Changelog.view(app.appDir())));
        r.on("POST", "/api/update/check", (ex, p) -> Http.json(ex, 200, app.updates.check()));
        r.on("POST", "/api/update/install", (ex, p) ->
                Http.json(ex, 200, Json.map("job", app.jobs.start("Mise à jour de Cable Club", app.updates::install))));
        // a page of the web, in the browser: the legal pages of the server in use, the Cable Club site
        r.on("POST", "/api/open", (ex, p) -> {
            String base = app.link.base() != null ? app.link.base() : "https://play.cableclub.app";
            String page = Json.str(Http.readJson(ex), "page");
            Desktop.browse(switch (page == null ? "" : page) {
                case "confidentialite" -> base + "/confidentialite";
                case "conditions" -> base + "/conditions";
                case "suppression" -> base + "/compte/suppression";
                case "site" -> "https://cableclub.app";
                case "source" -> "https://github.com/SD-Antoine/CableClub";
                default -> throw new Http.Fail(400, "Page inconnue.");
            });
            Http.ok(ex);
        });
        r.on("POST", "/api/update/page", (ex, p) -> {
            Map<String, Object> u = app.updates.status();
            Desktop.browse(u.get("page") instanceof String page && page.startsWith("https://github.com/") ? page : Updates.PAGE);
            Http.ok(ex);
        });
        r.on("GET", "/api/stream", (ex, p) -> {
            String page = Http.query(ex).getOrDefault("page", "").replaceAll("[^A-Za-z0-9]", "");
            String id = page.length() > 40 ? page.substring(0, 40) : page;
            Sse.serve(ex, Json.stringify(state()), s -> app.hub.add(s, id), app.hub::remove);
        });
        // the window is closing (sent with sendBeacon: no custom header, so the token proves where it comes from)
        r.on("POST", "/api/ui/bye", (ex, p) -> {
            Map<String, Object> b = Json.parseObject(new String(Http.readBody(ex, 4096), java.nio.charset.StandardCharsets.UTF_8));
            if (app.uiToken.equals(Json.str(b, "t"))) {
                app.hub.closePage(Json.str(b, "page"));
                app.uiClosed();
            }
            Http.ok(ex);
        });

        // ----------------------------------------------------- server & session
        r.on("POST", "/api/server/use", (ex, p) -> {
            Map<String, Object> info = app.link.useServer(Json.str(Http.readJson(ex), "address"));
            Http.json(ex, 200, Json.map("server", info, "state", state()));
        });
        r.on("POST", "/api/host/start", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            int port = (int) Json.num(b, "port", slt.server.SltServer.DEFAULT_PORT);
            boolean internet = Json.bool(b, "internet", false);
            app.hosting.start(port, Json.str(b, "name"), internet);
            app.config.set("host", Json.map("enabled", true, "port", (long) port, "name", Json.str(b, "name"), "internet", internet));
            if (app.link.base() == null || Json.bool(b, "use", true)) {
                app.link.useLocal(app.hosting.localUrl(), app.hosting.fingerprint());
            }
            Http.json(ex, 200, state());
        });
        r.on("POST", "/api/host/internet", (ex, p) -> {
            boolean on = Json.bool(Http.readJson(ex), "on", false);
            app.hosting.setInternet(on);
            Map<String, Object> host = Json.obj(Json.copy(app.config.get("host")));
            if (host != null) {
                host.put("internet", on);
                app.config.set("host", host);
            }
            Http.json(ex, 200, state());
        });
        r.on("POST", "/api/host/firewall", (ex, p) -> {
            app.hosting.allowFirewall();
            Http.json(ex, 200, state());
        });
        r.on("POST", "/api/host/stop", (ex, p) -> {
            app.hosting.stop();
            app.config.set("host", Json.map("enabled", false));
            Http.json(ex, 200, state());
        });
        r.on("POST", "/api/auth/login", (ex, p) -> Http.json(ex, 200, app.link.login("/api/auth/login", Http.readJson(ex))));
        r.on("POST", "/api/auth/register", (ex, p) -> Http.json(ex, 200, app.link.login("/api/auth/register", Http.readJson(ex))));
        // the account is deleted on the server (password checked there), then this app forgets the session
        r.on("POST", "/api/account/delete", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            app.link.call("POST", "/api/me/delete", Json.map("password", Json.str(b, "password")));
            app.players.stop();
            app.link.logout();
            Http.json(ex, 200, state());
        });
        r.on("POST", "/api/auth/logout", (ex, p) -> {
            app.players.stop();
            app.link.logout();
            Http.json(ex, 200, state());
        });
        r.on("POST", "/api/me", (ex, p) -> {
            Object me = app.link.call("POST", "/api/me", Http.readJson(ex));
            app.config.set("user", me);
            Http.json(ex, 200, me);
        });
        // profile changes: the answer is the new "me", kept here for the rail and the next launches
        for (String path : List.of("/api/me/profile", "/api/me/avatar", "/api/me/avatar/remove")) {
            r.on("POST", path, (ex, p) -> {
                Object me = app.link.call("POST", path, Http.readJson(ex));
                app.config.set("user", me);
                app.hub.send("link", app.link.status());
                Http.json(ex, 200, me);
            });
        }
        // profile pictures (<img> cannot send headers: an open GET, the version in the address keeps it fresh)
        r.on("GET", "/api/avatar/{id}", (ex, p) -> {
            var res = app.link.getResponse("/api/users/" + p.get("id").replaceAll("[^A-Za-z0-9]", "") + "/avatar");
            if (res == null) {
                throw new Http.Fail(404, "Pas de photo");
            }
            ex.getResponseHeaders().set("Cache-Control", "private, max-age=604800");
            Http.bytes(ex, 200, "image/png", res.body());
        });
        r.on("POST", "/api/me/password", (ex, p) -> {
            Map<String, Object> res = Json.obj(app.link.call("POST", "/api/me/password", Http.readJson(ex)));
            app.config.set("token", Json.str(res, "token"));
            app.link.startStream();
            Http.ok(ex);
        });

        // relay of the server API: /api/srv/<path> -> server /api/<path>
        for (String method : List.of("GET", "POST")) {
            for (String pattern : List.of("/api/srv/{a}", "/api/srv/{a}/{b}", "/api/srv/{a}/{b}/{c}", "/api/srv/{a}/{b}/{c}/{d}")) {
                r.on(method, pattern, (ex, p) -> {
                    String rest = ex.getRequestURI().getRawPath().substring("/api/srv".length());
                    String q = ex.getRequestURI().getRawQuery();
                    Object body = "GET".equals(method) ? null : Http.readJson(ex);
                    Http.json(ex, 200, app.link.call(method, "/api" + rest + (q == null ? "" : "?" + q), body));
                });
            }
        }
        // live image of a partner's game
        r.on("GET", "/api/live/{room}/{user}", (ex, p) -> {
            String since = Http.query(ex).getOrDefault("since", "0");
            var res = app.link.getResponse("/api/rooms/" + p.get("room") + "/screen/" + p.get("user") + "?since="
                    + since.replaceAll("\\D", ""));
            if (res == null) {
                ex.sendResponseHeaders(204, -1);
                ex.close();
                return;
            }
            String seq = res.header("X-Seq");
            ex.getResponseHeaders().set("X-Seq", seq == null ? "0" : seq);
            Http.bytes(ex, 200, "image/jpeg", res.body());
        });

        // ------------------------------------------------------------ library
        r.on("GET", "/api/library", (ex, p) -> Http.json(ex, 200, Json.map("roms", app.library.list())));
        r.on("POST", "/api/library/add", (ex, p) -> Http.json(ex, 200,
                app.library.add(Path.of(require(Json.str(Http.readJson(ex), "path"))))));
        r.on("POST", "/api/library/scan", (ex, p) -> {
            Path dir = Path.of(require(Json.str(Http.readJson(ex), "path")));
            Http.json(ex, 200, Json.map("job", app.jobs.start("Ajout des ROMs", progress -> app.library.scan(dir, progress))));
        });
        r.on("POST", "/api/library/{id}/remove", (ex, p) -> {
            app.library.remove(p.get("id"));
            Http.ok(ex);
        });
        r.on("GET", "/api/fs/places", (ex, p) -> Http.json(ex, 200, Json.map("places", FileBrowser.places(app.appDir()))));
        r.on("GET", "/api/fs/list", (ex, p) -> {
            Map<String, String> q = Http.query(ex);
            Set<String> exts = "1".equals(q.get("roms")) ? FileBrowser.ROM_EXTENSIONS : Set.of();
            Http.json(ex, 200, FileBrowser.list(q.get("path"), exts));
        });
        r.on("GET", "/api/jobs/{id}", (ex, p) -> Http.json(ex, 200, app.jobs.status(p.get("id"))));

        // ---------------------------------------------------------- room ROMs
        r.on("GET", "/api/rooms/{id}/local", (ex, p) -> {
            String id = p.get("id");
            Map<String, Object> rom = app.roms.status(id);
            Map<String, Object> st = app.players.state();
            Http.json(ex, 200, Json.map("rom", rom, "player", id.equals(Json.str(st, "roomId")) ? st : null,
                    "save", Files.isRegularFile(app.players.saveFile(id))));
        });
        r.on("POST", "/api/rooms/{id}/prepare", (ex, p) -> {
            String id = p.get("id");
            String job;
            synchronized (preparing) {
                String running = preparing.get(id);
                if (running != null && "running".equals(app.jobs.status(running).get("state"))) {
                    job = running;
                } else {
                    job = app.jobs.start("Préparation de la ROM", progress -> {
                        app.hub.send("rom", Json.map("roomId", id, "state", "building"));
                        try {
                            Map<String, Object> res = app.roms.prepare(id, msg -> {
                                progress.accept(msg);
                                app.hub.send("rom", Json.map("roomId", id, "state", "building", "message", msg));
                            });
                            app.hub.send("rom", Json.map("roomId", id, "state", "ready"));
                            return res;
                        } catch (Exception e) {
                            app.hub.send("rom", Json.map("roomId", id, "state", "error", "message", e.getMessage()));
                            throw e;
                        }
                    });
                    preparing.put(id, job);
                }
            }
            Http.json(ex, 200, Json.map("job", job));
        });
        r.on("GET", "/api/rooms/{id}/rom/file", (ex, p) -> {
            Map<String, Object> rom = app.roms.status(p.get("id"));
            if (rom == null || !Json.bool(rom, "present", false)) {
                throw new Http.Fail(404, "ROM non générée sur ce PC.");
            }
            Path f = Path.of(Json.str(rom, "path"));
            String ext = f.getFileName().toString().replaceAll(".*\\.", "");
            String name = "CableClub-" + Json.str(rom, "gameId") + "-" + p.get("id") + "." + ext;
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + name + "\"");
            Http.bytes(ex, 200, "application/octet-stream", Files.readAllBytes(f));
        });
        // names of the room's game in its language (species, moves, abilities, items), read from its ROM
        r.on("GET", "/api/rooms/{id}/names", (ex, p) -> {
            Map<String, Object> rom = app.roms.status(p.get("id"));
            if (rom == null || !Json.bool(rom, "present", false)) {
                throw new Http.Fail(404, "ROM non générée sur ce PC.");
            }
            Path info = Rando.trackerInfo(Json.str(rom, "path"), app.dataDir.resolve("cache"));
            Map<String, Object> o = Json.parseObject(Files.readString(info));
            Http.json(ex, 200, Json.map("gen", o.get("gen"), "species", o.get("species"), "moves", o.get("moves"),
                    "abilities", o.get("abilities"), "items", o.get("items")));
        });
        r.on("POST", "/api/rooms/{id}/rom/reveal", (ex, p) -> {
            Map<String, Object> rom = app.roms.status(p.get("id"));
            if (rom == null) {
                throw new Http.Fail(404, "ROM non générée sur ce PC.");
            }
            Desktop.reveal(Json.str(rom, "path"));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/save/restore", (ex, p) -> {
            app.players.restoreSave(p.get("id"));
            Http.ok(ex);
        });
        r.on("POST", "/api/rooms/{id}/play", (ex, p) -> Http.json(ex, 200, app.players.launch(p.get("id"))));
        r.on("POST", "/api/game/stop", (ex, p) -> {
            app.players.stop();
            Http.ok(ex);
        });
        r.on("POST", "/api/data/reveal", (ex, p) -> {
            Desktop.reveal(app.dataDir.toString());
            Http.ok(ex);
        });

        // ------------------------------------------------- the game player
        r.on("POST", "/api/player/status", (ex, p) -> {
            app.players.status(Http.readJson(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/player/events", (ex, p) -> {
            app.players.events(Json.arr(Http.readJson(ex).get("events")));
            Http.ok(ex);
        });
        r.on("GET", "/api/player/directives", (ex, p) -> Http.json(ex, 200, app.players.directives()));
        r.on("POST", "/api/player/ack", (ex, p) -> {
            app.players.ack(Json.arr(Http.readJson(ex).get("refunds")));
            Http.ok(ex);
        });
        r.on("POST", "/api/player/screen", (ex, p) -> {
            app.players.screen(Http.readBody(ex, 400_000));
            Http.ok(ex);
        });
        r.on("POST", "/api/player/ghost", (ex, p) -> {
            app.players.ghost(Http.readJson(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/player/trade", (ex, p) -> {
            app.players.trade(Http.readJson(ex));
            Http.ok(ex);
        });
        r.on("POST", "/api/player/saved", (ex, p) -> {
            app.players.saved();
            Http.ok(ex);
        });
        r.on("POST", "/api/player/notice", (ex, p) -> {
            Map<String, Object> b = Http.readJson(ex);
            b.put("local", true);
            app.hub.send("notice", b);
            Http.ok(ex);
        });
    }

    /** Reads the interface needs without custom headers: the event stream and the ROM download link. */
    private static boolean openGet(HttpExchange ex, String path) {
        return "GET".equals(ex.getRequestMethod())
                && (path.equals("/api/stream") || path.matches("/api/rooms/[a-z0-9]+/rom/file")
                || path.matches("/api/avatar/[A-Za-z0-9]+"));
    }

    private static String require(String v) {
        if (v == null || v.isBlank()) {
            throw new Http.Fail(400, "Chemin manquant");
        }
        return v;
    }
}
