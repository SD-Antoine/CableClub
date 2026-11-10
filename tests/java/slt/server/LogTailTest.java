package slt.server;

import slt.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * The journal of a room read from the end ({@link Rooms#tail}, point 6a of the follow-up of the audit of 2026-11-10)
 * gives exactly what the former reading of the whole file gave: random journals (up to 5000 lines, lines longer than
 * a block, accents and emoji, blank lines, Windows line ends, no last line end), many starts and limits.
 */
public final class LogTailTest {

    private static int failed;

    public static void main(String[] args) throws Exception {
        Random random = new Random(7);
        Path f = Files.createTempFile("cableclub-journal", ".jsonl");
        int cases = 0;
        try {
            for (int j = 0; j < 60; j++) {
                int n = j == 0 ? 0 : j == 1 ? 1 : random.nextInt(j < 50 ? 300 : 5000);
                StringBuilder sb = new StringBuilder();
                String eol = j % 7 == 3 ? "\r\n" : "\n";
                for (int i = 1; i <= n; i++) {
                    if (random.nextInt(40) == 0) {
                        sb.append(eol); // a blank line
                    }
                    int len = random.nextInt(25) == 0 ? 70_000 + random.nextInt(70_000) : random.nextInt(200);
                    String text = "é🎮 " + "x".repeat(len);
                    sb.append(Json.stringify(Json.map("seq", (long) i, "type", "test", "text", text)));
                    if (i < n || j % 5 != 2) {
                        sb.append(eol); // sometimes no line end after the last line
                    }
                }
                Files.writeString(f, sb.toString(), StandardCharsets.UTF_8);
                for (long after : new long[]{0, 1, n / 2, Math.max(0, n - 3), n, n + 5, random.nextInt(n + 1)}) {
                    for (int limit : new int[]{1, 2, 5, 1000, 2000, 5000}) {
                        cases++;
                        String expected = Json.stringify(former(f, after, limit));
                        String got = Json.stringify(RoomJournal.tail(f, after, limit));
                        if (!expected.equals(got)) {
                            failed++;
                            if (failed <= 5) {
                                System.out.println("  échec : journal " + j + " (" + n + " lignes), après " + after + ", " + limit
                                        + " au plus");
                            }
                        }
                    }
                }
            }
            Files.writeString(f, "");
            cases++;
            check(RoomJournal.tail(f, 0, 10).isEmpty(), "journal vide");
        } finally {
            Files.deleteIfExists(f);
        }
        System.out.println(failed == 0 ? "ok     journal lu depuis la fin (" + cases + " cas identiques à la lecture complète)"
                : "ÉCHEC  journal lu depuis la fin : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** The former reading: every line, then the last {@code limit} after {@code after}. */
    private static List<Object> former(Path f, long after, int limit) throws Exception {
        List<Object> out = new ArrayList<>();
        for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, Object> l = Json.parseObject(line);
            if (Json.num(l, "seq", 0) > after) {
                out.add(l);
            }
        }
        return out.size() > limit ? new ArrayList<>(out.subList(out.size() - limit, out.size())) : out;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
