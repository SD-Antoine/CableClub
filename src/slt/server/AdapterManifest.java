package slt.server;

import slt.Http;
import slt.Json;

import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The manifest of a game adapter, adapter.json (point 4a of the evolution plan, docs/fan-games.md): which game a module
 * reads, how to recognize the player's own file (its fingerprints, never the file), under which licence the game may
 * be played with Cable Club, who wrote the module, which official game's structures it relies on and what it can do.
 * Only two categories are accepted: (1) homebrew and original works their authors allow, (2) games and engines whose
 * licence allows it. A fan game built on a third party's franchise (category 3) is refused, whatever it declares:
 * Cable Club neither distributes, lists nor supports it. A manifest is data only: no code, no file, no picture.
 */
final class AdapterManifest {

    /** Largest manifest accepted. */
    static final int MAX_BYTES = 8 * 1024;

    static final Set<String> CATEGORIES = Set.of("homebrew", "licensed");

    /** The official games whose memory structures the adapters read: id → generation. */
    static final Map<String, Integer> BASES = bases();

    private static Map<String, Integer> bases() {
        Map<String, Integer> b = new LinkedHashMap<>();
        for (String g : List.of("red", "blue", "yellow")) {
            b.put(g, 1);
        }
        for (String g : List.of("gold", "silver", "crystal")) {
            b.put(g, 2);
        }
        for (String g : List.of("ruby", "sapphire", "emerald", "firered", "leafgreen")) {
            b.put(g, 3);
        }
        for (String g : List.of("diamond", "pearl", "platinum", "heartgold", "soulsilver")) {
            b.put(g, 4);
        }
        for (String g : List.of("black", "white", "black2", "white2")) {
            b.put(g, 5);
        }
        return b;
    }

    /** The platforms of each generation (the emulator cores Cable Club already runs). */
    private static final Map<Integer, Set<String>> PLATFORMS = Map.of(1, Set.of("gb", "gbc"), 2, Set.of("gbc"),
            3, Set.of("gba"), 4, Set.of("nds"), 5, Set.of("nds"));

    private static final Set<String> KEYS = Set.of("schemaVersion", "id", "game", "category", "gameLicense", "module",
            "base", "capabilities");
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    private static final Pattern CRC32 = Pattern.compile("[0-9A-F]{8}");
    private static final Pattern SHA1 = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern HTTPS = Pattern.compile("https://[A-Za-z0-9.-]+(/[A-Za-z0-9._~%!$&'()*+,;=:@/-]*)?");
    /** Names that belong to a franchise of others: a game that uses them is in category 3. */
    private static final List<String> FRANCHISES = List.of("pokemon", "pokémon", "poké", "pocketmonster", "nintendo",
            "gamefreak");

    final String id;
    final String gameName;
    final String platform;
    final String crc32;
    final String sha1;
    final String category;
    final String gameLicense;
    final String gameLicenseUrl;
    final String moduleAuthor;
    final String moduleLicense;
    final String base;
    final int gen;
    final Map<String, Boolean> capabilities;

    private AdapterManifest(Map<String, Object> game, Map<String, Object> licence, Map<String, Object> module,
                            String id, String category, String base, Map<String, Boolean> capabilities) {
        this.id = id;
        this.gameName = Json.str(game, "name");
        this.platform = Json.str(game, "platform");
        this.crc32 = Json.str(game, "crc32");
        this.sha1 = Json.str(game, "sha1");
        this.category = category;
        this.gameLicense = Json.str(licence, "name");
        this.gameLicenseUrl = Json.str(licence, "url");
        this.moduleAuthor = Json.str(module, "author");
        this.moduleLicense = Json.str(module, "license");
        this.base = base;
        this.gen = BASES.get(base);
        this.capabilities = capabilities;
    }

    /** Reads and checks a manifest: everything is checked, anything outside the format is refused with the reason. */
    static AdapterManifest parse(String text) {
        if (text == null || text.isEmpty()) {
            throw fail("fichier vide.");
        }
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw fail("fichier trop gros (" + MAX_BYTES / 1024 + " Ko au plus).");
        }
        Map<String, Object> m;
        try {
            m = Json.parseObject(text);
        } catch (IllegalArgumentException e) {
            throw fail("ce n'est pas un fichier JSON lisible (" + e.getMessage() + ").");
        }
        only(m, KEYS, "");
        if (!(m.get("schemaVersion") instanceof Number v) || v.doubleValue() != 1) {
            throw fail("« schemaVersion » doit valoir 1.");
        }
        String id = text(m, "id", 3, 40);
        if (!ID.matcher(id).matches()) {
            throw fail("« id » : lettres minuscules, chiffres et tirets seulement.");
        }

