package slt.server;

import slt.Http;
import slt.Json;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The live images of the games of a server's rooms (taken out of {@link Rooms}, point 8 of the follow-up of the audit
 * of 2026-11-10): the latest one of each player, and when another member last asked for it (point 4: an image nobody
 * watches is sent once every few seconds only). Kept in memory, never written.
 */
final class LiveScreens {

    static final int MAX_SCREEN = 400_000;
    /** An image asked for in this time is watched: the interface asks 4 times a second while it shows it. */
    static final long WATCH_MS = 3_000;

    private record Screen(long seq, byte[] jpeg, long at) {
    }

    private final Map<String, Screen> screens = new ConcurrentHashMap<>();
    /** When the image of a player's game was last asked for by another member (room id/user id → ms). */
    final Map<String, Long> watched = new ConcurrentHashMap<>();

    /**
     * Keeps the latest image of a player's game (room {@code r}, its member {@code user}). Returns whether another
     * member asked for it lately: the application then sends one image every few seconds only while nobody looks
     * (an older one ignores it).
     */
    boolean put(Room r, Accounts.User user, byte[] jpeg) {
        if (jpeg.length > MAX_SCREEN) {
            throw new Http.Fail(413, "Image trop grande");
        }
        String key = r.id + "/" + user.id;
        Screen prev = screens.get(key);
        long now = System.currentTimeMillis();
        screens.put(key, new Screen(prev == null ? 1 : prev.seq() + 1, jpeg, now));
        Long seen = watched.get(key);
        return seen != null && now - seen < WATCH_MS;
    }

    /** Latest image of a player's game, or null if nothing newer than {@code since}; the image is watched. */
    Map<String, Object> get(Room r, Accounts.User user, String target, long since) {
        if (!user.id.equals(target) && r.member(target) != null) {
            watched.put(r.id + "/" + target, System.currentTimeMillis());
        }
        Screen s = screens.get(r.id + "/" + target);
        if (s == null || s.seq() <= since || System.currentTimeMillis() - s.at() > 20_000) {
            return null;
        }
        return Json.map("seq", s.seq(), "jpeg", s.jpeg());
    }

    /** A player's image and its watchers forgotten (deleted account). */
    void forget(String roomId, String userId) {
        screens.remove(roomId + "/" + userId);
        watched.remove(roomId + "/" + userId);
    }

    /** Every image of a deleted room forgotten. */
    void forgetRoom(String roomId) {
        screens.keySet().removeIf(k -> k.startsWith(roomId + "/"));
        watched.keySet().removeIf(k -> k.startsWith(roomId + "/"));
    }

    /** Images kept (the moderators' metrics). */
    long kept() {
        return screens.size();
    }

    /** Images watched right now. */
    long watching(long now) {
        return watched.values().stream().filter(t -> now - t < WATCH_MS).count();
    }
}
