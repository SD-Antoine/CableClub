package slt.client;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import slt.Android;
import slt.Http;
import slt.Ids;
import slt.Json;
import slt.Log;
import slt.Net;
import slt.Version;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Updates of Cable Club for Android from the GitHub releases: the latest release is checked at start then every
 * few hours; "Mettre à jour" downloads its APK and hands it to Android's installer, which asks the player to
 * confirm (the data of the app stays).
 */
final class Updates {

    static final String REPO = "SD-Antoine/CableClub";
    static final String PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String API = System.getProperty("slt.updates.api", "https://api.github.com/repos/" + REPO + "/releases/latest");
    private static final long PERIOD_MS = 6 * 3600_000L;
    /** Broadcast of Android's installer with the result of the installation. */
    static final String INSTALLED = "app.cableclub.INSTALLATION";

    private final ClientApp app;
    private final Net http = Net.client(null, null, 10_000);
    private volatile Map<String, Object> status = Json.map("state", "idle", "current", Version.VERSION);
    private volatile Release latest;
    private volatile boolean installing;

    private record Release(String version, String notes, String page, String apkUrl, long apkSize, String shaUrl) {
    }

    Updates(ClientApp app) {
        this.app = app;
    }

    Map<String, Object> status() {
        return status;
    }

    /** Checks now and then every few hours (when enabled in the settings). */
    void start() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(8000);
                for (;;) {
                    if (!Boolean.FALSE.equals(app.config.get("updateCheck"))) {
                        check();
                    }
                    Thread.sleep(PERIOD_MS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "slt-updates");
        t.setDaemon(true);
        t.start();
    }

