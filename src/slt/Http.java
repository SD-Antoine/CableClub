package slt;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** HTTP helpers and a tiny path router on top of com.sun.net.httpserver. */
public final class Http {

    private Http() {
    }

    /** Thrown by handlers to answer with an error status and message. */
    public static final class Fail extends RuntimeException {
        public final int status;

        public Fail(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    @FunctionalInterface
    public interface Handler {
        void handle(HttpExchange ex, Map<String, String> params) throws Exception;
    }

    public static final class Router {
        private static final class Route {
            final String method;
            final String[] parts;
            final Handler handler;

            Route(String method, String pattern, Handler handler) {
                this.method = method;
                this.parts = split(pattern);
                this.handler = handler;
            }
        }

        private final List<Route> routes = new ArrayList<>();

        public Router on(String method, String pattern, Handler handler) {
            routes.add(new Route(method, pattern, handler));
            return this;
        }

        /** @return true if a route matched and was executed. */
        public boolean dispatch(HttpExchange ex, String path) throws Exception {
            String[] parts = split(path);
            boolean pathMatched = false;
            for (Route r : routes) {
                Map<String, String> params = match(r.parts, parts);
                if (params == null) {
                    continue;
                }
                pathMatched = true;
                if (!r.method.equals(ex.getRequestMethod())) {
                    continue;
                }
                r.handler.handle(ex, params);
                return true;
            }
            if (pathMatched) {
                throw new Fail(405, "Méthode non autorisée");
            }
            return false;
        }

        private static Map<String, String> match(String[] pattern, String[] parts) {
            if (pattern.length != parts.length) {
                return null;
            }
            Map<String, String> params = new HashMap<>();
            for (int i = 0; i < pattern.length; i++) {
                String p = pattern[i];
                if (p.startsWith("{") && p.endsWith("}")) {
                    params.put(p.substring(1, p.length() - 1), parts[i]);
                } else if (!p.equals(parts[i])) {
                    return null;
                }
            }
            return params;
        }

        private static String[] split(String path) {
            String p = path;
            while (p.startsWith("/")) {
                p = p.substring(1);
            }
            while (p.endsWith("/")) {
                p = p.substring(0, p.length() - 1);
            }
            if (p.isEmpty()) {
                return new String[0];
            }
            String[] raw = p.split("/");
            for (int i = 0; i < raw.length; i++) {
                raw[i] = URLDecoder.decode(raw[i], StandardCharsets.UTF_8);
            }
            return raw;
        }
    }

    // ------------------------------------------------------------- responses

    public static void json(HttpExchange ex, int status, Object body) throws IOException {
        bytes(ex, status, "application/json; charset=utf-8", Json.stringify(body).getBytes(StandardCharsets.UTF_8));
    }

    public static void ok(HttpExchange ex) throws IOException {
        json(ex, 200, Json.map("ok", true));
    }

    public static void bytes(HttpExchange ex, int status, String contentType, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        if ("HEAD".equals(ex.getRequestMethod())) {
            ex.sendResponseHeaders(status, -1);
            ex.close();
            return;
        }
        ex.sendResponseHeaders(status, data.length == 0 ? -1 : data.length);
        if (data.length > 0) {
            try (OutputStream os = ex.getResponseBody()) {
                os.write(data);
            }
        }
        ex.close();
    }

    public static void error(HttpExchange ex, int status, String message) {
        try {
            json(ex, status, Json.map("error", message == null ? "Erreur inconnue" : message));
        } catch (IOException ignored) {
            ex.close();
        }
    }

    // --------------------------------------------------------------- requests

    public static byte[] readBody(HttpExchange ex, int maxBytes) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > maxBytes) {
                    throw new Fail(413, "Requête trop volumineuse");
                }
            }
            return out.toByteArray();
        }
    }

    public static Map<String, Object> readJson(HttpExchange ex) throws IOException {
        byte[] data = readBody(ex, 32 * 1024 * 1024);
        if (data.length == 0) {
            return new HashMap<>();
        }
        try {
            return Json.parseObject(new String(data, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new Fail(400, "JSON invalide : " + e.getMessage());
        }
    }

    /** Session token: "Authorization: Bearer …" header, or ?token= (event streams cannot send headers). */
    public static String bearer(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            String t = h.substring(7).trim();
            return t.isEmpty() ? null : t;
        }
        String q = query(ex).get("token");
        return q == null || q.isBlank() ? null : q;
    }

    /** Address of the client (for rate limiting). */
    public static String clientIp(HttpExchange ex) {
        return ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
    }

    public static Map<String, String> query(HttpExchange ex) {
        Map<String, String> q = new HashMap<>();
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return q;
        }
        for (String pair : raw.split("&")) {
            int i = pair.indexOf('=');
            String k = i < 0 ? pair : pair.substring(0, i);
            String v = i < 0 ? "" : pair.substring(i + 1);
            q.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return q;
    }

    // ----------------------------------------------------------------- static

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("js", "text/javascript; charset=utf-8"),
            Map.entry("mjs", "text/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("txt", "text/plain; charset=utf-8"));

    public static String mimeFor(String path) {
        int dot = path.lastIndexOf('.');
        String ext = dot < 0 ? "" : path.substring(dot + 1).toLowerCase();
        return MIME.getOrDefault(ext, "application/octet-stream");
    }

    /**
     * Serves the web UI either from a directory on disk (dev mode) or from the
     * /web resources packaged in the jar.
     */
    public static void serveStatic(HttpExchange ex, Path webDir) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/") || path.isEmpty()) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            error(ex, 400, "Chemin invalide");
            return;
        }
        byte[] data = null;
        if (webDir != null) {
            Path f = webDir.resolve(path.substring(1)).normalize();
            if (f.startsWith(webDir) && Files.isRegularFile(f)) {
                data = Files.readAllBytes(f);
            }
        } else {
            try (InputStream in = Http.class.getResourceAsStream("/web" + path)) {
                if (in != null) {
                    data = in.readAllBytes();
                }
            }
        }
        if (data == null) {
            error(ex, 404, "Introuvable : " + path);
            return;
        }
        bytes(ex, 200, mimeFor(path), data);
    }
}
