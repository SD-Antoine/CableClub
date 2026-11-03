package slt.server;

import slt.Http;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A game mode as a declarative file, .ccmode.json (point 2 of the evolution plan): it only composes the primitives of
 * the engine ({@link Mode}) — whether the players are linked, which wild Pokémon count, how many Pokémon the team
 * holds — with the rules of the catalog below, their default values, the ones the mode fixes, and its end
 * conditions. There is no code, no binary, no picture and no address in a mode: anything else is refused, with a
 * clear message. The official modes are files of the interface (web/js/data/modes), read like any other.
 */
final class ModeSpec {

    /** Largest file accepted. */
    static final int MAX_BYTES = 32 * 1024;

    // ---------------------------------------------------------- primitives

    static final String SHARED = "shared";
    static final String NONE = "none";
    static final String FIRST_PER_ZONE = "firstPerZone";
    static final String SHINIES_ONLY = "shiniesOnly";
    static final String FREE = "free";
    static final String ONE = "one";

    /** End conditions a mode may use (each one has its rule: autoEnd, wipeEndsRun, soloLoss). */
    static final Set<String> WINS = Set.of("champion");
    static final Set<String> LOSES = Set.of("teamWiped", "soloLost");
    /** Goals shown to the players (point 3); they decide nothing. */
    static final Set<String> OBJECTIVES = Set.of("champion", "badges", "captures");

    /**
     * One rule of the catalog: a switch or a choice, the primitive it makes sense with ("links:shared"…, null =
     * always), the value it must keep otherwise, and whether only an official mode may use it (written in the ROM).
     */
    private record RuleDef(List<String> options, String when, Object neutral, boolean officialOnly) {
        boolean bool() {
            return options == null;
        }
    }

    /** The rules a mode may use: the primitives of the engine. Nothing else is accepted in a mode file. */
    static final Map<String, RuleDef> CATALOG = catalog();

