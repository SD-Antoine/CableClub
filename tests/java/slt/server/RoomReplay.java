package slt.server;

import slt.Http;
import slt.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Non-regression harness of the referee: replays scenarios of tracker events on a {@link Room} and compares what it
 * produces (journal lines, notices, directives of each game, final state) with the reference saved next to each
 * scenario. The room is driven the way {@link Rooms} drives it, without the server around it (no disk, no network);
 * the times of the wall clock are left out.
 *
 * <pre>
 *   tests\golden.ps1           replays tests\golden\*.json and compares with their .txt references
 *   tests\golden.ps1 -Update   writes the references again: only for a change of behaviour that was decided
 * </pre>
 *
 * A scenario: {"title", "mode", "game": {"id", "name", "gen"}, "rules": {...}, "rando": {...},
 * "players": [{"id", "name", "families": {"species": first species of its evolution line}}], "steps": [...]}.
 * Its steps (the first player is the leader; "note" is a comment kept in the reference):
 * <pre>
 *   {"do": "launch"}                                   the leader launches the adventure
 *   {"by": id, "events": [...], "shown": "battle"}      tracker events of a game ("shown": what its screen shows)
 *   {"do": "name", "zone": key, "nickname": "..."}     the leader names a link
 *   {"by": id, "do": "hm", "on": true}                 "Exception pour les CS": keep the next forbidden capture
 *   {"by": id, "do": "ack", "ids": ["1"]}              the game gave the refunded items back
 *   {"do": "adventure", "open": false}                 the leader closes (or opens) the adventure
 *   {"do": "rules", "rules": {...}}                    the leader changes rules during the run
 *   {"do": "end", "outcome": "victory"}                the leader ends the run (victory, defeat, abandon)
 * </pre>
 */
public final class RoomReplay {

    /** Times of the wall clock: replaced by 0 in the output. */
    private static final Set<String> TIMES = Set.of("at", "firstAt", "launchedAt", "createdAt", "joinedAt");

    /** An error in a scenario (not in the room): stops it. */
    private static final class Bad extends RuntimeException {
        Bad(String message) {
            super(message);
        }
    }

