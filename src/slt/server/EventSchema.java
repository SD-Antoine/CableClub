package slt.server;

import slt.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The common event vocabulary that the games' adapters emit and the referee ({@link Room}) consumes
 * (documented in docs/events.md). This validator is data-driven and used by the tests: it says whether an event
 * matches the contract. The referee itself stays tolerant — it reads fields defensively and ignores what it does
 * not know — so a real game sending a slightly different event is never rejected because of this class.
 *
 * <p>The {@code type} field selects the shape; for {@code "encounter"} and {@code "battle"} a {@code "phase"}
 * ({@code "start"} or {@code "end"}) selects it further. A required field must be present and non-null; an optional
 * field may be absent or explicitly null (e.g. {@code speciesName}, {@code nickname}).
 */
public final class EventSchema {

    enum T { STR, NUM, BOOL, ARR, OBJ }

    private record Field(String name, T type, boolean required) { }

    private static Field req(String name, T type) {
        return new Field(name, type, true);
    }

    private static Field opt(String name, T type) {
        return new Field(name, type, false);
    }

    /** Fields by event key (the type, or "type/phase" for the phased events). */
    private static final Map<String, List<Field>> SCHEMA = build();

    private static Map<String, List<Field>> build() {
        Map<String, List<Field>> m = new LinkedHashMap<>();
        m.put("location", List.of(req("zone", T.STR), opt("map", T.NUM)));
        m.put("encounter/start", List.of(req("uid", T.STR), req("zone", T.STR), req("species", T.NUM),
                req("level", T.NUM), opt("speciesName", T.STR), opt("shiny", T.BOOL), opt("balls", T.NUM)));
        m.put("encounter/end", List.of(req("uid", T.STR), opt("outcome", T.STR), opt("wildFainted", T.BOOL),
                opt("species", T.NUM), opt("speciesName", T.STR), opt("level", T.NUM), opt("zone", T.STR),
                opt("shiny", T.BOOL), opt("balls", T.NUM)));
        m.put("capture", List.of(req("uid", T.STR), req("species", T.NUM), req("level", T.NUM), req("origin", T.STR),
                opt("speciesName", T.STR), opt("nickname", T.STR), opt("zone", T.STR), opt("inParty", T.BOOL),
                opt("fainted", T.BOOL), opt("shiny", T.BOOL)));
        m.put("update", List.of(req("uid", T.STR), opt("species", T.NUM), opt("level", T.NUM),
                opt("speciesName", T.STR), opt("nickname", T.STR), opt("evolved", T.BOOL)));
        m.put("faint", List.of(req("uid", T.STR), opt("species", T.NUM), opt("level", T.NUM),
                opt("speciesName", T.STR), opt("nickname", T.STR), opt("zone", T.STR), opt("doomed", T.BOOL)));
        m.put("party", List.of(req("uids", T.ARR)));
        m.put("team", List.of(req("mons", T.ARR)));
        m.put("badges", List.of(req("count", T.NUM)));
        m.put("whiteout", List.of());
        m.put("battle/start", List.of(req("kind", T.STR), opt("size", T.NUM), opt("trainer", T.STR)));
        m.put("battle/end", List.of(req("kind", T.STR), opt("outcome", T.STR), opt("trainer", T.STR)));
        m.put("items", List.of(req("changes", T.ARR), opt("money", T.NUM), opt("battle", T.BOOL), opt("fight", T.BOOL)));
        m.put("champion", List.of());
        m.put("balls", List.of(req("has", T.BOOL)));
        m.put("caps", List.of(req("gyms", T.ARR), opt("league", T.NUM)));
        m.put("removed", List.of(req("item", T.NUM), opt("name", T.STR), opt("qty", T.NUM), opt("rule", T.STR)));
        m.put("held", List.of(req("item", T.NUM), opt("uid", T.STR), opt("nickname", T.STR), opt("name", T.STR),
                opt("rule", T.STR)));
        m.put("forgot", List.of(req("move", T.NUM), opt("uid", T.STR), opt("nickname", T.STR)));
        return m;
    }

    /** The event types known to the referee (the phased ones listed once, without their phase). */
    public static List<String> types() {
        List<String> out = new ArrayList<>();
        for (String key : SCHEMA.keySet()) {
            String type = key.contains("/") ? key.substring(0, key.indexOf('/')) : key;
            if (!out.contains(type)) {
                out.add(type);
            }
        }
        return out;
    }

    /** What is wrong with an event, as a list of messages (empty = valid). Never throws. */
    public static List<String> validate(Map<String, Object> ev) {
        List<String> out = new ArrayList<>();
        if (ev == null) {
            out.add("événement absent");
            return out;
        }
        String type = Json.str(ev, "type");
        if (type == null || type.isBlank()) {
            out.add("champ « type » manquant");
            return out;
        }
        String key = type;
        if (type.equals("encounter") || type.equals("battle")) {
            String phase = Json.str(ev, "phase");
            if (!"start".equals(phase) && !"end".equals(phase)) {
                out.add(type + " : « phase » doit être « start » ou « end »");
                return out;
            }
            key = type + "/" + phase;
        }
        List<Field> fields = SCHEMA.get(key);
        if (fields == null) {
            out.add("type d'événement inconnu : " + key);
            return out;
        }
        for (Field f : fields) {
            Object v = ev.get(f.name());
            if (v == null) {
                if (f.required()) {
                    out.add(key + " : champ « " + f.name() + " » (" + f.type() + ") manquant");
                }
                continue;
            }
            if (!typeOk(f.type(), v)) {
                out.add(key + " : « " + f.name() + " » devrait être " + f.type() + ", pas " + v.getClass().getSimpleName());
            }
        }
        if ("caps".equals(key) && ev.get("gyms") instanceof List<?> gyms && gyms.size() != 8) {
            out.add("caps : « gyms » doit contenir 8 niveaux (reçu " + gyms.size() + ")");
        }
        return out;
    }

    private static boolean typeOk(T t, Object v) {
        return switch (t) {
            case STR -> v instanceof String;
            case NUM -> v instanceof Number;
            case BOOL -> v instanceof Boolean;
            case ARR -> v instanceof List;
            case OBJ -> v instanceof Map;
        };
    }

    private EventSchema() {
    }
}
