package slt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Messages telling the other players of a room where a player stands in their
 * game ("ghosts"): the latest position, the way walked since the previous
 * message ("pts": [time, map, x, y, sprite id] on each image where it changed,
 * with the sender's clock) and the sprites not sent yet ("sprs": id -> [png,
 * width, height]). A message waiting behind another one being sent is merged
 * with the next one, so that no step of the way is lost.
 */
public final class Ghosts {

    /** Points of the way kept at most (4 s of walking, one point per image). */
    public static final int MAX_POINTS = 240;
    /** Sprites of one player kept at most (walking frames in 4 directions, bike, surf…). */
    public static final int MAX_SPRITES = 48;

    private Ghosts() {
    }

    /** The newer message, with the way and the sprites of the older one before its own. */
    public static Map<String, Object> merge(Map<String, Object> older, Map<String, Object> newer) {
        if (older == null) {
            return newer;
        }
        if (older.containsKey("spr") && !newer.containsKey("spr")) {
            newer.put("spr", older.get("spr"));
            newer.put("sw", older.get("sw"));
            newer.put("sh", older.get("sh"));
        }
        List<Object> before = Json.arr(older.get("pts"));
        if (before != null) {
            List<Object> all = new ArrayList<>(before);
            List<Object> after = Json.arr(newer.get("pts"));
            if (after != null) {
                all.addAll(after);
            }
            newer.put("pts", trimPoints(all));
        }
        Map<String, Object> sprites = Json.obj(older.get("sprs"));
        if (sprites != null) {
            Map<String, Object> all = new LinkedHashMap<>(sprites);
            Map<String, Object> mine = Json.obj(newer.get("sprs"));
            if (mine != null) {
                all.putAll(mine);
            }
            newer.put("sprs", trimSprites(all));
        }
        return newer;
    }

    /** The last {@link #MAX_POINTS} points of a way. */
    public static List<Object> trimPoints(List<Object> pts) {
        return pts.size() <= MAX_POINTS ? pts : new ArrayList<>(pts.subList(pts.size() - MAX_POINTS, pts.size()));
    }

    /** The {@link #MAX_SPRITES} sprites seen last. */
    public static Map<String, Object> trimSprites(Map<String, Object> sprites) {
        if (sprites.size() <= MAX_SPRITES) {
            return sprites;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        int skip = sprites.size() - MAX_SPRITES;
        for (Map.Entry<String, Object> e : sprites.entrySet()) {
            if (skip-- <= 0) {
                out.put(e.getKey(), e.getValue());
            }
        }
        return out;
    }
}
