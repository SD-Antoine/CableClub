package slt.client;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import com.sun.net.httpserver.HttpServer;
import slt.Android;
import slt.Desktop;
import slt.Http;
import slt.Jobs;
import slt.Json;
import slt.Log;
import slt.Version;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The Cable Club application on a phone: the same local API, server link, ROM library and game as on the PC
 * (the interface is shown by the app's web view instead of a browser window). No hosted server on a phone.
 */
public final class ClientApp {

    final Path dataDir;
    /** The interface is packaged with the app; a debug build takes data/web-dev instead when it is there (development). */
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
    /** Proves that a "window closed" message comes from the interface (it reads it from /api/state). */
    final String uiToken = slt.Ids.token();

    private ClientApp(Path dataDir, boolean debuggable) {
        this.dataDir = dataDir;
        this.webDir = debuggable && Files.isDirectory(dataDir.resolve("web-dev")) ? dataDir.resolve("web-dev") : null;
        this.config = new Config(dataDir);
        this.link = new ServerLink(config, hub);
        this.library = new Library(dataDir);
        this.library.copiesIn(dataDir.resolve("roms"));
        this.roms = new RoomRoms(dataDir, library, link);
        this.players = new Players(this);
        this.hosting = new Hosting(dataDir, () -> { });
        this.updates = new Updates(this);
    }

    /**
     * Starts the application's side of Cable Club (local API on 127.0.0.1, link to the server).
     *
     * @return the application; its {@link #url()} is where the interface is
     */
    public static ClientApp start(Context context, Path dataDir) throws IOException {
        Android.init(context);
        Desktop.init(context);
        Files.createDirectories(dataDir);
        Log.init(dataDir);
        Log.info("Cable Club " + Version.VERSION + " (Android) — données : " + dataDir);
        // 127.0.0.1, where the interface is loaded (Android's default loopback address is ::1)
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 0), 64);
        ClientApp app = new ClientApp(dataDir, (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        LocalApi api = new LocalApi(app);
        server.createContext("/", api::handle);
        server.start();
        app.url = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
        Log.info("Interface disponible sur " + app.url);
        if (app.link.loggedIn()) {
            app.link.startStream();
        }
        app.updates.start();
        return app;
    }

    public String url() {
        return url;
    }

    String apiBase() {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** No program folder to show on a phone: the patch notes come from the app itself. */
    Path appDir() {
        return dataDir;
    }

    List<String> launchArgs() {
        return List.of();
    }

    /** The web view is the window: it never closes without the app. */
    void uiClosed() {
    }

    boolean closeWindowForRestart() {
        return false;
    }

    /** The app is leaving (the system or the player closed it): the game stops with it. */
    public void quit() {
        Log.info("Fermeture de Cable Club");
        players.stop();
    }

    /** The game is running (the screen stays on, the back button leaves the game first). */
    public boolean playing() {
        String s = Json.str(players.state(), "state");
        return "starting".equals(s) || "running".equals(s);
    }

    /** Tells the interface (as the PC's application does) through its event stream. */
    public void notifyInterface(String event, Object data) {
        hub.send(event, data);
    }

    /**
     * ROM files picked by the player in Android's file picker: copied into the app's own folder (the picker only
     * lends them), then added to the library. Runs as a job the interface follows (event "rom-import").
     */
    public void importRoms(ContentResolver resolver, List<Uri> files) {
        String job = jobs.start("Ajout des ROMs", progress -> {
            Path dir = dataDir.resolve("roms");
            Files.createDirectories(dir);
            int ok = 0;
            List<Object> skipped = new ArrayList<>();
            int i = 0;
            for (Uri uri : files) {
                i++;
                String name = displayName(resolver, uri);
                progress.accept("Copie " + i + "/" + files.size() + " : " + name);
                Path target = dir.resolve(name.replaceAll("[\\\\/:*?\"<>|]", "_"));
                try {
                    Path tmp = dir.resolve(target.getFileName() + ".copie");
                    try (InputStream in = resolver.openInputStream(uri); OutputStream out = Files.newOutputStream(tmp)) {
                        if (in == null) {
                            throw new IOException("fichier illisible");
                        }
                        byte[] buf = new byte[1 << 16];
                        for (int n; (n = in.read(buf)) > 0; ) {
                            out.write(buf, 0, n);
                        }
                    }
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                    progress.accept("Lecture " + i + "/" + files.size() + " : " + name);
                    library.add(target);
                    ok++;
                } catch (Http.Fail e) {
                    Files.deleteIfExists(target);
                    skipped.add(Json.map("file", name, "reason", e.getMessage()));
                } catch (IOException | RuntimeException e) {
                    Files.deleteIfExists(target);
                    skipped.add(Json.map("file", name, "reason", String.valueOf(e.getMessage())));
                }
            }
            return Json.map("added", (long) ok, "found", (long) files.size(), "skipped", skipped);
        });
        hub.send("rom-import", Json.map("job", job));
    }

    private static String displayName(ContentResolver resolver, Uri uri) {
        try (Cursor c = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isBlank()) {
                    return n;
                }
            }
        } catch (RuntimeException ignored) {
            // the name of the address below
        }
        String last = uri.getLastPathSegment();
        return last == null ? "rom" : last.replaceAll(".*[/:]", "");
    }

    /** What the interface needs to know about the phone (see /api/state). */
    static Map<String, Object> platform() {
        return Json.map("os", "android");
    }
}
