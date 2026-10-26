package slt.player;

/**
 * A change of map read before the player's new coordinates: through a door or stairs, the games set the new map a
 * few images before the position on it (gen 1: about 8 images, then the fade). Meanwhile the position read is still
 * the old map's: it must not be shown to the others on the new map. The wait ends when the position moves, when a
 * fade (or a message) has gone by, or after 3 seconds.
 */
final class MapChange {

    private static final int MAX_IMAGES = 180;

    private int map = -1;
    private int x;
    private int y;
    private boolean busySeen;
    private int images;

    /**
     * One reading (positions in tiles, as the game keeps them), with the map and position of the previous one.
     *
     * @param busy a fade, a menu or a message is on the screen
     * @return true while the position read is not yet the one on the new map
     */
    boolean pending(int map, int x, int y, int lastMap, int lastX, int lastY, boolean busy) {
        if (map != lastMap && lastMap >= 0 && x == lastX && y == lastY) {
            this.map = map;
            this.x = x;
            this.y = y;
            busySeen = false;
            images = 0;
        }
        if (this.map < 0) {
            return false;
        }
        if (map != this.map) {
            this.map = -1;
            return false;
        }
        images++;
        busySeen |= busy;
        if (x != this.x || y != this.y || (busySeen && !busy) || images > MAX_IMAGES) {
            this.map = -1;
            return false;
        }
        return true;
    }
}
