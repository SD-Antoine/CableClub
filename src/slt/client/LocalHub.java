package slt.client;

import slt.Json;
import slt.Sse;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Event stream from this SLT to its own interface (local events + relayed server events). */
final class LocalHub {

    private final List<Sse.Sink> sinks = new CopyOnWriteArrayList<>();

    void add(Sse.Sink s) {
        sinks.add(s);
    }

    void remove(Sse.Sink s) {
        sinks.remove(s);
    }

    void send(String event, Object data) {
        String json = data instanceof String s ? s : Json.stringify(data);
        for (Sse.Sink s : sinks) {
            if (!s.offer(event, json)) {
                sinks.remove(s);
            }
        }
    }
}
