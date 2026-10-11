package slt.player;

import slt.Ghosts;
import slt.Json;
import slt.Log;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Link between the game player and the SLT application that started it:
 * status, tracker events (kept in order), the room's directives, live
 * images, save notifications.
 */
final class Bridge {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final String base;
    private final String secret;
    private final String wsToken;
    private final AtomicBoolean screenBusy = new AtomicBoolean();
    private final List<Object> batch = new ArrayList<>();
    private CompletableFuture<?> last = CompletableFuture.completedFuture(null);
    private boolean flushScheduled;

    Bridge(String base, String secret, String wsToken) {
        this.base = base;
        this.secret = secret;
        this.wsToken = wsToken;
    }

    void status(String state, String core, int wsPort) {
        post("/api/player/status", Json.map("state", state, "core", core, "wsPort", (long) wsPort, "wsToken", wsToken,
                "pid", ProcessHandle.current().pid()));
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
        CompletableFuture.delayedExecutor(150, java.util.concurrent.TimeUnit.MILLISECONDS).execute(this::sendBatch);
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
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/api/player/directives"))
                .timeout(Duration.ofSeconds(4))
                .header("X-SLT-Player", secret)
                .GET()
                .build();
        try {
            HttpResponse<String> res = client.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return res.statusCode() == 200 ? Json.parseObject(res.body()) : null;
        } catch (java.io.IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** Live image for the partners: dropped when the previous one is still being sent. */
    void screen(byte[] jpeg) {
        if (!screenBusy.compareAndSet(false, true)) {
            return;
        }
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/api/player/screen"))
                .timeout(Duration.ofSeconds(5))
                .header("X-SLT-Player", secret)
                .header("Content-Type", "image/jpeg")
                .POST(HttpRequest.BodyPublishers.ofByteArray(jpeg))
                .build();
        client.sendAsync(r, HttpResponse.BodyHandlers.discarding()).whenComplete((x, e) -> screenBusy.set(false));
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
        sendGhost(g);
    }

    private void sendGhost(Map<String, Object> g) {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + "/api/player/ghost"))
                .timeout(Duration.ofSeconds(4))
                .header("Content-Type", "application/json")
                .header("X-SLT-Player", secret)
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(g), StandardCharsets.UTF_8))
                .build();
        client.sendAsync(r, HttpResponse.BodyHandlers.discarding()).whenComplete((x, e) -> {
            Map<String, Object> next;
            synchronized (ghostLock) {
                next = ghostPending;
                ghostPending = null;
                if (next == null) {
                    ghostBusy = false;
                    return;
                }
            }
            sendGhost(next);
        });
    }

    private synchronized void post(String path, Map<String, Object> body) {
        HttpRequest r = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .header("X-SLT-Player", secret)
                .POST(HttpRequest.BodyPublishers.ofString(Json.stringify(body), StandardCharsets.UTF_8))
                .build();
        // keep messages ordered: each request waits for the previous one
        last = last.handle((x, e) -> null).thenCompose(x -> client.sendAsync(r, HttpResponse.BodyHandlers.discarding()))
                .exceptionally(e -> {
                    Log.warn("SLT injoignable depuis le lecteur : " + e.getMessage());
                    return null;
                });
    }

    void flush() {
        sendBatch();
        try {
            last.get(3, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception ignored) {
            // the application may already be closed
        }
    }
}
