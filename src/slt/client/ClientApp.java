package slt.client;

import com.sun.net.httpserver.HttpServer;
import slt.Desktop;
import slt.Jobs;
import slt.Json;
import slt.Log;
import slt.Version;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * The SLT application of one player: local API + interface (shown in an
 * application window), ROM library, connection to the SLT server, game
 * player, and optionally the SLT server itself.
 */
public final class ClientApp {

    static final int DEFAULT_PORT = 47331;

    final Path dataDir;
    final Path webDir;
    final Config config;
    final LocalHub hub = new LocalHub();
    final ServerLink link;
    final Library library;
    final RoomRoms roms;
    final Players players;
    final Hosting hosting;
    final Jobs jobs = new Jobs();
    final Updates updates;
    private String url;
    /** Arguments SLT was started with (given again when it restarts after an update). */
    private static List<String> launchArgs = List.of();
    /** The application window when SLT owns it (dedicated Edge/Chrome process). */
    private volatile Process window;
    private volatile boolean restarting;
    /** SLT opened a window for the player: closing it closes everything. */
    private volatile boolean windowed;
    /** Proves that a "window closed" message comes from the interface (it reads it from /api/state). */
    final String uiToken = slt.Ids.token();

    private ClientApp(Path dataDir, Path webDir) {
        this.dataDir = dataDir;
        this.webDir = webDir;
        this.config = new Config(dataDir);
        this.link = new ServerLink(config, hub);
        this.library = new Library(dataDir);
        this.roms = new RoomRoms(dataDir, library, link);
        this.players = new Players(this);
        this.hosting = new Hosting(dataDir, this::hostChanged);
        this.updates = new Updates(this);
    }

    /** The interface shows the hosted server's state live (box, firewall, invitation code). */
    private void hostChanged() {
        hub.send("host", hosting.status());
    }

    String apiBase() {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Folder of SLT.exe (packaged) or the working folder (development). */
    Path appDir() {
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            return Path.of(appPath).toAbsolutePath().getParent();
        }
        return Path.of("").toAbsolutePath();
    }

    List<String> launchArgs() {
        return launchArgs;
    }

