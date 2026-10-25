package slt;

import java.util.Map;

/**
 * The shiny rate of a room: k chances out of 8192, the denominator of every game of generations 2 to 5 (their own
 * rate is 1/8192). Kept in the randomization of the room as {@code shinyNum} (k; 0 = the game's own rate); rooms of
 * older versions have {@code shinyRate} (1 in N) instead.
 *
 * <p>The DS games get it in their ROM: their shiny test {@code x < 8} (x = the 16-bit mix of the personality value
 * and the trainer's IDs) becomes {@code (x >> shift) < limit}, so the threshold {@code limit << shift} can be any
 * multiple of 8 up to 65536 (exactly, or within 1/255 when it has no exact form).
 */
public final class ShinyOdds {

    public static final int DENOMINATOR = 8192;

    private ShinyOdds() {
    }

    /** The numerator of a room's randomization (0: the game's own rate). */
    public static int numerator(Map<String, Object> rando) {
        if (rando == null) {
            return 0;
        }
        long k = Json.num(rando, "shinyNum", -1);
        if (k >= 0) {
            return (int) Math.min(DENOMINATOR, k);
        }
        long n = Json.num(rando, "shinyRate", 0);
        return n <= 0 || n >= DENOMINATOR ? 0 : (int) Math.max(1, Math.min(DENOMINATOR, Math.round((double) DENOMINATOR / n)));
    }

    /** Probability that a wild Pokémon is shiny with this numerator. */
    public static double probability(int num) {
        return num <= 1 ? 1.0 / DENOMINATOR : Math.min(1.0, (double) num / DENOMINATOR);
    }

    /** The same as "1 in N" (0: the game's own rate), for messages. */
    public static int oneIn(int num) {
        return num <= 1 ? 0 : (int) Math.max(1, Math.round((double) DENOMINATOR / num));
    }

    /** DS shiny test of this numerator: {shift, limit}; shiny when (x >> shift) < limit. */
    public static int[] dsTest(int num) {
        long threshold = num <= 1 ? 8 : Math.min(65536, 8L * num);
        int shift = 0;
        long limit = threshold;
        while (limit > 255) {
            shift++;
            limit = Math.round((double) threshold / (1L << shift));
        }
        return new int[]{shift, (int) limit};
    }

    /** The DS threshold actually written in the ROM: x below it is shiny. */
    public static int dsThreshold(int num) {
        int[] t = dsTest(num);
        return t[1] << t[0];
    }

    /** "3/8192 (1 sur 2731)", for the notes and the logs. */
    public static String label(int num) {
        if (num <= 1) {
            return "1/" + DENOMINATOR + " (celui du jeu)";
        }
        return num + "/" + DENOMINATOR + (num < DENOMINATOR ? " (1 sur " + oneIn(num) + ")" : " (tous)");
    }
}
