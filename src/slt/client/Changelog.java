package slt.client;

import slt.Json;
import slt.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The patch notes of every version, from the CHANGELOG.md packaged in the program (changelog.md among its resources;
 * in development, the file at the root of the project): the interface shows the ones of the version installed.
 */
final class Changelog {

    private static final Pattern HEADING = Pattern.compile("^##\\s+(\\S+)\\s*(?:[—–-]\\s*(.*))?$");

    private Changelog() {
    }

    /** The versions, the latest first: version, date (may be empty) and the notes in Markdown. */
    static List<Object> sections(Path appDir) {
        String text = read(appDir);
        List<Object> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        String version = null;
        String date = "";
        StringBuilder body = new StringBuilder();
        for (String line : text.split("\\R", -1)) {
            Matcher m = HEADING.matcher(line.trim());
            if (m.matches()) {
                add(out, version, date, body);
                version = m.group(1);
                date = m.group(2) == null ? "" : m.group(2).trim();
                body.setLength(0);
            } else if (version != null) {
                body.append(line).append('\n');
            }
        }
        add(out, version, date, body);
        return out;
    }

    private static void add(List<Object> out, String version, String date, StringBuilder body) {
        if (version != null) {
            out.add(Json.map("version", version, "date", date, "notes", body.toString().strip()));
        }
    }

    private static String read(Path appDir) {
        try (InputStream in = Changelog.class.getResourceAsStream("/changelog.md")) {
            if (in != null) {
                return new String(slt.Io.readAll(in), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Log.warn("Notes de version illisibles : " + e.getMessage());
        }
        for (Path dir : List.of(Path.of("").toAbsolutePath(), appDir)) {
            Path f = dir.resolve("CHANGELOG.md");
            if (Files.isRegularFile(f)) {
                try {
                    return Files.readString(f, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    Log.warn("Notes de version illisibles : " + e.getMessage());
                }
            }
        }
        return null;
    }

    static Map<String, Object> view(Path appDir) {
        return Json.map("sections", sections(appDir));
    }
}