    public static void main(String[] args) throws IOException {
        boolean update = false;
        Path dir = Path.of("tests", "golden");
        for (String a : args) {
            if ("--update".equals(a)) {
                update = true;
            } else {
                dir = Path.of(a);
            }
        }
        List<Path> scenarios;
        try (Stream<Path> s = Files.list(dir)) {
            scenarios = s.filter(p -> p.toString().endsWith(".json")).sorted().toList();
        }
        int failed = 0;
        for (Path p : scenarios) {
            String name = p.getFileName().toString().replaceFirst("\\.json$", "");
            Path ref = dir.resolve(name + ".txt");
            String got;
            try {
                got = replay(Json.parseObject(Files.readString(p, StandardCharsets.UTF_8)));
            } catch (RuntimeException e) {
                System.out.println("ÉCHEC  " + name + " : scénario invalide, " + e.getMessage());
                failed++;
                continue;
            }
            if (update) {
                Files.writeString(ref, got, StandardCharsets.UTF_8);
                System.out.println("écrit  " + name);
                continue;
            }
            String expected = Files.exists(ref) ? Files.readString(ref, StandardCharsets.UTF_8).replace("\r\n", "\n") : null;
            if (expected == null) {
                System.out.println("ÉCHEC  " + name + " : pas de référence (tests\\golden.ps1 -Update)");
                failed++;
            } else if (!expected.equals(got)) {
                System.out.println("ÉCHEC  " + name + " : " + difference(expected, got));
                failed++;
            } else {
                System.out.println("ok     " + name);
            }
        }
        System.out.println(update ? scenarios.size() - failed + " référence(s) écrite(s)"
                : scenarios.size() - failed + " / " + scenarios.size() + " scénarios conformes");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** The first line that differs, with the step it belongs to. */
    private static String difference(String expected, String got) {
        String[] a = expected.split("\n", -1);
        String[] b = got.split("\n", -1);
        int i = 0;
        while (i < a.length && i < b.length && a[i].equals(b[i])) {
            i++;
        }
        String step = "";
        for (int j = Math.min(i, b.length - 1); j >= 0; j--) {
            if (!b[j].startsWith(" ") && !b[j].isEmpty()) {
                step = b[j];
                break;
            }
        }
        return "ligne " + (i + 1) + " (" + step + ")\n    attendu : " + (i < a.length ? a[i] : "(fin)")
                + "\n    obtenu  : " + (i < b.length ? b[i] : "(fin)");
    }

    // ------------------------------------------------------------------ replay

    static String replay(Map<String, Object> sc) {
        Room r = new Room();
        r.id = "salle";
        r.code = "CODE01";
        r.name = Json.str(sc, "title");
        String mode = Json.str(sc, "mode");
        if (mode == null || !Room.MODES.contains(mode)) {
            throw new Bad("mode inconnu : " + mode);
        }
        r.mode = mode;
        Map<String, Object> game = Json.obj(sc.get("game"));
        if (game == null) {
            throw new Bad("jeu absent");
        }
        r.game = Json.map("gameId", Json.str(game, "id"), "name", Json.str(game, "name"), "gen", Json.num(game, "gen", 0));
        r.rules = Room.defaultRules(r.mode);
        Map<String, Object> rules = Json.obj(sc.get("rules"));
        if (rules != null) {
            for (Map.Entry<String, Object> e : rules.entrySet()) {
                if (!r.rules.containsKey(e.getKey())) {
                    throw new Bad("règle inconnue du mode " + r.mode + " : " + e.getKey());
                }
                r.rules.put(e.getKey(), e.getValue());
            }
        }
        Map<String, Object> rando = Json.obj(sc.get("rando"));
        r.rando = rando == null ? new LinkedHashMap<>() : rando;
        List<Object> players = Json.arr(sc.get("players"));
        if (players == null || players.isEmpty()) {
            throw new Bad("aucun joueur");
        }
        for (Object o : players) {
            r.members.add(member(Json.obj(o), r.members.size() + 1));
        }
        r.leaderId = r.members.get(0).userId;
        r.maxPlayers = r.members.size();

        StringBuilder out = new StringBuilder();
        out.append("# ").append(r.name).append('\n');
        out.append("mode ").append(r.mode).append(", ").append(r.gameName()).append(", génération ")
                .append(Json.num(r.game, "gen", 0)).append(", joueurs ");
        for (Room.Member m : r.members) {
            out.append(m.userId).append(m.userId.equals(r.leaderId) ? " (chef)" : "").append(m == r.members.getLast() ? "" : ", ");
        }
        out.append('\n').append("règles ").append(Json.stringify(r.rules)).append('\n');
        if (!r.rando.isEmpty()) {
            out.append("randomisation ").append(Json.stringify(r.rando)).append('\n');
        }

        Map<String, Map<String, Object>> told = new LinkedHashMap<>();  // last directives of each game
        int n = 0;
        for (Object o : Json.arr(sc.get("steps")) == null ? List.of() : Json.arr(sc.get("steps"))) {
            Map<String, Object> step = Json.obj(o);
            out.append('\n').append(++n).append(". ").append(describe(r, step)).append('\n');
            Room.Out ro = new Room.Out();
            try {
                apply(r, step, ro);
            } catch (Http.Fail f) {
                out.append("  refus ").append(f.status).append(" : ").append(f.getMessage()).append('\n');
            } catch (Bad e) {
                throw new Bad("étape " + n + " : " + e.getMessage());
            } catch (RuntimeException e) {
                out.append("  exception ").append(e.getClass().getSimpleName()).append('\n');
            }
            for (Map<String, Object> l : ro.logs) {
                out.append("  journal ").append(l.get("type"));
                if (l.get("user") != null) {
                    out.append(" [").append(l.get("user")).append(']');
                }
                out.append(" : ").append(l.get("text"));
                if (l.get("data") != null) {
                    out.append("  ").append(Json.stringify(l.get("data")));
                }
                out.append('\n');
            }
            for (Map.Entry<String, List<Map<String, Object>>> e : ro.notices.entrySet()) {
                for (Map<String, Object> x : e.getValue()) {
                    out.append("  avis [").append(e.getKey()).append("] ").append(x.get("level")).append(" : ")
                            .append(x.get("text")).append('\n');
                }
            }
            if (ro.ended) {
                out.append("  fin de l'aventure\n");
            }
            for (Room.Member m : r.members) {
                Map<String, Object> d = normalize(r.directives(m));
                Map<String, Object> before = told.get(m.userId);
                List<String> changed = new ArrayList<>();
                for (Map.Entry<String, Object> e : d.entrySet()) {
                    String v = Json.stringify(e.getValue());
                    if (before == null || !v.equals(Json.stringify(before.get(e.getKey())))) {
                        changed.add(e.getKey() + "=" + v);
                    }
                }
                if (!changed.isEmpty()) {
                    out.append("  directives [").append(m.userId).append("] ").append(String.join(", ", changed)).append('\n');
                }
                told.put(m.userId, d);
            }
        }

        out.append("\nÉtat final\n  ");
        pretty(out, normalize(r.toStore()), "  ");
        out.append('\n');
        for (Room.Member m : r.members) {
            out.append("Vue en direct [").append(m.userId).append("] ");
            pretty(out, normalize(m.live), "");
            out.append('\n');
        }
        return out.toString();
    }

    private static Room.Member member(Map<String, Object> p, int slot) {
        if (p == null || Json.str(p, "id") == null) {
            throw new Bad("joueur sans id");
        }
        Room.Member m = new Room.Member();
        m.userId = Json.str(p, "id");
        m.name = Json.str(p, "name") == null ? m.userId : Json.str(p, "name");
        m.slot = slot;
        m.ready = true;
        m.rom = Json.map("status", "ready");
        // the evolution lines of the player's ROM: species -> first species of its line (0: itself)
        Map<String, Object> fam = Json.obj(p.get("families"));
        if (fam != null) {
            int max = 0;
            for (String k : fam.keySet()) {
                max = Math.max(max, Integer.parseInt(k));
            }
            m.families = new int[max + 1];
            for (Map.Entry<String, Object> e : fam.entrySet()) {
                m.families[Integer.parseInt(e.getKey())] = ((Number) e.getValue()).intValue();
            }
        }
        return m;
    }

    private static String describe(Room r, Map<String, Object> step) {
        String by = Json.str(step, "by");
        String what = Json.str(step, "do");
        StringBuilder sb = new StringBuilder(by == null ? r.leaderId + " (chef)" : by).append(" : ");
        if (what == null) {
            List<String> types = new ArrayList<>();
            for (Object o : Json.arr(step.get("events")) == null ? List.of() : Json.arr(step.get("events"))) {
                Map<String, Object> ev = Json.obj(o);
                String t = Json.str(ev, "type");
                types.add(Json.str(ev, "phase") == null ? t : t + " " + Json.str(ev, "phase"));
            }
            sb.append(String.join(", ", types));
            if (Json.str(step, "shown") != null) {
                sb.append(" (écran : ").append(Json.str(step, "shown")).append(')');
            }
        } else {
            sb.append(what);
        }
        if (Json.str(step, "note") != null) {
            sb.append(" — ").append(Json.str(step, "note"));
        }
        return sb.toString();
    }

    /** One step, as the matching method of {@link Rooms} applies it to the room. */
    private static void apply(Room r, Map<String, Object> step, Room.Out out) {
        String by = Json.str(step, "by");
        Room.Member m = r.member(by == null ? r.leaderId : by);
        if (m == null) {
            throw new Bad("joueur inconnu : " + by);
        }
        String what = Json.str(step, "do");
        switch (what == null ? "events" : what) {
            case "launch" -> launch(r, out);
            case "events" -> {
                // Rooms.events: what the game shows, then its events in order
                String shown = Json.str(step, "shown");
                m.shown = shown;
                List<Object> events = Json.arr(step.get("events"));
                if (events == null) {
                    throw new Bad("étape sans événements");
                }
                for (Object o : events) {
                    Map<String, Object> ev = Json.obj(o);
                    if (ev != null) {
                        r.event(m, ev, out);
                    }
                }
                if ("free".equals(shown) || "dialogue".equals(shown)) {
                    m.live.remove("battle");
                }
            }
            case "name" -> r.name(Json.str(step, "zone"), Json.str(step, "nickname"), out);
            case "hm" -> r.hmNext(m, Json.bool(step, "on", true), out);
            case "ack" -> r.ackRefunds(m, Json.arr(step.get("ids")) == null ? List.of() : Json.arr(step.get("ids")));
            case "adventure" -> {
                // Rooms.adventure
                boolean open = Json.bool(step, "open", true);
                if ("running".equals(r.state) && r.adventureOpen != open) {
                    r.adventureOpen = open;
                    out.log(m, open ? "open" : "close", open ? "Le chef de salle ouvre l'aventure : à vos manettes !"
                            : "Le chef de salle ferme l'aventure : pause pour tout le monde.", null);
                }
            }
            case "rules" -> rules(r, m, Json.obj(step.get("rules")), out);
            case "end" -> {
                // Rooms.end
                String outcome = Json.str(step, "outcome");
                String o = "victory".equals(outcome) ? "victory" : "defeat".equals(outcome) ? "defeat" : "abandon";
                String reason = switch (o) {
                    case "victory" -> "Victoire déclarée par le chef de salle.";
                    case "defeat" -> "Défaite déclarée par le chef de salle.";
                    default -> "Aventure arrêtée par le chef de salle.";
                };
                r.finish(o, reason, out);
            }
            default -> throw new Bad("action inconnue : " + what);
        }
    }

    /** Rooms.launch, once its checks passed (every player ready with the same ROM). */
    private static void launch(Room r, Room.Out out) {
        if (!"lobby".equals(r.state)) {
            throw new Http.Fail(409, "L'aventure est déjà lancée.");
        }
        r.invites.clear();
        r.state = "running";
        r.adventureOpen = true;
        r.launchedAt = 1;
        out.log(null, "launch", "L'aventure commence ! " + r.members.size() + " joueur(s) sur " + r.gameName() + ".", null);
        if (r.rule("startWithBalls")) {
            out.log(null, "info", r.individual()
                    ? "Le " + r.runName() + " de chaque joueur commence à sa première Poké Ball : d'ici là, un Pokémon K.O. ne meurt pas."
                    : "Le Soul Link commencera vraiment quand chaque joueur aura une Poké Ball : "
                    + "d'ici là, un Pokémon K.O. ne meurt pas.", null);
        }
        r.checkStart(out);
    }

    /** The rules part of Rooms.settings. */
    private static void rules(Room r, Room.Member leader, Map<String, Object> rules, Room.Out out) {
        if (rules == null) {
            throw new Bad("étape sans règles");
        }
        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, Object> e : rules.entrySet()) {
            if (!Room.defaultRules(r.mode).containsKey(e.getKey())) {
                continue;
            }
            Object before = r.rules.get(e.getKey());
            if (before == null ? e.getValue() != null : !before.equals(e.getValue())) {
                if (Room.ROM_RULES.contains(e.getKey()) && !"lobby".equals(r.state)) {
                    throw new Http.Fail(409, "Cette règle est écrite dans la ROM de la salle : elle ne peut plus changer une fois l'aventure lancée.");
                }
                r.rules.put(e.getKey(), e.getValue());
                changed.add(e.getKey());
            }
        }
        if (!changed.isEmpty() && !"lobby".equals(r.state)) {
            out.log(leader, "rules", leader.name + " modifie les règles : " + String.join(", ", changed), null);
        }
    }

