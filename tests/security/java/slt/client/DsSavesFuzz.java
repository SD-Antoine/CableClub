package slt.client;

import java.util.Arrays;
import java.util.Random;

/**
 * Fuzzer of the DS save conversion (security audit, point 9e): random byte arrays through DsSaves.raw / dsv / isDsv.
 * No real save is used — every input is generated. Criterion: no exception, no null, and the raw -> dsv -> raw
 * round-trip is exact.
 */
public final class DsSavesFuzz {

    private static final int ROUNDS = 5000;

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 7654321L;
        Random rnd = new Random(seed);
        int failed = 0;
        for (int i = 0; i < ROUNDS && failed <= 20; i++) {
            byte[] data = randomData(rnd);
            try {
                DsSaves.isDsv(data);
                if (DsSaves.raw(data) == null) {
                    System.out.println("  raw() a renvoyé null (len " + data.length + ")");
                    failed++;
                }
            } catch (Throwable t) {
                System.out.println("  raw()/isDsv() : " + t.getClass().getName() + " (len " + data.length + ")");
                failed++;
            }
            // round-trip: a raw save of a plausible size, with or without a "previous" DeSmuME file
            byte[] rawSave = new byte[rnd.nextInt(4096)];
            rnd.nextBytes(rawSave);
            byte[] previous = i % 3 == 0 ? data : null;
            try {
                byte[] back = DsSaves.raw(DsSaves.dsv(rawSave, previous));
                if (!Arrays.equals(rawSave, back)) {
                    System.out.println("  aller-retour dsv/raw non identique (len " + rawSave.length + ")");
                    failed++;
                }
            } catch (Throwable t) {
                System.out.println("  dsv()/raw() : " + t.getClass().getName() + " (len " + rawSave.length + ")");
                failed++;
            }
        }
        System.out.println(failed == 0 ? "ok     fuzz DsSaves (" + ROUNDS + " entrées)"
                : "ÉCHEC  fuzz DsSaves : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Random bytes, with extra weight on sizes around the 122-byte DeSmuME footer boundary. */
    private static byte[] randomData(Random rnd) {
        int n = switch (rnd.nextInt(5)) {
            case 0 -> rnd.nextInt(4);        // tiny
            case 1 -> 118 + rnd.nextInt(10); // around the footer size
            default -> rnd.nextInt(8192);
        };
        byte[] b = new byte[n];
        rnd.nextBytes(b);
        return b;
    }

    private DsSavesFuzz() {
    }
}
