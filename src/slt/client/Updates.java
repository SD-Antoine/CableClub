package slt.client;

import slt.Http;
import slt.Json;
import slt.Log;
import slt.Version;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Updates of Cable Club from the GitHub releases: the latest release is checked at
 * start then every few hours; "Mettre à jour" downloads its zip, unpacks it
 * next to CableClub.exe and hands over to a small PowerShell script that waits for
 * Cable Club to close, swaps app\, runtime\ and CableClub.exe (the data folder is never
 * touched; the old files come back if anything fails) and starts Cable Club again.
 */
final class Updates {

    static final String REPO = "SD-Antoine/CableClub";
    static final String PAGE = "https://github.com/" + REPO + "/releases/latest";
    private static final String API = System.getProperty("slt.updates.api", "https://api.github.com/repos/" + REPO + "/releases/latest");
    private static final long PERIOD_MS = 6 * 3600_000L;

    private final ClientApp app;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private volatile Map<String, Object> status = Json.map("state", "idle", "current", Version.VERSION);
    /** Latest release seen: version, zip and checksum addresses. */
    private volatile Release latest;
    private volatile boolean installing;

    private record Release(String version, String notes, String page, String zipUrl, long zipSize, String shaUrl) {
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
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(API))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "SLT/" + Version.VERSION)
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() == 404) {
                publish(Json.map("state", "uptodate", "current", Version.VERSION, "checkedAt", System.currentTimeMillis()));
                return status;
            }
            if (r.statusCode() != 200) {
                throw new IOException("GitHub a répondu " + r.statusCode());
            }
            Map<String, Object> rel = Json.parseObject(r.body());
            String version = Json.str(rel, "tag_name");
            version = version == null ? "" : version.replaceFirst("^[vV]", "");
            String zipUrl = null;
            String shaUrl = null;
            long zipSize = 0;
            String zipName = null;
            List<Object> assets = Json.arr(rel.get("assets"));
            for (Object o : assets == null ? List.of() : assets) {
                Map<String, Object> a = Json.obj(o);
                String name = Json.str(a, "name");
                if (name != null && name.matches("CableClub-.*-windows-x64\\.zip")) {
                    zipName = name;
                    zipUrl = Json.str(a, "browser_download_url");
                    zipSize = Json.num(a, "size", 0);
                }
            }
            for (Object o : assets == null ? List.of() : assets) {
                Map<String, Object> a = Json.obj(o);
                if (zipName != null && (zipName + ".sha256").equals(Json.str(a, "name"))) {
                    shaUrl = Json.str(a, "browser_download_url");
                }
            }
            String page = Json.str(rel, "html_url");
            latest = new Release(version, Json.str(rel, "body"), page != null ? page : PAGE, zipUrl, zipSize, shaUrl);
            if (compare(version, Version.VERSION) > 0) {
                String blocked = installBlocker();
                publish(Json.map("state", "available", "current", Version.VERSION, "version", version,
                        "notes", latest.notes(), "page", latest.page(), "size", zipSize,
                        "installable", zipUrl != null && blocked == null, "reason", zipUrl == null ? "Pas encore de fichier à télécharger pour cette version." : blocked,
                        "checkedAt", System.currentTimeMillis()));
            } else {
                publish(Json.map("state", "uptodate", "current", Version.VERSION, "checkedAt", System.currentTimeMillis()));
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Log.warn("Vérification des mises à jour impossible : " + e.getMessage());
            publish(Json.map("state", "error", "current", Version.VERSION, "error",
                    "GitHub ne répond pas (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ").",
                    "checkedAt", System.currentTimeMillis()));
        }
        return status;
    }

    /** Why this copy of Cable Club cannot replace itself (null when it can). */
    private String installBlocker() {
        if (System.getProperty("jpackage.app-path") == null) {
            return "Version de développement : mettez à jour avec git.";
        }
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            return "Mise à jour automatique disponible seulement sous Windows.";
        }
        Path dir = app.appDir();
        try {
            Path probe = Files.createTempFile(dir, "maj", ".tmp");
            Files.delete(probe);
        } catch (IOException | SecurityException e) {
            return "Le dossier de Cable Club est protégé en écriture (" + dir + ") : téléchargez la nouvelle version à la main.";
        }
        return null;
    }

    /**
     * Downloads and unpacks the latest release, then restarts Cable Club on it.
     * Runs as a job (progress shown by the interface).
     */
    Map<String, Object> install(Consumer<String> progress) throws Exception {
        Release rel = latest;
        if (rel == null || compare(rel.version(), Version.VERSION) <= 0) {
            throw new Http.Fail(409, "Aucune nouvelle version à installer.");
        }
        if (rel.zipUrl() == null) {
            throw new Http.Fail(409, "Pas encore de fichier à télécharger pour la version " + rel.version() + ".");
        }
        String blocked = installBlocker();
        if (blocked != null) {
            throw new Http.Fail(409, blocked);
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
            Path appDir = app.appDir();
            Path work = appDir.resolve("maj-" + rel.version().replaceAll("[^0-9A-Za-z.]", ""));
            if (Files.exists(work)) {
                deleteTree(work);
            }
            Files.createDirectories(work);

            Path zip = work.resolve("SLT.zip");
            download(rel.zipUrl(), zip, rel.zipSize(), progress);
            if (rel.shaUrl() != null) {
                progress.accept("Vérification du fichier…");
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(rel.shaUrl()))
                        .header("User-Agent", "SLT/" + Version.VERSION).timeout(Duration.ofSeconds(30)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                String expected = r.body().trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
                String actual = sha256(zip);
                if (r.statusCode() != 200 || !expected.equals(actual)) {
                    throw new IOException("Le fichier téléchargé est abîmé (empreinte différente) : réessayez.");
                }
            }

            progress.accept("Décompression…");
            Path unpacked = work.resolve("nouvelle");
            unzip(zip, unpacked);
            Files.delete(zip);
            Path root = findRoot(unpacked);
            if (root == null) {
                throw new IOException("L'archive ne contient pas le programme.");
            }

            progress.accept("Redémarrage de Cable Club…");
            Path script = writeScript(work, root, appDir);
            List<String> args = new ArrayList<>(app.launchArgs());
            boolean windowClosed = app.closeWindowForRestart();
            if (!windowClosed && !args.contains("--no-window")) {
                // the interface stays open and reloads itself on the new version
                args.add("--no-open");
            }
            Files.writeString(work.resolve("arguments.txt"), String.join("\n", args), StandardCharsets.UTF_8);
            new ProcessBuilder("powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden",
                    "-File", script.toString(), "-SltPid", Long.toString(ProcessHandle.current().pid()))
                    .directory(appDir.toFile()).redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            Log.info("Mise à jour vers Cable Club " + rel.version() + " : redémarrage");
            Thread quit = new Thread(() -> {
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException ignored) {
                    // quit anyway
                }
                app.quit();
            }, "slt-update-quit");
            quit.start();
            return Json.map("version", rel.version(), "restarting", true);
        } catch (Exception e) {
            installing = false;
            throw e;
        }
    }

    private void publish(Map<String, Object> s) {
        status = s;
        app.hub.send("update", s);
    }

    private void download(String url, Path target, long size, Consumer<String> progress) throws Exception {
        HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", "SLT/" + Version.VERSION).timeout(Duration.ofMinutes(30)).GET().build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (r.statusCode() != 200) {
            r.body().close();
            throw new IOException("Téléchargement refusé par GitHub (" + r.statusCode() + ")");
        }
        long total = size > 0 ? size : r.headers().firstValueAsLong("Content-Length").orElse(0);
        long done = 0;
        long shown = -1;
        try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(target)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                done += n;
                long mb = done >> 20;
                if (mb != shown) {
                    shown = mb;
                    progress.accept(total > 0 ? "Téléchargement : " + mb + " / " + (total >> 20) + " Mo"
                            : "Téléchargement : " + mb + " Mo");
                }
            }
        }
        if (total > 0 && done != total) {
            throw new IOException("Téléchargement interrompu (" + (done >> 20) + " / " + (total >> 20) + " Mo) : réessayez.");
        }
    }

    private static void unzip(Path zip, Path target) throws IOException {
        Path base = target.toAbsolutePath().normalize();
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                // Windows PowerShell 5.1 writes "\" in entry names
                String name = e.getName().replace('\\', '/');
                Path out = base.resolve(name).normalize();
                if (!out.startsWith(base)) {
                    throw new IOException("Archive invalide : " + name);
                }
                if (e.isDirectory() || name.endsWith("/")) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** The folder of the archive that holds CableClub.exe, app\ and runtime\. */
    private static Path findRoot(Path dir) throws IOException {
        if (complete(dir)) {
            return dir;
        }
        try (var list = Files.list(dir)) {
            for (Path p : list.toList()) {
                if (Files.isDirectory(p) && complete(p)) {
                    return p;
                }
            }
        }
        return null;
    }

    private static boolean complete(Path dir) {
        return Files.isRegularFile(dir.resolve("CableClub.exe"))
                && Files.isRegularFile(dir.resolve("app").resolve("slt.jar"))
                && Files.isDirectory(dir.resolve("runtime"));
    }

    private Path writeScript(Path work, Path from, Path to) throws IOException {
        String s = """
                # Cable Club update: waits for Cable Club to close, swaps the program files, starts it again.
                param([int]$SltPid)
                $ErrorActionPreference = 'Stop'
                $work = %WORK%
                $from = %FROM%
                $to = %TO%
                $log = %LOG%
                function Note($m) { try { Add-Content -LiteralPath $log -Encoding UTF8 -Value ((Get-Date -Format 'yyyy-MM-dd HH:mm:ss') + ' ' + $m) } catch { } }
                Note "Attente de la fermeture de Cable Club ($SltPid)"
                try { Wait-Process -Id $SltPid -Timeout 120 -ErrorAction Stop } catch { }
                # the game player and any other process of this folder
                for ($i = 0; $i -lt 60; $i++) {
                    $busy = @(Get-Process -ErrorAction SilentlyContinue | Where-Object { $_.Path -and $_.Path.StartsWith($to + '\\', [StringComparison]::OrdinalIgnoreCase) })
                    if ($busy.Count -eq 0) { break }
                    Start-Sleep -Seconds 1
                }
                Start-Sleep -Milliseconds 500
                $old = Join-Path $work 'ancienne'
                New-Item -ItemType Directory -Force $old | Out-Null
                $parts = @('app', 'runtime', 'CableClub.exe')
                $ok = $false
                try {
                    foreach ($n in $parts) { $p = Join-Path $to $n; if (Test-Path -LiteralPath $p) { Move-Item -LiteralPath $p (Join-Path $old $n) } }
                    foreach ($n in $parts) { Move-Item -LiteralPath (Join-Path $from $n) (Join-Path $to $n) }
                    Get-ChildItem -LiteralPath $from -File | ForEach-Object { Copy-Item -LiteralPath $_.FullName (Join-Path $to $_.Name) -Force }
                    $ok = $true
                    Note 'Nouvelle version installée'
                } catch {
                    Note ('Échec : ' + $_)
                    foreach ($n in $parts) {
                        $b = Join-Path $old $n
                        if (Test-Path -LiteralPath $b) {
                            $p = Join-Path $to $n
                            if (Test-Path -LiteralPath $p) { Remove-Item -LiteralPath $p -Recurse -Force }
                            Move-Item -LiteralPath $b $p
                        }
                    }
                    Note 'Ancienne version remise en place'
                }
                $argsFile = Join-Path $work 'arguments.txt'
                $arguments = @()
                if (Test-Path -LiteralPath $argsFile) { $arguments = @(Get-Content -LiteralPath $argsFile -Encoding UTF8 | Where-Object { $_ }) }
                $exe = Join-Path $to 'CableClub.exe'
                if ($arguments.Count -gt 0) {
                    Start-Process -FilePath $exe -WorkingDirectory $to -ArgumentList ($arguments | ForEach-Object { '"' + $_ + '"' })
                } else {
                    Start-Process -FilePath $exe -WorkingDirectory $to
                }
                if ($ok) {
                    Start-Sleep -Seconds 2
                    Set-Location $to
                    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
                }
                """
                .replace("%WORK%", psQuote(work.toAbsolutePath().toString()))
                .replace("%FROM%", psQuote(from.toAbsolutePath().toString()))
                .replace("%TO%", psQuote(to.toAbsolutePath().toString()))
                .replace("%LOG%", psQuote(app.dataDir.resolve("mise-a-jour.log").toAbsolutePath().toString()));
        Path script = work.resolve("mise-a-jour.ps1");
        // with a BOM: Windows PowerShell reads BOM-less scripts in the ANSI code page (accented paths)
        byte[] body = s.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);
        Files.write(script, withBom);
        return script;
    }

    private static String psQuote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    private static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                md.update(buf, 0, n);
            }
        }
        return HexFormat.of().formatHex(md.digest());
    }

    private static void deleteTree(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            for (Path p : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
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