    /** Asks GitHub for the latest release. Never throws: the status says what happened. */
    synchronized Map<String, Object> check() {
        if (installing) {
            return status;
        }
        try {
            Net.Response r = http.send("GET", API, Map.of("Accept", "application/vnd.github+json", "User-Agent", "CableClub-Android/" + Version.VERSION),
                    null, 20_000);
            if (r.status() == 404) {
                publish(Json.map("state", "uptodate", "current", Version.VERSION, "checkedAt", System.currentTimeMillis()));
                return status;
            }
            if (r.status() != 200) {
                throw new IOException("GitHub a répondu " + r.status());
            }
            Map<String, Object> rel = Json.parseObject(r.text());
            String version = Json.str(rel, "tag_name");
            version = version == null ? "" : version.replaceFirst("^[vV]", "");
            String apkUrl = null;
            String apkName = null;
            String shaUrl = null;
            long apkSize = 0;
            List<Object> assets = Json.arr(rel.get("assets"));
            for (Object o : assets == null ? List.of() : assets) {
                Map<String, Object> a = Json.obj(o);
                String name = Json.str(a, "name");
                if (name != null && name.matches("CableClub-.*-android\\.apk")) {
                    apkName = name;
                    apkUrl = Json.str(a, "browser_download_url");
                    apkSize = Json.num(a, "size", 0);
                }
            }
            for (Object o : assets == null ? List.of() : assets) {
                Map<String, Object> a = Json.obj(o);
                if (apkName != null && (apkName + ".sha256").equals(Json.str(a, "name"))) {
                    shaUrl = Json.str(a, "browser_download_url");
                }
            }
            String page = Json.str(rel, "html_url");
            latest = new Release(version, Json.str(rel, "body"), page != null ? page : PAGE, apkUrl, apkSize, shaUrl);
            if (compare(version, Version.VERSION) > 0) {
                publish(Json.map("state", "available", "current", Version.VERSION, "version", version,
                        "notes", latest.notes(), "page", latest.page(), "size", apkSize,
                        "installable", apkUrl != null, "reason", apkUrl == null ? "Pas encore de version Android pour cette mise à jour." : null,
                        "checkedAt", System.currentTimeMillis()));
            } else {
                publish(Json.map("state", "uptodate", "current", Version.VERSION, "checkedAt", System.currentTimeMillis()));
            }
        } catch (Exception e) {
            Log.warn("Vérification des mises à jour impossible : " + e.getMessage());
            publish(Json.map("state", "error", "current", Version.VERSION, "error",
                    "GitHub ne répond pas (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ").",
                    "checkedAt", System.currentTimeMillis()));
        }
        return status;
    }

    /** Downloads the APK of the latest release and opens Android's installer. Runs as a job. */
    Map<String, Object> install(Consumer<String> progress) throws Exception {
        Release rel = latest;
        if (rel == null || compare(rel.version(), Version.VERSION) <= 0) {
            throw new Http.Fail(409, "Aucune nouvelle version à installer.");
        }
        if (rel.apkUrl() == null) {
            throw new Http.Fail(409, "Pas encore de version Android pour la version " + rel.version() + ".");
        }
        String game = Json.str(app.players.state(), "state");
        if ("starting".equals(game) || "running".equals(game)) {
            throw new Http.Fail(409, "Quittez d'abord votre partie en cours : la mise à jour redémarre Cable Club.");
        }
        synchronized (this) {
            if (installing) {
                throw new Http.Fail(409, "La mise à jour est déjà en cours.");
            }
            installing = true;
        }
        try {
            Context c = Android.context();
            Path dir = c.getCacheDir().toPath().resolve("mise-a-jour");
            Files.createDirectories(dir);
            Path apk = dir.resolve("CableClub-" + rel.version().replaceAll("[^0-9A-Za-z.]", "") + ".apk");
            download(rel.apkUrl(), apk, rel.apkSize(), progress);
            if (rel.shaUrl() != null) {
                progress.accept("Vérification du fichier…");
                // the .sha256 file is sent from another address too (the app's HTTP client does not follow redirects)
                String expected;
                HttpURLConnection sha = open(rel.shaUrl());
                try (InputStream in = sha.getInputStream()) {
                    java.io.ByteArrayOutputStream text = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[512];
                    for (int n; text.size() < 4096 && (n = in.read(buf)) > 0; ) {
                        text.write(buf, 0, n);
                    }
                    expected = text.toString("UTF-8").trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
                } finally {
                    sha.disconnect();
                }
                if (!expected.equals(sha256(apk))) {
                    throw new IOException("Le fichier téléchargé est abîmé (empreinte différente) : réessayez.");
                }
            }
            progress.accept("Ouverture de l'installation…");
            PackageInstaller pi = c.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(c.getPackageName());
            int id = pi.createSession(params);
            try (PackageInstaller.Session s = pi.openSession(id)) {
                try (OutputStream out = s.openWrite("cableclub.apk", 0, Files.size(apk)); InputStream in = Files.newInputStream(apk)) {
                    byte[] buf = new byte[1 << 16];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        out.write(buf, 0, n);
                    }
                    s.fsync(out);
                }
                Intent done = new Intent(INSTALLED).setPackage(c.getPackageName());
                PendingIntent pending = PendingIntent.getBroadcast(c, id, done, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                s.commit(pending.getIntentSender());
            }
            Log.info("Mise à jour vers Cable Club " + rel.version() + " : installation demandée à Android");
            return Json.map("version", rel.version(), "installer", true);
        } finally {
            installing = false;
        }
    }

    private void publish(Map<String, Object> s) {
        status = s;
        app.hub.send("update", s);
    }

    /** A file of a release, open for reading: GitHub sends it from another address, which is followed. */
    private static HttpURLConnection open(String url) throws IOException {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15_000);
            c.setReadTimeout(60_000);
            c.setRequestProperty("User-Agent", "CableClub-Android/" + Version.VERSION);
            int code = c.getResponseCode();
            if (code / 100 == 3 && c.getHeaderField("Location") != null) {
                url = new URL(new URL(url), c.getHeaderField("Location")).toString();
                c.disconnect();
                continue;
            }
            if (code != 200) {
                c.disconnect();
                throw new IOException("Téléchargement refusé par GitHub (" + code + ")");
            }
            return c;
        }
        throw new IOException("Téléchargement impossible : trop de redirections");
    }

    private void download(String url, Path target, long size, Consumer<String> progress) throws IOException {
        HttpURLConnection c = open(url);
        long total = size > 0 ? size : c.getContentLengthLong();
        long done = 0;
        long shown = -1;
        try (InputStream in = c.getInputStream(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                long mb = done >> 20;
                if (mb != shown) {
                    shown = mb;
                    progress.accept(total > 0 ? "Téléchargement : " + mb + " / " + (total >> 20) + " Mo" : "Téléchargement : " + mb + " Mo");
                }
            }
        } finally {
            c.disconnect();
        }
        if (total > 0 && done != total) {
            throw new IOException("Téléchargement interrompu (" + (done >> 20) + " / " + (total >> 20) + " Mo) : réessayez.");
        }
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
        }
        return Ids.hex(md.digest());
    }

    /** Compares two versions "1.2.3" number by number (missing parts count as 0). */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int u = i < x.length ? leadingInt(x[i]) : 0;
            int v = i < y.length ? leadingInt(y[i]) : 0;
            if (u != v) {
                return Integer.compare(u, v);
            }
        }
        return 0;
    }

    private static int leadingInt(String s) {
        int n = 0;
        for (int i = 0; i < s.length() && Character.isDigit(s.charAt(i)); i++) {
            n = n * 10 + (s.charAt(i) - '0');
        }
        return n;
    }
}
