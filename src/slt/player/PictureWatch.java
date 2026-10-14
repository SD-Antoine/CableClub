package slt.player;

/**
 * Whether the top screen of a DS game shows another picture than on the image before. The 3D games of the DS work at
 * 30 images a second: the picture changes every other image, and what the RAM holds (camera, positions) changes on
 * the same image as the picture or on the other one, depending on the game. The window must place the other players
 * with what went with the picture it shows, so the readings are kept from one change of the picture to the next
 * (placed with the very latest camera, they shook by a step, 30 times a second).
 *
 * <p>A sample of the pixels is compared with the one of the previous image: a picture that scrolls changes a third of
 * them or more, the animations of the scenery (water, flowers) a few in a hundred.
 */
final class PictureWatch {

    private static final int COLUMNS = 86;
    private static final int ROWS = 64;

    private int[] last;

    /** True when the picture changed (or cannot be told). Call it once per image. */
    boolean changed(CoreHost core) {
        int w = core.frameWidth();
        int[] px = core.pixels();
        if (w != 256 || core.frameHeight() < 192 || px.length < w * 192) {
            last = null;
            return true;
        }
        boolean first = last == null;
        if (first) {
            last = new int[COLUMNS * ROWS];
        }
        int i = 0;
        int diff = 0;
        for (int y = 1; y < 192; y += 3) {
            for (int x = 1; x < 256; x += 3) {
                int c = px[y * w + x];
                int p = last[i];
                if (!first && Math.abs(((c >> 16) & 0xFF) - ((p >> 16) & 0xFF)) + Math.abs(((c >> 8) & 0xFF) - ((p >> 8) & 0xFF))
                        + Math.abs((c & 0xFF) - (p & 0xFF)) > 24) {
                    diff++;
                }
                last[i++] = c;
            }
        }
        return first || diff * 100 > i * 3;
    }
}
