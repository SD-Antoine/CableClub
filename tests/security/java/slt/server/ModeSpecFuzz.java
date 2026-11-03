package slt.server;

import slt.Http;
import slt.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Fuzzer of the mode file validator (security re-audit of point 2, before modes are published): thousands of mode
 * files derived from valid ones — a field replaced by a value of another type, removed, added, a text full of
 * unusual characters — plus random bytes. The only acceptable outcomes are a mode, or a refusal with status 400;
 * anything else (a NullPointerException, a ClassCastException…) would be a 500 or an unreadable room. Every accepted
 * mode must also read back the same from its normalized form, and give a mode engine. All inputs are generated.
 */
public final class ModeSpecFuzz {

    private static final int ROUNDS = 6000;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 20261103L;
        Random rnd = new Random(seed);
        List<String> bases = new ArrayList<>();
        for (ModeSpec s : ModeSpec.officials().values()) {
            Map<String, Object> m = s.toJson();
            m.put("id", s.id + "-copie");
            m.put("name", "Copie " + s.id);
            m.put("author", "Testeur");
            Map<String, Object> rules = Json.obj(m.get("rules"));
            rules.remove("honorNoEvolution"); // reserved to the official modes
            bases.add(Json.stringify(m));
        }
        int failed = texts(bases.get(1));
        int accepted = 0;
        for (int i = 0; i < ROUNDS && failed <= 20; i++) {
            String input = i % 10 == 9 ? randomText(rnd, 1 + rnd.nextInt(300))
                    : Json.stringify(mutate(Json.parseObject(bases.get(rnd.nextInt(bases.size()))), rnd, 1 + rnd.nextInt(3)));
            String why = badOutcome(input);
            if (why == null) {
                continue;
            }
            if (why.equals("accepted")) {
                accepted++;
                continue;
            }
            System.out.println("  " + why + "  | " + (input.length() > 160 ? input.substring(0, 160) + "…" : input));
            failed++;
        }
        System.out.println(failed == 0 ? "ok     fuzz ModeSpec (" + ROUNDS + " fichiers de mode, " + accepted + " acceptés)"
                : "ÉCHEC  fuzz ModeSpec : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /**
     * Texts a mode may not carry, each refused (pinned by the re-audit): invisible characters that reorder or hide
     * text (bidi controls, zero width), C1 controls, lone surrogates, an address written with a look-alike dot or a
     * hidden character, and the names of the official modes or of Cable Club itself (an imported mode passing off as
     * an official one).
     */
    private static int texts(String base) {
        String[][] cases = {
            {"description", "Mode normal ‮evil"}, {"name", "Mode⁦ caché⁩"}, {"description", "un​deux"},
            {"description", "suite﻿"}, {"name", "Mode\u0085"}, {"name", "Mode\u007F"}, {"author", "Moi\uD800"},
            {"description", "La ROM sur rom​.com"}, {"description", "La ROM sur rom．com"},
            {"description", "La ROM sur rom。com"}, {"description", "La ROM sur ｒｏｍ．ｃｏｍ"},
            {"description", "La ROM sur rom﹒com"}, {"description", "voir WWW.exemple.test"},
            {"name", "Nuzlocke Hardcore"}, {"name", "soul  link"}, {"name", "ＳＯＵＬ ＬＩＮＫ"},
            {"author", "Cable Club"}, {"author", "cableclub"},
            {"description", "Icône : data:image/png;base64,iVBORw0KGgo"},
            {"description", "Fichier : " + "QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVo".repeat(3)},
        };
        int failed = 0;
        for (String[] c : cases) {
            Map<String, Object> m = Json.parseObject(base);
            m.put(c[0], c[1]);
            try {
                ModeSpec.parse(Json.stringify(m), false);
                System.out.println("  accepté à tort : " + c[0] + " = " + Json.stringify(c[1]));
                failed++;
            } catch (Http.Fail f) {
                if (f.status != 400) {
                    System.out.println("  refus " + f.status + " pour " + c[0]);
                    failed++;
                }
            }
        }
        // what stays allowed: accents, apostrophes, emoji, a line break in a description, a dot in a sentence
        Map<String, Object> ok = Json.parseObject(base);
        ok.put("name", "Défi d’Élise 🔥");
        ok.put("description", "Première ligne.\nDeuxième ligne : 3.5 badges, c’est trop. Fin.Suite");
        try {
            ModeSpec.parse(Json.stringify(ok), false);
        } catch (Http.Fail f) {
            System.out.println("  refusé à tort : " + f.getMessage());
            failed++;
        }
        return failed;
    }

    /** "accepted", null for a clean refusal, otherwise what went wrong. */
    private static String badOutcome(String input) {
        ModeSpec spec;
        try {
            spec = ModeSpec.parse(input, false);
        } catch (Http.Fail f) {
            return f.status == 400 ? null : "refus " + f.status;
        } catch (Throwable t) {
            return "exception " + t.getClass().getName();
        }
        try {
            String once = Json.stringify(spec.toJson());
            if (!Json.stringify(ModeSpec.parse(once, false).toJson()).equals(once)) {
                return "forme normalisée relue différente";
            }
            if (once.length() > 8 * 1024) {
                return "forme normalisée de " + once.length() + " caractères";
            }
            Room r = new Room();
            r.customSpec = spec;
            r.mode = spec.id;
            r.rules = spec.defaults();
            Mode.forRoom(r);
            Room.fromStore(Json.parseObject(Json.stringify(r.toStore())));
        } catch (Throwable t) {
            return "mode accepté mais inutilisable : " + t;
        }
        return "accepted";
    }

    @SuppressWarnings("unchecked")
    private static Object mutate(Object node, Random rnd, int times) {
        for (int n = 0; n < times; n++) {
            // a random place: walk down a few levels
            Object cur = node;
            for (int depth = 0; depth < 3; depth++) {
                if (cur instanceof Map<?, ?> m && !m.isEmpty() && rnd.nextBoolean()) {
                    Object next = m.get(new ArrayList<>(m.keySet()).get(rnd.nextInt(m.size())));
                    if (next instanceof Map || next instanceof List) {
                        cur = next;
                    }
                } else if (cur instanceof List<?> l && !l.isEmpty() && rnd.nextBoolean()) {
                    Object next = l.get(rnd.nextInt(l.size()));
                    if (next instanceof Map || next instanceof List) {
                        cur = next;
                    }
                }
            }
            if (cur instanceof Map<?, ?> raw) {
                Map<String, Object> m = (Map<String, Object>) raw;
                List<String> keys = new ArrayList<>(m.keySet());
                switch (rnd.nextInt(4)) {
                    case 0 -> { if (!keys.isEmpty()) m.remove(keys.get(rnd.nextInt(keys.size()))); }
                    case 1 -> m.put(randomKey(rnd), randomValue(rnd, 0));
                    default -> { if (!keys.isEmpty()) m.put(keys.get(rnd.nextInt(keys.size())), randomValue(rnd, 0)); }
                }
            } else if (cur instanceof List<?> raw) {
                List<Object> l = (List<Object>) raw;
                if (!l.isEmpty() && rnd.nextBoolean()) {
                    l.set(rnd.nextInt(l.size()), randomValue(rnd, 0));
                } else {
                    l.add(randomValue(rnd, 0));
                }
            }
        }
        return node;
    }

    private static final String[] KEYS = {"schemaVersion", "id", "name", "description", "author", "license", "players",
        "needs", "engine", "rules", "win", "lose", "objectives", "min", "max", "links", "team", "encounters", "default",
        "locked", "type", "count", "hardcore", "honorNoEvolution", "shinyClause", "soloLoss", "__proto__", "", "script"};
    private static final String[] WORDS = {"shared", "none", "firstPerZone", "shiniesOnly", "free", "one", "champion",
        "teamWiped", "soloLost", "badges", "captures", "shinies", "heldItems", "auto", "honor", "next", "end", "all",
        "first", "leader", "report", "lose", "nuzlocke", "soullink"};

    private static String randomKey(Random rnd) {
        return rnd.nextInt(5) == 0 ? randomText(rnd, 1 + rnd.nextInt(8)) : KEYS[rnd.nextInt(KEYS.length)];
    }

    private static Object randomValue(Random rnd, int depth) {
        return switch (rnd.nextInt(depth > 3 ? 7 : 9)) {
            case 0 -> null;
            case 1 -> rnd.nextBoolean();
            case 2 -> (long) (rnd.nextInt(25) - 5);
            case 3 -> switch (rnd.nextInt(5)) {
                case 0 -> Long.MAX_VALUE;
                case 1 -> Long.MIN_VALUE;
                case 2 -> 1e300;
                case 3 -> 2.5;
                default -> -0.0;
            };
            case 4 -> WORDS[rnd.nextInt(WORDS.length)];
            case 5 -> randomText(rnd, rnd.nextInt(700));
            case 6 -> "x".repeat(rnd.nextInt(2) == 0 ? 40 : 41);
            case 7 -> {
                List<Object> l = new ArrayList<>();
                for (int i = rnd.nextInt(12); i > 0; i--) {
                    l.add(randomValue(rnd, depth + 1));
                }
                yield l;
            }
            default -> {
                Map<String, Object> m = new LinkedHashMap<>();
                for (int i = rnd.nextInt(5); i > 0; i--) {
                    m.put(randomKey(rnd), randomValue(rnd, depth + 1));
                }
                yield m;
            }
        };
    }

    // texts with what a validator easily forgets: controls, format characters (bidi, zero width), surrogates,
    // look-alike dots, markup, combining marks
    private static final int[] ODD = {0x00, 0x07, 0x0A, 0x0D, 0x1B, 0x7F, 0x85, 0x9B, 0xA0, 0x200B, 0x200D, 0x200E,
        0x202E, 0x2066, 0x2069, 0xFEFF, 0xD800, 0xDFFF, 0xFF0E, 0x3002, 0xFE52, 0x2024, 0x0301, '<', '>', '&', '"',
        '\\', '/', ':', '.', 0x1F600, 0x10FFFF};

    private static String randomText(Random rnd, int length) {
        StringBuilder sb = new StringBuilder();
        while (sb.length() < length) {
            int pick = rnd.nextInt(10);
            if (pick < 5) {
                sb.append((char) ('a' + rnd.nextInt(26)));
            } else if (pick < 8) {
                sb.appendCodePoint(ODD[rnd.nextInt(ODD.length)]);
            } else if (pick == 8) {
                sb.append(rnd.nextBoolean() ? "rom.com" : "http://x");
            } else {
                sb.append(' ');
            }
        }
        return sb.toString();
    }
}
