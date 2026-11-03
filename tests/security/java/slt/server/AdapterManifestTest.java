package slt.server;

import slt.Http;
import slt.Json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Consumer;

/**
 * The manifest of a game adapter (point 4a): the example of docs/ is accepted; a fan game built on a third party's
 * franchise is refused however it is declared; a manifest carries fingerprints, never a file, and nothing outside
 * the format. Then thousands of mutated manifests: the only outcomes allowed are a manifest or a refusal with 400.
 */
public final class AdapterManifestTest {

    private static int failed;

    public static void main(String[] args) throws IOException {
        Path root = Path.of(System.getProperty("slt.web", "web")).toAbsolutePath().getParent();
        String example = Files.readString(root.resolve("docs").resolve("adapter.example.json"));
        AdapterManifest ok = AdapterManifest.parse(example);
        check(ok.gen == 3 && "homebrew".equals(ok.category) && ok.capabilities.get("shinies"), "l'exemple de docs/ est accepté");

        // category 3: refused, whatever the file says
        refused(example, m -> m.put("category", "thirdParty"), "« category »");
        refused(example, m -> m.put("category", "franchise"), "« category »");
        refused(example, m -> game(m).put("name", "Pokémon Lumière"), "franchise tierce");
        refused(example, m -> game(m).put("name", "ＰＯＫＥＭＯＮ Quartz"), "franchise tierce");
        refused(example, m -> game(m).put("name", "Pocket Monsters Opale"), "franchise tierce");
        // fingerprints, never the file; nothing outside the format
        refused(example, m -> game(m).remove("sha1"), "« game.sha1 »");
        refused(example, m -> game(m).put("crc32", "xyz"), "« game.crc32 »");
        refused(example, m -> m.put("rom", "UEsDBBQAAAAI"), "champ inconnu « rom »");
        refused(example, m -> game(m).put("file", "jeu.gba"), "champ inconnu « game.file »");
        refused(example, m -> m.put("script", "x"), "champ inconnu « script »");
        // a licence that can be checked; structures and platform that Cable Club reads
        refused(example, m -> Json.obj(m.get("gameLicense")).put("url", "http://example.org/licence"), "« gameLicense.url »");
        refused(example, m -> Json.obj(m.get("gameLicense")).remove("url"), "« url »");
        refused(example, m -> m.put("base", "sword"), "« base »");
        refused(example, m -> game(m).put("platform", "nds"), "« game.platform »");
        refused(example, m -> Json.obj(m.get("capabilities")).put("teleport", true), "capacité inconnue");
        refused(example, m -> Json.obj(m.get("capabilities")).put("shinies", "oui"), "true ou false");
        refused(example, m -> game(m).put("name", "Jeu \u202Ecaché"), "invisibles");
        refused(example, m -> game(m).put("name", "<b>Jeu</b>"), "balises");
        refused(example, m -> m.put("schemaVersion", 2), "schemaVersion");
        refused("[" .repeat(5000), "JSON lisible");

        // fuzz
        Random rnd = new Random(20261103L);
        int accepted = 0;
        for (int i = 0; i < 3000 && failed <= 20; i++) {
            Map<String, Object> m = Json.parseObject(example);
            mutate(m, rnd);
            try {
                AdapterManifest.parse(Json.stringify(m));
                accepted++;
            } catch (Http.Fail f) {
                if (f.status != 400) {
                    check(false, "refus " + f.status);
                }
            } catch (Throwable t) {
                check(false, "exception " + t + " | " + Json.stringify(m));
            }
        }
        System.out.println(failed == 0 ? "ok     manifeste d'adaptateur (3000 manifestes mutés, " + accepted + " acceptés)"
                : "ÉCHEC  manifeste d'adaptateur : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static Map<String, Object> game(Map<String, Object> m) {
        return Json.obj(m.get("game"));
    }

    private static void refused(String base, Consumer<Map<String, Object>> edit, String why) {
        Map<String, Object> m = Json.parseObject(base);
        edit.accept(m);
        refused(Json.stringify(m), why);
    }

    private static void refused(String text, String why) {
        try {
            AdapterManifest.parse(text);
            check(false, "aurait dû être refusé (" + why + ")");
        } catch (Http.Fail f) {
            check(f.status == 400 && f.getMessage().contains(why), "refus « " + why + " », reçu : " + f.getMessage());
        }
    }

    private static final Object[] VALUES = {null, true, false, 0L, -1L, 3L, 1e300, "", "x", "emerald", "gba", "homebrew",
        "licensed", "https://example.org/a", "0BADC0DE", "[]", "{}", "\u202E", "Pokémon"};

    /** A value of {@link #VALUES}, a new list or object for "[]" and "{}" (they may be mutated in turn). */
    private static Object value(Random rnd) {
        Object v = VALUES[rnd.nextInt(VALUES.length)];
        return "[]".equals(v) ? new ArrayList<>() : "{}".equals(v) ? new java.util.LinkedHashMap<String, Object>() : v;
    }

    @SuppressWarnings("unchecked")
    private static void mutate(Map<String, Object> m, Random rnd) {
        for (int n = 1 + rnd.nextInt(3); n > 0; n--) {
            Map<String, Object> target = m;
            List<String> nested = new ArrayList<>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() instanceof Map) {
                    nested.add(e.getKey());
                }
            }
            if (!nested.isEmpty() && rnd.nextBoolean()) {
                target = (Map<String, Object>) m.get(nested.get(rnd.nextInt(nested.size())));
            }
            List<String> keys = new ArrayList<>(target.keySet());
            switch (rnd.nextInt(3)) {
                case 0 -> { if (!keys.isEmpty()) target.remove(keys.get(rnd.nextInt(keys.size()))); }
                case 1 -> target.put("k" + rnd.nextInt(5), value(rnd));
                default -> { if (!keys.isEmpty()) target.put(keys.get(rnd.nextInt(keys.size())), value(rnd)); }
            }
        }
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  " + what);
        }
    }
}
