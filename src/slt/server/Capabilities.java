package slt.server;

import slt.Json;
import slt.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * What Cable Club can do in each game (point 1d of the evolution plan), read from web/js/data/capabilities.json:
 * the same file the interface reads, so the server and the interface never disagree. A generation gives the
 * defaults and a game may change them; a mode or a rule lists the capabilities it needs. The server refuses to
 * create a room whose mode the game cannot play, and to turn on a rule the game cannot apply.
 */
final class Capabilities {

    private static final String RESOURCE = "/web/js/data/capabilities.json";
    private static volatile Map<String, Object> data;

    private Capabilities() {
    }

    /** The file: packaged with the interface in the jar, or web/ next to the sources (development, tests). */
    static Map<String, Object> data() {
        Map<String, Object> d = data;
        if (d != null) {
            return d;
        }
        String text = null;
        try (InputStream in = Capabilities.class.getResourceAsStream(RESOURCE)) {
            if (in != null) {
                text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Log.warn("Capacités des jeux illisibles : " + e.getMessage());
        }
        if (text == null) {
            Path file = Path.of(System.getProperty("slt.web", "web"), "js", "data", "capabilities.json");
            try {
                text = Files.isRegularFile(file) ? Files.readString(file, StandardCharsets.UTF_8) : null;
            } catch (IOException e) {
                Log.warn("Capacités des jeux illisibles : " + e.getMessage());
            }
        }
        if (text == null) {
            Log.warn("Capacités des jeux introuvables (" + RESOURCE + ") : aucune règle n'est refusée.");
            d = Map.of();
        } else {
            d = Json.parseObject(text);
        }
        data = d;
        return d;
    }

    /** Whether the game can do this: its own value, or else its generation's. */
    static boolean has(String gameId, int gen, String capability) {
        Map<String, Object> games = Json.obj(data().get("games"));
        Map<String, Object> game = games == null || gameId == null ? null : Json.obj(games.get(gameId));
        if (game != null && game.get(capability) instanceof Boolean b) {
            return b;
        }
        Map<String, Object> generations = Json.obj(data().get("generations"));
        Map<String, Object> g = generations == null ? null : Json.obj(generations.get(String.valueOf(gen)));
        return g != null && Boolean.TRUE.equals(g.get(capability));
    }

    /** Why the game cannot play this mode (the text of the capability it lacks), or null when it can. */
    static String missingForMode(String mode, String gameId, int gen) {
        return missing("modes", mode, gameId, gen);
    }

    /** Why the game cannot apply this rule, or null when it can. */
    static String missingForRule(String rule, String gameId, int gen) {
        return missing("rules", rule, gameId, gen);
    }

    private static String missing(String table, String key, String gameId, int gen) {
        Map<String, Object> t = Json.obj(data().get(table));
        List<Object> needs = t == null ? null : Json.arr(t.get(key));
        if (needs == null || gen <= 0) {
            return null; // nothing needed, or the game is not known: nothing refused
        }
        Map<String, Object> caps = Json.obj(data().get("capabilities"));
        for (Object o : needs) {
            String cap = String.valueOf(o);
            if (!has(gameId, gen, cap)) {
                String why = Json.str(caps == null ? null : Json.obj(caps.get(cap)), "missing");
                return why == null ? cap : why;
            }
        }
        return null;
    }
}