        Map<String, Object> game = object(m, "game", Set.of("name", "version", "platform", "crc32", "sha1"));
        String name = text(game, "name", 1, 60);
        if (game.containsKey("version")) {
            text(game, "version", 1, 20);
        }
        String crc = Json.str(game, "crc32");
        String sha = Json.str(game, "sha1");
        if (crc == null || !CRC32.matcher(crc).matches()) {
            throw fail("« game.crc32 » : l'empreinte CRC32 du fichier attendu, 8 chiffres hexadécimaux en majuscules.");
        }
        if (sha == null || !SHA1.matcher(sha).matches()) {
            throw fail("« game.sha1 » : l'empreinte SHA-1 du fichier attendu, 40 chiffres hexadécimaux en minuscules.");
        }

        // category 3 is refused, whatever the file says
        Object cat = m.get("category");
        if (!(cat instanceof String category) || !CATEGORIES.contains(category)) {
            throw fail("« category » : homebrew (œuvre originale que son auteur autorise) ou licensed (jeu ou moteur sous une"
                    + " licence qui le permet). Les fan games reprenant une franchise tierce ne sont pas pris en charge.");
        }
        String folded = Normalizer.normalize(name, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\s\\-_.]+", "");
        for (String f : FRANCHISES) {
            if (folded.contains(f)) {
                throw fail("« game.name » : un jeu qui reprend le nom d'une franchise tierce relève de la catégorie 3, qui n'est"
                        + " pas prise en charge.");
            }
        }

        Map<String, Object> licence = object(m, "gameLicense", Set.of("name", "url"));
        text(licence, "name", 1, 60);
        String url = text(licence, "url", 12, 200);
        if (!HTTPS.matcher(url).matches()) {
            throw fail("« gameLicense.url » : l'adresse https de la licence ou de l'autorisation de l'auteur du jeu.");
        }
        Map<String, Object> module = object(m, "module", Set.of("author", "license"));
        text(module, "author", 1, 60);
        text(module, "license", 1, 40);

        Object b = m.get("base");
        if (!(b instanceof String base) || !BASES.containsKey(base)) {
            throw fail("« base » : le jeu officiel dont le module reprend les structures en mémoire, parmi " + BASES.keySet() + ".");
        }
        String platform = Json.str(game, "platform");
        if (platform == null || !PLATFORMS.get(BASES.get(base)).contains(platform)) {
            throw fail("« game.platform » : " + PLATFORMS.get(BASES.get(base)) + " pour un jeu aux structures de « " + base + " ».");
        }

        Map<String, Object> caps = Json.obj(Capabilities.data().get("capabilities"));
        Map<String, Object> declared = Json.obj(m.get("capabilities"));
        if (declared == null) {
            throw fail("« capabilities » doit être un objet (ce que le module sait faire : true ou false).");
        }
        Map<String, Boolean> capabilities = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : declared.entrySet()) {
            if (caps != null && !caps.containsKey(e.getKey())) {
                throw fail("« capabilities » : capacité inconnue « " + e.getKey() + " » (voir web/js/data/capabilities.json).");
            }
            if (!(e.getValue() instanceof Boolean on)) {
                throw fail("« capabilities." + e.getKey() + " » : true ou false.");
            }
            capabilities.put(e.getKey(), on);
        }
        return new AdapterManifest(game, licence, module, id, category, base, capabilities);
    }

    private static Http.Fail fail(String message) {
        return new Http.Fail(400, "Manifeste d'adaptateur invalide : " + message);
    }

    private static void only(Map<String, Object> m, Set<String> keys, String where) {
        for (String k : m.keySet()) {
            if (!keys.contains(k)) {
                throw fail("champ inconnu « " + where + k + " ».");
            }
        }
    }

    private static Map<String, Object> object(Map<String, Object> m, String key, Set<String> keys) {
        Map<String, Object> o = Json.obj(m.get(key));
        if (o == null) {
            throw fail("« " + key + " » doit être un objet.");
        }
        only(o, keys, key + ".");
        return o;
    }

    /** A short text: no control or invisible character, no markup. */
    private static String text(Map<String, Object> m, String key, int min, int max) {
        if (!(m.get(key) instanceof String s)) {
            throw fail("« " + key + " » : texte attendu.");
        }
        int n = s.codePointCount(0, s.length());
        if (n < min || n > max || !s.equals(s.strip())) {
            throw fail("« " + key + " » : " + min + " à " + max + " caractères, sans espace au début ni à la fin.");
        }
        boolean hidden = s.codePoints().anyMatch(c -> switch (Character.getType(c)) {
            case Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
                    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> true;
            default -> false;
        });
        if (hidden || s.indexOf('<') >= 0 || s.indexOf('>') >= 0) {
            throw fail("« " + key + " » : caractères de contrôle, invisibles ou balises interdits.");
        }
        return s;
    }
}