    /** The catalog: modes/catalog.json, the file the mode editor of the interface reads too. */
    private static Map<String, RuleDef> catalog() {
        String text = read("catalog.json");
        Map<String, Object> rules = text == null ? null : Json.obj(Json.parseObject(text).get("rules"));
        if (rules == null) {
            throw new IllegalStateException("Catalogue des règles introuvable (" + DIR + "catalog.json)");
        }
        Map<String, RuleDef> c = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : rules.entrySet()) {
            Map<String, Object> d = Json.obj(e.getValue());
            List<Object> options = Json.arr(d.get("options"));
            List<String> values = null;
            if (options != null) {
                values = new ArrayList<>();
                for (Object o : options) {
                    values.add(String.valueOf(o));
                }
            }
            Object neutral = options == null ? Boolean.FALSE : Json.str(d, "neutral");
            if (neutral == null || (values != null && !values.contains(neutral))) {
                throw new IllegalStateException("Catalogue des règles : « " + e.getKey() + " » sans valeur neutre parmi ses options");
            }
            c.put(e.getKey(), new RuleDef(values == null ? null : List.copyOf(values), Json.str(d, "when"), neutral,
                    Json.bool(d, "officialOnly", false)));
        }
        return Collections.unmodifiableMap(c);
    }

    // --------------------------------------------------------------- spec

    final String id;
    final String name;
    final String description;
    final String author;
    final String license;
    final int minPlayers;
    final int maxPlayers;
    final List<String> needs;
    final String links;
    final String encounters;
    final String team;
    private final Map<String, Object> defaults;
    private final Set<String> locked;
    private final Set<String> win;
    private final Set<String> lose;
    final List<Map<String, Object>> objectives;
    final boolean official;

    private ModeSpec(Builder b) {
        id = b.id;
        name = b.name;
        description = b.description;
        author = b.author;
        license = b.license;
        minPlayers = b.minPlayers;
        maxPlayers = b.maxPlayers;
        needs = List.copyOf(b.needs);
        links = b.links;
        encounters = b.encounters;
        team = b.team;
        defaults = Collections.unmodifiableMap(new LinkedHashMap<>(b.defaults));
        locked = Set.copyOf(b.locked);
        win = Set.copyOf(b.win);
        lose = Set.copyOf(b.lose);
        objectives = List.copyOf(b.objectives);
        official = b.official;
    }

    boolean shared() {
        return SHARED.equals(links);
    }

    boolean shiniesOnly() {
        return SHINIES_ONLY.equals(encounters);
    }

    boolean solo() {
        return ONE.equals(team);
    }

    /**
     * The historical mode this one is closest to, whose tab shows its rooms: the links first (Soul Link), then the
     * shinies (Shinylocke), then the team of one (Sololocke), else the Nuzlocke.
     */
    String family() {
        return shared() ? "soullink" : shiniesOnly() ? "shinylocke" : solo() ? "sololocke" : "nuzlocke";
    }

    /** The rules of a new room of this mode (a copy: the room changes it). */
    Map<String, Object> defaults() {
        return new LinkedHashMap<>(defaults);
    }

    boolean offers(String rule) {
        return defaults.containsKey(rule);
    }

    /** The default value of a rule of this mode (null when the mode does not offer it). */
    Object defaultOf(String rule) {
        return defaults.get(rule);
    }

    boolean locked(String rule) {
        return locked.contains(rule);
    }

    boolean wins(String condition) {
        return win.contains(condition);
    }

    boolean loses(String condition) {
        return lose.contains(condition);
    }

    /** Whether a rule makes sense with this mode's primitives (otherwise it may only keep its neutral value). */
    boolean applies(String rule) {
        RuleDef def = CATALOG.get(rule);
        return def != null && holds(def.when());
    }

    /** The file of this mode, normalized (what a room keeps, what the interface shows and exports). */
    Map<String, Object> toJson() {
        Map<String, Object> rules = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : defaults.entrySet()) {
            rules.put(e.getKey(), locked.contains(e.getKey()) ? Json.map("default", e.getValue(), "locked", true) : e.getValue());
        }
        List<Object> goals = new ArrayList<>();
        for (Map<String, Object> g : objectives) {
            goals.add(new LinkedHashMap<>(g));
        }
        return Json.map("schemaVersion", 1L, "id", id, "name", name, "description", description, "author", author,
                "license", license, "players", Json.map("min", (long) minPlayers, "max", (long) maxPlayers),
                "needs", new ArrayList<Object>(needs),
                "engine", Json.map("links", links, "encounters", encounters, "team", team),
                "rules", rules, "win", new ArrayList<Object>(win), "lose", new ArrayList<Object>(lose), "objectives", goals);
    }

    /** The catalog as read: values, where a rule applies, its neutral value, official modes only. */
    static List<Object> catalogJson() {
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, RuleDef> e : CATALOG.entrySet()) {
            RuleDef d = e.getValue();
            out.add(Json.map("key", e.getKey(), "options", d.bool() ? null : new ArrayList<Object>(d.options()),
                    "when", d.when(), "neutral", d.neutral(), "officialOnly", d.officialOnly()));
        }
        return out;
    }

    /** Whether the value is one the rule allows (a switch: true or false; a choice: one of its options). */
    static boolean allows(String rule, Object value) {
        RuleDef def = CATALOG.get(rule);
        if (def == null) {
            return false;
        }
        return def.bool() ? value instanceof Boolean : value instanceof String s && def.options().contains(s);
    }

    /** The value a rule must keep where it does not apply. */
    static Object neutral(String rule) {
        RuleDef def = CATALOG.get(rule);
        return def == null ? null : def.neutral();
    }

    private boolean holds(String when) {
        if (when == null) {
            return true;
        }
        int colon = when.indexOf(':');
        String axis = when.substring(0, colon);
        String value = when.substring(colon + 1);
        return switch (axis) {
            case "links" -> value.equals(links);
            case "encounters" -> value.equals(encounters);
            case "team" -> value.equals(team);
            case "win" -> win.contains(value);
            default -> false;
        };
    }

    // ------------------------------------------------------------ official

    private static final String DIR = "/web/js/data/modes/";
    private static volatile Map<String, ModeSpec> officials;

    /** The official modes, by id (the files of the interface, read once). */
    static Map<String, ModeSpec> officials() {
        Map<String, ModeSpec> o = officials;
        if (o != null) {
            return o;
        }
        Map<String, ModeSpec> out = new LinkedHashMap<>();
        String index = read("index.json");
        List<Object> ids = index == null ? null : Json.arr(Json.parse(index));
        if (ids == null) {
            throw new IllegalStateException("Modes officiels introuvables (" + DIR + "index.json)");
        }
        for (Object id : ids) {
            String text = read(id + ".ccmode.json");
            if (text == null) {
                throw new IllegalStateException("Mode officiel introuvable : " + id);
            }
            ModeSpec spec = parse(text, true);
            if (!spec.id.equals(id)) {
                throw new IllegalStateException("Mode officiel " + id + " : identifiant « " + spec.id + " » dans le fichier");
            }
            out.put(spec.id, spec);
        }
        officials = Collections.unmodifiableMap(out);
        return officials;
    }

    static ModeSpec official(String id) {
        return id == null ? null : officials().get(id);
    }

    private static String read(String file) {
        try (InputStream in = ModeSpec.class.getResourceAsStream(DIR + file)) {
            if (in != null) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            Log.warn("Mode illisible : " + file + " : " + e.getMessage());
        }
        Path f = Path.of(System.getProperty("slt.web", "web"), "js", "data", "modes", file);
        try {
            return Files.isRegularFile(f) ? Files.readString(f, StandardCharsets.UTF_8) : null;
        } catch (IOException e) {
            Log.warn("Mode illisible : " + f + " : " + e.getMessage());
            return null;
        }
    }

    // ---------------------------------------------------------- validation

    private static final Set<String> KEYS = Set.of("schemaVersion", "id", "name", "description", "author", "license",
            "players", "needs", "engine", "rules", "win", "lose", "objectives");
    private static final Pattern ID = Pattern.compile("[a-z0-9]+(-[a-z0-9]+)*");
    /** An address, a link or a domain name: never in a mode (no link to a ROM, a picture, a site). */
    private static final Pattern ADDRESS = Pattern.compile("(?i)[a-z][a-z0-9+.-]*://|\\bwww\\.|\\b[a-z0-9-]+\\."
            + "(com|net|org|fr|io|app|gg|xyz|ru|info|me|co|ly|link|be|ch|de|uk|eu|es|it|tk|top|site|online|zip)\\b");

    /**
     * Reads and checks a mode file. Everything is checked: the shape, the bounds, the rules against the catalog and
     * the primitives, the end conditions, the texts (no address, no markup). An official mode may also use the rule
     * written in the ROM of the room.
     */
    static ModeSpec parse(String text, boolean official) {
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
        for (String k : m.keySet()) {
            if (!KEYS.contains(k)) {
                throw fail("champ inconnu « " + k + " ».");
            }
        }
        if (!(m.get("schemaVersion") instanceof Number v) || v.longValue() != 1) {
            throw fail("« schemaVersion » doit valoir 1.");
        }
        Builder b = new Builder();
        b.official = official;
        b.id = string(m, "id", 3, 32, true);
        if (!ID.matcher(b.id).matches()) {
            throw fail("« id » : lettres minuscules, chiffres et tirets seulement.");
        }
        b.name = string(m, "name", 1, 40, true);
        b.description = string(m, "description", 0, 600, false);
        b.author = string(m, "author", 1, 40, true);
        b.license = string(m, "license", 1, 40, true);

        Map<String, Object> players = object(m, "players", Set.of("min", "max"));
        b.minPlayers = integer(players, "players.min", 1, 10);
        b.maxPlayers = integer(players, "players.max", b.minPlayers, 10);

        Map<String, Object> engine = object(m, "engine", Set.of("links", "encounters", "team"));
        b.links = oneOf(engine, "engine.links", SHARED, NONE);
        b.encounters = oneOf(engine, "engine.encounters", FIRST_PER_ZONE, SHINIES_ONLY);
        b.team = oneOf(engine, "engine.team", FREE, ONE);
        if (SHARED.equals(b.links) && b.minPlayers < 2) {
            throw fail("des joueurs liés sont au moins 2 (« players.min »).");
        }

        Map<String, Object> caps = Json.obj(Capabilities.data().get("capabilities"));
        for (String need : strings(m, "needs", 10)) {
            if (caps != null && !caps.containsKey(need)) {
                throw fail("« needs » : capacité inconnue « " + need + " ».");
            }
            b.needs.add(need);
        }
        if (SHINIES_ONLY.equals(b.encounters) && !b.needs.contains("shinies")) {
            throw fail("un mode où seuls les chromatiques comptent a besoin de « shinies » (« needs »).");
        }

        b.win.addAll(subset(m, "win", WINS));
        b.lose.addAll(subset(m, "lose", LOSES));

        Map<String, Object> rules = Json.obj(m.get("rules"));
        if (rules == null) {
            throw fail("« rules » doit être un objet.");
        }
        ModeSpec probe = new ModeSpec(b); // primitives and end conditions known: what each rule may be
        for (Map.Entry<String, Object> e : rules.entrySet()) {
            String key = e.getKey();
            RuleDef def = CATALOG.get(key);
            if (def == null) {
                throw fail("règle inconnue « " + key + " ».");
            }
            if (def.officialOnly() && !official) {
                throw fail("la règle « " + key + " » modifie la ROM de la salle : réservée aux modes officiels.");
            }
            Object value = e.getValue();
            boolean lock = false;
            if (value instanceof Map<?, ?> o) {
                for (Object k : o.keySet()) {
                    if (!"default".equals(k) && !"locked".equals(k)) {
                        throw fail("règle « " + key + " » : champ inconnu « " + k + " ».");
                    }
                }
                Object l = o.get("locked");
                if (l != null && !(l instanceof Boolean)) {
                    throw fail("règle « " + key + " » : « locked » doit valoir true ou false.");
                }
                lock = Boolean.TRUE.equals(l);
                value = o.get("default");
            }
            if (def.bool() ? !(value instanceof Boolean) : !(value instanceof String s && def.options().contains(s))) {
                throw fail("règle « " + key + " » : valeur " + (def.bool() ? "true ou false" : "parmi " + def.options())
                        + " attendue.");
            }
            if (!probe.holds(def.when()) && !def.neutral().equals(value)) {
                throw fail("la règle « " + key + " » n'a pas de sens avec ces primitives (" + def.when()
                        + ") : elle ne peut valoir que " + def.neutral() + ".");
            }
            b.defaults.put(key, value);
            if (lock) {
                b.locked.add(key);
            }
        }
        if (b.win.contains("champion") && !b.defaults.containsKey("autoEnd")) {
            throw fail("la victoire contre le Maître (« win ») a besoin de la règle « autoEnd ».");
        }
        if (b.lose.contains("teamWiped") && (ONE.equals(b.team) || !b.defaults.containsKey("wipeEndsRun"))) {
            throw fail("« teamWiped » (« lose ») : une équipe libre (« engine.team ») et la règle « wipeEndsRun ».");
        }
        if (ONE.equals(b.team) && (!b.lose.contains("soloLost") || !b.defaults.containsKey("soloLoss"))) {
            throw fail("une équipe d'un seul Pokémon a besoin de « soloLost » (« lose ») et de la règle « soloLoss ».");
        }
        if (b.lose.contains("soloLost") && !ONE.equals(b.team)) {
            throw fail("« soloLost » (« lose ») suppose une équipe d'un seul Pokémon (« engine.team »).");
        }

        List<Object> objectives = list(m, "objectives", 10);
        for (Object o : objectives) {
            Map<String, Object> g = Json.obj(o);
            if (g == null) {
                throw fail("« objectives » : objets attendus.");
            }
            for (String k : g.keySet()) {
                if (!"type".equals(k) && !"count".equals(k)) {
                    throw fail("« objectives » : champ inconnu « " + k + " ».");
                }
            }
            String type = Json.str(g, "type");
            if (type == null || !OBJECTIVES.contains(type)) {
                throw fail("« objectives » : type parmi " + OBJECTIVES + " attendu.");
            }
            if ("champion".equals(type) ? g.containsKey("count") : !(g.get("count") instanceof Number)) {
                throw fail("« objectives » : « count » " + ("champion".equals(type) ? "inutile" : "manquant") + " pour " + type + ".");
            }
            if (g.get("count") instanceof Number) {
                integer(g, "objectives.count", 1, "badges".equals(type) ? 8 : 500);
            }
            b.objectives.add(new LinkedHashMap<>(g));
        }
        return new ModeSpec(b);
    }

    private static Http.Fail fail(String message) {
        return new Http.Fail(400, "Mode invalide : " + message);
    }

    private static String string(Map<String, Object> m, String key, int min, int max, boolean required) {
        Object o = m.get(key);
        if (o == null && !required) {
            return "";
        }
        if (!(o instanceof String s)) {
            throw fail("« " + key + " » : texte attendu.");
        }
        String t = s.strip();
        if (t.codePointCount(0, t.length()) < min || t.codePointCount(0, t.length()) > max) {
            throw fail("« " + key + " » : " + min + " à " + max + " caractères.");
        }
        if (t.chars().anyMatch(c -> c < 0x20 && c != '\n') || t.indexOf('<') >= 0 || t.indexOf('>') >= 0) {
            throw fail("« " + key + " » : caractères de contrôle ou balises interdits.");
        }
        if (ADDRESS.matcher(t).find()) {
            throw fail("« " + key + " » : pas d'adresse ni de lien dans un mode.");
        }
        return t;
    }

    private static Map<String, Object> object(Map<String, Object> m, String key, Set<String> keys) {
        Map<String, Object> o = Json.obj(m.get(key));
        if (o == null) {
            throw fail("« " + key + " » doit être un objet.");
        }
        for (String k : o.keySet()) {
            if (!keys.contains(k)) {
                throw fail("« " + key + " » : champ inconnu « " + k + " ».");
            }
        }
        return o;
    }

    private static int integer(Map<String, Object> m, String label, int min, int max) {
        Object o = m.get(label.substring(label.indexOf('.') + 1));
        if (!(o instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < min || n.longValue() > max) {
            throw fail("« " + label + " » : nombre entier de " + min + " à " + max + " attendu.");
        }
        return (int) n.longValue();
    }

    private static String oneOf(Map<String, Object> m, String label, String... values) {
        String v = Json.str(m, label.substring(label.indexOf('.') + 1));
        if (v == null || !List.of(values).contains(v)) {
            throw fail("« " + label + " » : " + String.join(" ou ", values) + " attendu.");
        }
        return v;
    }

    private static List<Object> list(Map<String, Object> m, String key, int max) {
        List<Object> l = Json.arr(m.get(key));
        if (l == null) {
            throw fail("« " + key + " » doit être une liste.");
        }
        if (l.size() > max) {
            throw fail("« " + key + " » : " + max + (max > 1 ? " éléments" : " élément") + " au plus.");
        }
        return l;
    }

    private static List<String> strings(Map<String, Object> m, String key, int max) {
        List<String> out = new ArrayList<>();
        for (Object o : list(m, key, max)) {
            if (!(o instanceof String s)) {
                throw fail("« " + key + " » : textes attendus.");
            }
            if (out.contains(s)) {
                throw fail("« " + key + " » : « " + s + " » en double.");
            }
            out.add(s);
        }
        return out;
    }

    private static Set<String> subset(Map<String, Object> m, String key, Set<String> allowed) {
        Set<String> out = new LinkedHashSet<>();
        for (String s : strings(m, key, 10)) {
            if (!allowed.contains(s)) {
                throw fail("« " + key + " » : « " + s + " » inconnu (parmi " + allowed + ").");
            }
            out.add(s);
        }
        return out;
    }

    private static final class Builder {
        String id;
        String name;
        String description;
        String author;
        String license;
        int minPlayers;
        int maxPlayers;
        final List<String> needs = new ArrayList<>();
        String links;
        String encounters;
        String team;
        final Map<String, Object> defaults = new LinkedHashMap<>();
        final Set<String> locked = new LinkedHashSet<>();
        final Set<String> win = new LinkedHashSet<>();
        final Set<String> lose = new LinkedHashSet<>();
        final List<Map<String, Object>> objectives = new ArrayList<>();
        boolean official;
    }
}
