package slt.client;

import slt.Json;
import slt.Sse;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Event stream from this SLT to its own interface (local events + relayed server events). */
final class LocalHub {

    /** Each stream and the interface page that opened it (empty when unknown). */
    private final Map<Sse.Sink, String> sinks = new ConcurrentHashMap<>();
    /** Streams opened since the start (a page coming back after a reload opens a new one). */
    private final java.util.concurrent.atomic.AtomicLong opened = new java.util.concurrent.atomic.AtomicLong();

    void add(Sse.Sink s, String page) {
        sinks.put(s, page == null ? "" : page);
        opened.incrementAndGet();
    }

    long opened() {
        return opened.get();
    }

    void remove(Sse.Sink s) {
        sinks.remove(s);
    }

    /** A page said it is going away: its stream no longer counts (it would only be noticed at the next ping). */
    void closePage(String page) {
        if (page != null && !page.isEmpty()) {
            sinks.values().removeIf(page::equals);
        }
    }

    /** Interface windows (or tabs) connected right now. */
    int size() {
        return sinks.size();
    }

    void send(String event, Object data) {
        String json = data instanceof String s ? s : Json.stringify(data);
        for (Sse.Sink s : sinks.keySet()) {
            if (!s.offer(event, json)) {
                sinks.remove(s);
            }
        }
    }
}
