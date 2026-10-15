package slt.player;

import slt.Ghosts;
import slt.Json;
import slt.Log;
import slt.Net;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Link between the game player and the SLT application that started it:
 * status, tracker events (kept in order), the room's directives, live
 * images, save notifications.
 */
final class Bridge {

    private final Net client = Net.client(null, null, 2000);
    private final String base;
    private final String secret;
    private final String wsToken;
    private final AtomicBoolean screenBusy = new AtomicBoolean();
    private final List<Object> batch = new ArrayList<>();
    private boolean flushScheduled;
    // messages one after the other, in order; images and positions each on their own, never waiting for the others
    private final ExecutorService ordered = executor("slt-pont");
    private final ExecutorService images = executor("slt-pont-images");
    private final ExecutorService positions = executor("slt-pont-position");
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "slt-pont-minuterie"));
    private Future<?> last;

    Bridge(String base, String secret, String wsToken) {
        this.base = base;
        this.secret = secret;
        this.wsToken = wsToken;
    }

    private static ExecutorService executor(String name) {
        return Executors.newSingleThreadExecutor(r -> daemon(r, name));
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    void status(String state, String core, int wsPort) {
        post("/api/player/status", Json.map("state", state, "core", core, "wsPort", (long) wsPort, "wsToken", wsToken));
    }

    /** Tracker event: grouped for 150 ms, then sent after the previous group. */
    void event(Map<String, Object> event) {
        synchronized (batch) {
            batch.add(event);
            if (flushScheduled) {
                return;
            }
            flushScheduled = true;
        }
        timer.schedule(this::sendBatch, 150, TimeUnit.MILLISECONDS);
    }

    private void sendBatch() {
        List<Object> events;
        synchronized (batch) {
            events = new ArrayList<>(batch);
            batch.clear();
            flushScheduled = false;
        }
        if (!events.isEmpty()) {
            post("/api/player/events", Json.map("events", events));
        }
    }

    void notice(String level, String text) {
        post("/api/player/notice", Json.map("level", level, "text", text));
    }

    void ack(List<Object> refundIds) {
        post("/api/player/ack", Json.map("refunds", refundIds));
    }

    /** Trade data or result for the room (see the trade directive). */
    void trade(Map<String, Object> t) {
        post("/api/player/trade", t);
    }

    void saved() {
        post("/api/player/saved", Json.map());
    }

    /** What the room says this game must apply, or null when SLT cannot be reached. */
    Map<String, Object> directives() {
        try {
            Net.Response res = client.send("GET", base + "/api/player/directives", Map.of("X-SLT-Player", secret), null, 4000);
            return res.status() == 200 ? Json.parseObject(res.text()) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Live image for the partners: dropped when the previous one is still being sent. */
    void screen(byte[] jpeg) {
        if (!screenBusy.compareAndSet(false, true)) {
            return;
        }
        images.execute(() -> {
            try {
                client.send("POST", base + "/api/player/screen", Map.of("X-SLT-Player", secret, "Content-Type", "image/jpeg"), jpeg, 5000);
            } catch (IOException | RuntimeException e) {
                // the next image will try again
            } finally {
                screenBusy.set(false);
            }
        });
    }

    private final Object ghostLock = new Object();
    private Map<String, Object> ghostPending;
    private boolean ghostBusy;

    /**
     * Position of the player for the others: a reading that arrives while the
     * previous one is being sent is merged with any waiting one (latest
     * position, the way walked by both, their sprites).
     */
    void ghost(Map<String, Object> g) {
        synchronized (ghostLock) {
            if (ghostBusy) {
                ghostPending = Ghosts.merge(ghostPending, g);
                return;
            }
            ghostBusy = true;
        }
        positions.execute(() -> {
            Map<String, Object> next = g;
            while (next != null) {
                try {
                    client.send("POST", base + "/api/player/ghost", Map.of("Content-Type", "application/json", "X-SLT-Player", secret),
                            Json.stringify(next).getBytes(StandardCharsets.UTF_8), 4000);
                } catch (IOException | RuntimeException e) {
                    // the next reading will try again
                }
                synchronized (ghostLock) {
                    next = ghostPending;
                    ghostPending = null;
                    if (next == null) {
                        ghostBusy = false;
                    }
                }
            }
        });
    }

    private synchronized void post(String path, Map<String, Object> body) {
        byte[] json = Json.stringify(body).getBytes(StandardCharsets.UTF_8);
        // keep messages ordered: each request waits for the previous one
        last = ordered.submit(() -> {
            try {
                client.send("POST", base + path, Map.of("Content-Type", "application/json", "X-SLT-Player", secret), json, 5000);
            } catch (IOException | RuntimeException e) {
                Log.warn("SLT injoignable depuis le lecteur : " + e.getMessage());
            }
        });
    }

    void flush() {
        sendBatch();
        Future<?> f;
        synchronized (this) {
            f = last;
        }
        if (f == null) {
            return;
        }
        try {
            f.get(3, TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // the application may already be closed
        }
    }
}