    // ------------------------------------------------------------------ output

    /** A copy without the times of the wall clock, with the evolution lines as species -> first of the line. */
    @SuppressWarnings("unchecked")
    private static <T> T normalize(T v) {
        if (v instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String k = String.valueOf(e.getKey());
                Object x = e.getValue();
                if (TIMES.contains(k) && x instanceof Number) {
                    out.put(k, 0L);
                } else if ("families".equals(k) && x instanceof int[] fam) {
                    Map<String, Object> lines = new TreeMap<>((a, b) -> Integer.compare(Integer.parseInt(a), Integer.parseInt(b)));
                    for (int i = 0; i < fam.length; i++) {
                        if (fam[i] > 0 && fam[i] != i) {
                            lines.put(Integer.toString(i), (long) fam[i]);
                        }
                    }
                    out.put(k, lines);
                } else {
                    out.put(k, normalize(x));
                }
            }
            return (T) out;
        }
        if (v instanceof Collection<?> c) {
            List<Object> out = new ArrayList<>();
            for (Object x : c) {
                out.add(normalize(x));
            }
            return (T) out;
        }
        return v;
    }

    /** JSON over several lines when it does not hold on one. */
    private static void pretty(StringBuilder sb, Object v, String indent) {
        String flat = Json.stringify(v);
        if (flat.length() + indent.length() <= 110 || !(v instanceof Map<?, ?> || v instanceof Collection<?>)) {
            sb.append(flat);
            return;
        }
        String inner = indent + "  ";
        if (v instanceof Map<?, ?> map) {
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : map.entrySet()) {
                sb.append(inner).append(Json.stringify(String.valueOf(e.getKey()))).append(": ");
                if ("families".equals(e.getKey())) {
                    sb.append(Json.stringify(e.getValue())); // data of the scenario: one line
                } else {
                    pretty(sb, e.getValue(), inner);
                }
                sb.append(++i < map.size() ? ",\n" : "\n");
            }
            sb.append(indent).append('}');
        } else {
            Collection<?> c = (Collection<?>) v;
            sb.append("[\n");
            int i = 0;
            for (Object x : c) {
                sb.append(inner);
                pretty(sb, x, inner);
                sb.append(++i < c.size() ? ",\n" : "\n");
            }
            sb.append(indent).append(']');
        }
    }
}
