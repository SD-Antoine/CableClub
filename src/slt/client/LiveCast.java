package slt.client;

import slt.Json;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Whether the live image of the game goes to the server now. The server answers each image with "watched":
 * while no other member of the room looks at it, one image every {@link #IDLE_MS} only (a recent picture for whoever
 * opens it, and the question asked again); as soon as someone looks, every image again. A server that does not
 * say (older than this) gets every image, as before.
 */
final class LiveCast {

    static final long IDLE_MS = 2_000;

    private boolean watched = true;
    private long lastSent = Long.MIN_VALUE;

    /** Whether to send the image made at {@code now} (ms); if so, it counts as sent. */
    synchronized boolean shouldSend(long now) {
        if (!watched && now - lastSent < IDLE_MS) {
            return false;
        }
        lastSent = now;
        return true;
    }

    /** The answer of the server to an image (null: not sent, nothing learnt). */
    synchronized void answered(byte[] body) {
        if (body == null) {
            return;
        }
        boolean w = true;
        try {
            Map<String, Object> m = Json.parseObject(new String(body, StandardCharsets.UTF_8));
            w = Json.bool(m, "watched", true);
        } catch (RuntimeException e) {
            // not what this version knows: every image, as with an older server
        }
        watched = w;
    }
}
