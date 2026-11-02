package slt;

import java.util.Random;

/**
 * Fuzzer of the JSON parser (security audit, point 9e): thousands of generated, malformed and deeply nested inputs.
 * The only acceptable outcomes are a parsed value or an {@link IllegalArgumentException} (the parser's declared
 * refusal); anything else — a StackOverflowError, a NullPointerException, a StringIndexOutOfBounds — is a failure.
 * No real data is used: every input is generated. Pins R-1 too: a very deep input is refused cleanly, never with a
 * stack overflow.
 */
public final class JsonFuzz {

    private static final int ROUNDS = 5000;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 1234567L;
        Random rnd = new Random(seed);
        int failed = depthLimit();
        for (int i = 0; i < ROUNDS && failed <= 20; i++) {
            String input = switch (i % 4) {
                case 0 -> randomBytes(rnd);
                case 1 -> jsonish(rnd);
                case 2 -> nested(rnd);
                default -> truncated(rnd);
            };
            String why = badOutcome(input);
            if (why != null) {
                System.out.println("  refus anormal par " + why + "  | " + preview(input));
                failed++;
            }
        }
        System.out.println(failed == 0 ? "ok     fuzz slt.Json (" + ROUNDS + " entrées)"
                : "ÉCHEC  fuzz slt.Json : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Null when the outcome is acceptable (a value, or an IllegalArgumentException); otherwise what went wrong. */
    private static String badOutcome(String input) {
        try {
            Json.parse(input);
            return null;
        } catch (IllegalArgumentException e) {
            return null; // the declared refusal
        } catch (Throwable t) {
            return t.getClass().getName();
        }
    }

    /** R-1: a very deep input is refused with an IllegalArgumentException, never a stack overflow; a sane depth parses. */
    private static int depthLimit() {
        String deep = "[".repeat(20000) + "]".repeat(20000);
        try {
            Json.parse(deep);
            System.out.println("  profondeur : un JSON de 20000 niveaux aurait dû être refusé");
            return 1;
        } catch (IllegalArgumentException e) {
            // expected
        } catch (Throwable t) {
            System.out.println("  profondeur : refus par " + t.getClass().getName() + " au lieu d'IllegalArgumentException");
            return 1;
        }
        try {
            Json.parse("[".repeat(100) + "1" + "]".repeat(100));
            return 0;
        } catch (Throwable t) {
            System.out.println("  profondeur : 100 niveaux auraient dû passer (" + t.getClass().getName() + ")");
            return 1;
        }
    }

    private static String randomBytes(Random rnd) {
        int n = rnd.nextInt(256);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append((char) rnd.nextInt(128));
        }
        return sb.toString();
    }

    private static final char[] TOK = "{}[]:,\"\\ \t\n0123456789.-+eEtfnurl aAzZ".toCharArray();

    private static String jsonish(Random rnd) {
        int n = rnd.nextInt(200);
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            sb.append(TOK[rnd.nextInt(TOK.length)]);
        }
        return sb.toString();
    }

    private static String nested(Random rnd) {
        int d = rnd.nextInt(700); // around and past the 512 limit
        boolean arr = rnd.nextBoolean();
        String open = arr ? "[" : "{\"k\":";
        String close = arr ? "]" : "}";
        return open.repeat(d) + (rnd.nextBoolean() ? "1" : "null") + close.repeat(d);
    }

    private static String truncated(Random rnd) {
        String[] bits = {"{\"a\":", "[1,2,", "\"abc", "{\"x\":{\"y\":", "-", "12.", "\\u12", "tru", "[", "{"};
        StringBuilder sb = new StringBuilder();
        int n = 1 + rnd.nextInt(5);
        for (int i = 0; i < n; i++) {
            sb.append(bits[rnd.nextInt(bits.length)]);
        }
        return sb.toString();
    }

    private static String preview(String s) {
        String p = s.length() > 40 ? s.substring(0, 40) + "…" : s;
        return p.replace("\n", "\\n").replace("\t", "\\t");
    }

    private JsonFuzz() {
    }
}
