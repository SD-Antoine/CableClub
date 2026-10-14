package slt;

import java.io.File;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Server side of the file browser shown inside SLT. A native Windows dialog
 * opened by a background process can appear behind the app window; browsing
 * from the interface itself avoids that entirely. Local API only.
 */
public final class FileBrowser {

    public static final Set<String> ROM_EXTENSIONS = Set.of("gb", "gbc", "sgb", "gba", "nds");

    private FileBrowser() {
    }

    /** Usual starting points: SLT folder, user folders, drives. */
    public static List<Object> places(Path appDir) {
        List<Object> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Path home = Path.of(System.getProperty("user.home"));
        addPlace(out, seen, "Dossier de Cable Club", appDir, "app");
        Path romsNext = appDir.resolve("roms");
        addPlace(out, seen, "roms (à côté de Cable Club)", romsNext, "folder");
        addPlace(out, seen, "Bureau", home.resolve("Desktop"), "folder");
        addPlace(out, seen, "Bureau (OneDrive)", home.resolve("OneDrive").resolve("Bureau"), "folder");
        addPlace(out, seen, "Bureau (OneDrive)", home.resolve("OneDrive").resolve("Desktop"), "folder");
        addPlace(out, seen, "Téléchargements", home.resolve("Downloads"), "folder");
        addPlace(out, seen, "Documents", home.resolve("Documents"), "folder");
        addPlace(out, seen, "Documents (OneDrive)", home.resolve("OneDrive").resolve("Documents"), "folder");
        addPlace(out, seen, "Dossier personnel", home, "folder");
        for (File root : File.listRoots()) {
            String label = "Disque " + root.getPath().replace("\\", "");
            addPlace(out, seen, label, root.toPath(), "drive");
        }
        return out;
    }

    private static void addPlace(List<Object> out, Set<String> seen, String label, Path p, String kind) {
        if (p == null || !Files.isDirectory(p)) {
            return;
        }
        String key = p.toAbsolutePath().normalize().toString().toLowerCase(Locale.ROOT);
        if (seen.add(key)) {
            out.add(Json.map("label", label, "path", p.toAbsolutePath().normalize().toString(), "kind", kind));
        }
    }

    /**
     * Lists a folder: sub-folders first, then the files whose extension is
     * accepted (all files when exts is empty).
     */
    public static Map<String, Object> list(String pathText, Set<String> exts) {
        if (pathText == null || pathText.isBlank()) {
            throw new Http.Fail(400, "Dossier manquant");
        }
        Path dir = Path.of(pathText.trim()).toAbsolutePath().normalize();
        if (Files.isRegularFile(dir)) {
            dir = dir.getParent();
        }
        if (dir == null || !Files.isDirectory(dir)) {
            throw new Http.Fail(404, "Dossier introuvable : " + pathText);
        }
        List<Map<String, Object>> dirs = new ArrayList<>();
        List<Map<String, Object>> files = new ArrayList<>();
        int hiddenCount = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (dirs.size() + files.size() >= 3000) {
                    break;
                }
                String name = p.getFileName() == null ? p.toString() : p.getFileName().toString();
                try {
                    if (name.startsWith(".") || name.startsWith("$") || Files.isHidden(p)) {
                        continue;
                    }
                    BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
                    if (a.isDirectory()) {
                        dirs.add(Json.map("name", name, "path", p.toString(), "dir", true));
                    } else if (exts.isEmpty() || exts.contains(extension(name))) {
                        files.add(Json.map("name", name, "path", p.toString(), "dir", false, "size", a.size()));
                    } else {
                        hiddenCount++;
                    }
                } catch (IOException ignored) {
                    // unreadable entry: skip it
                }
            }
        } catch (IOException e) {
            throw new Http.Fail(403, "Accès refusé à ce dossier : " + dir);
        }
        dirs.sort((x, y) -> String.CASE_INSENSITIVE_ORDER.compare(Json.str(x, "name"), Json.str(y, "name")));
        files.sort((x, y) -> String.CASE_INSENSITIVE_ORDER.compare(Json.str(x, "name"), Json.str(y, "name")));
        List<Object> entries = new ArrayList<>(dirs);
        entries.addAll(files);
        Path parent = dir.getParent();
        return Json.map("path", dir.toString(), "parent", parent == null ? null : parent.toString(),
                "entries", entries, "otherFiles", (long) hiddenCount);
    }

    public static String extension(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** ROM files found in a folder and its sub-folders (3 levels). */
    public static List<Path> findRoms(Path dir, Consumer<String> progress) throws IOException {
        if (!Files.isDirectory(dir)) {
            throw new Http.Fail(404, "Dossier introuvable : " + dir);
        }
        progress.accept("Recherche des ROMs dans " + dir + "…");
        List<Path> out = new ArrayList<>();
        try (Stream<Path> s = Files.find(dir, 3, (p, a) -> a.isRegularFile()
                && ROM_EXTENSIONS.contains(extension(p.getFileName().toString())))) {
            s.sorted().forEach(out::add);
        }
        return out;
    }
}