    public static void main(String[] args) throws Exception {
        launchArgs = List.of(args);
        boolean dev = Arrays.asList(args).contains("--dev");
        boolean noWindow = Arrays.asList(args).contains("--no-window");
        // restarted by an update while the old window stayed open (it reloads itself)
        boolean noOpen = Arrays.asList(args).contains("--no-open");
        int port = DEFAULT_PORT;
        String forcedData = null;
        for (String a : args) {
            if (a.startsWith("--port=")) {
                port = Integer.parseInt(a.substring(7));
            } else if (a.startsWith("--data=")) {
                forcedData = a.substring(7);
            }
        }
        Path dataDir = forcedData != null ? Path.of(forcedData).toAbsolutePath() : resolveDataDir(dev);
        Files.createDirectories(dataDir);
        Log.init(dataDir);
        Log.info("SLT " + Version.VERSION + " — données : " + dataDir);

        Path webDir = null;
        if (dev) {
            webDir = Path.of("web").toAbsolutePath().normalize();
        } else if (System.getProperty("slt.web") != null) {
            webDir = Path.of(System.getProperty("slt.web")).toAbsolutePath().normalize();
        }

        HttpServer server = tryBind(port);
        if (server == null) {
            if (isSlt(port)) {
                Log.info("SLT est déjà lancé : ouverture d'une nouvelle fenêtre.");
                if (!noWindow) {
                    Desktop.openAppWindow("http://127.0.0.1:" + port + "/", dataDir.resolve("navigateur"));
                }
                return;
            }
            server = tryBind(0);
            if (server == null) {
                throw new IOException("Impossible d'ouvrir un port local");
            }
        }

        ClientApp app = new ClientApp(dataDir, webDir);
        LocalApi api = new LocalApi(app);
        server.createContext("/", api::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        app.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Log.info("Interface disponible sur " + app.url);

        Map<String, Object> host = Json.obj(app.config.get("host"));
        if (Json.bool(host, "enabled", false)) {
            try {
                app.hosting.start((int) Json.num(host, "port", slt.server.SltServer.DEFAULT_PORT), Json.str(host, "name"),
                        Json.bool(host, "internet", false));
                // this application talks to its own server through the encrypted address (keeps the session)
                if (app.link.isLoopback(app.link.base(), app.hosting.port())) {
                    app.link.useLocal(app.hosting.localUrl(), app.hosting.fingerprint());
                }
            } catch (RuntimeException e) {
                Log.warn("Le serveur hébergé n'a pas pu redémarrer : " + e.getMessage());
            }
        }
        if (app.link.loggedIn()) {
            app.link.startStream();
        }
        app.updates.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            app.players.stop();
            app.hosting.stop();
        }, "slt-shutdown"));

        if (!noWindow) {
            app.windowed = true;
            app.watchInterface();
            Desktop.installTray(app::openWindow, () -> {
                try {
                    Desktop.reveal(app.dataDir.toString());
                } catch (IOException e) {
                    Log.warn(e.getMessage());
                }
            }, app::quit);
            if (!noOpen) {
                app.openWindow();
            }
        }
    }

    private void openWindow() {
        Process window = Desktop.openAppWindow(url, dataDir.resolve("navigateur"));
        if (window == null) {
            return;
        }
        this.window = window;
        long started = System.currentTimeMillis();
        Thread watcher = new Thread(() -> {
            try {
                window.waitFor();
            } catch (InterruptedException e) {
                return;
            }
            // a very short-lived process handed the window to a running browser: it cannot be tracked
            // (the interface says when it closes, see uiClosed)
            if (System.currentTimeMillis() - started < 8000) {
                Log.info("Fenêtre confiée à un navigateur déjà ouvert : suivie par l'interface");
                if (this.window == window) {
                    this.window = null;
                }
                return;
            }
            if (restarting) {
                return;
            }
            // closing the window closes everything (game, hosted server), even when it was minimized
            Log.info("Fenêtre fermée");
            quit();
        }, "slt-window-watch");
        watcher.setDaemon(true);
        watcher.start();
    }

    /**
     * The interface says its window is going away (closed, or reloaded). The
     * browser process cannot always be followed (handed to another one, kept
     * running in the background): if no interface comes back within a few
     * seconds, SLT closes.
     */
    void uiClosed() {
        if (!windowed || restarting) {
            return;
        }
        long before = hub.opened();
        Thread t = new Thread(() -> {
            // a stream of a page that closed without saying so is only noticed at its next ping (15 s)
            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    return;
                }
                if (restarting || (hub.opened() != before && hub.size() > 0)) {
                    return; // reloaded: the page is back
                }
                if (hub.size() == 0) {
                    Log.info("Fenêtre fermée (plus aucune interface ouverte)");
                    quit();
                    return;
                }
            }
        }, "slt-window-closed");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Last resort when the window could not be followed and did not say it closed (browser killed…): no
     * interface for 30 seconds after there was one closes SLT.
     */
    private void watchInterface() {
        Thread t = new Thread(() -> {
            boolean had = false;
            long emptySince = -1;
            while (true) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    return;
                }
                if (hub.size() > 0) {
                    had = true;
                    emptySince = -1;
                } else if (had && !restarting) {
                    long now = System.currentTimeMillis();
                    if (emptySince < 0) {
                        emptySince = now;
                    } else if (now - emptySince >= 30_000) {
                        Log.info("Plus aucune fenêtre ouverte depuis 30 s");
                        quit();
                        return;
                    }
                }
            }
        }, "slt-interface-watch");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Closes the application window before SLT restarts on a new version (the
     * new SLT opens a fresh one).
     *
     * @return false when SLT does not own the window (it stays open)
     */
    boolean closeWindowForRestart() {
        restarting = true;
        Process w = window;
        if (w == null || !w.isAlive()) {
            return false;
        }
        try {
            // without /F: the browser gets a normal close request
            new ProcessBuilder("taskkill", "/PID", Long.toString(w.pid())).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return true;
        } catch (IOException e) {
            Log.warn("Fermeture de la fenêtre impossible : " + e.getMessage());
            return false;
        }
    }

    void quit() {
        Log.info("Fermeture de SLT");
        players.stop();
        hosting.stop();
        Desktop.removeTray();
        System.exit(0);
    }

    private static HttpServer tryBind(int port) {
        try {
            return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 64);
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean isSlt(int port) {
        try {
            HttpClient c = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> r = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/state"))
                    .header("X-SLT", "1").timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            return r.statusCode() == 200 && r.body().contains("\"app\":\"SLT\"");
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Portable by default: data lives next to SLT.exe in "data". Falls back to
     * %LOCALAPPDATA%\SLT when that folder is not writable.
     */
    private static Path resolveDataDir(boolean dev) {
        String forced = System.getProperty("slt.data");
        if (forced != null) {
            return Path.of(forced).toAbsolutePath();
        }
        if (dev) {
            return Path.of("data").toAbsolutePath();
        }
        String appPath = System.getProperty("jpackage.app-path");
        if (appPath != null) {
            Path candidate = Path.of(appPath).toAbsolutePath().getParent().resolve("data");
            if (writable(candidate)) {
                return candidate;
            }
        } else if (writable(Path.of("data").toAbsolutePath())) {
            return Path.of("data").toAbsolutePath();
        }
        String local = System.getenv("LOCALAPPDATA");
        return local != null ? Path.of(local, "SLT") : Path.of(System.getProperty("user.home"), ".slt");
    }

    private static boolean writable(Path dir) {
        try {
            Files.createDirectories(dir);
            Path probe = Files.createTempFile(dir, "probe", ".tmp");
            Files.delete(probe);
            return true;
        } catch (IOException | SecurityException e) {
            return false;
        }
    }
}
