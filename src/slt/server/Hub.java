package slt.server;

import slt.Json;
import slt.Sse;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;

/**
 * Live connections of the users (one event stream per open SLT): presence
 * and delivery of events to one user or to the members of a room.
 */
public final class Hub {

    private final Map<String, List<Sse.Sink>> streams = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, Boolean> presenceListener = (u, b) -> { };
    /** Version of each user's profile picture (0 = none), shown next to their name everywhere. */
    private volatile java.util.function.ToLongFunction<String> avatars = u -> 0;

    void avatars(java.util.function.ToLongFunction<String> f) {
        avatars = f;
    }

    public long avatar(String userId) {
        return avatars.applyAsLong(userId);
    }

    void onPresence(BiConsumer<String, Boolean> listener) {
        presenceListener = listener;
    }

    void connect(String userId, Sse.Sink sink) {
        List<Sse.Sink> list = streams.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>());
        boolean first = list.isEmpty();
        list.add(sink);
        if (first) {
            presenceListener.accept(userId, true);
        }
    }

    void disconnect(String userId, Sse.Sink sink) {
        List<Sse.Sink> list = streams.get(userId);
        if (list == null) {
            return;
        }
        list.remove(sink);
        if (list.isEmpty()) {
            streams.remove(userId, list);
            presenceListener.accept(userId, false);
        }
    }

    public boolean online(String userId) {
        List<Sse.Sink> list = streams.get(userId);
        return list != null && !list.isEmpty();
    }

    public Set<String> onlineUsers() {
        return streams.keySet();
    }

    public void send(String userId, String event, Object data) {
        List<Sse.Sink> list = streams.get(userId);
        if (list == null) {
            return;
        }
        String json = data instanceof String s ? s : Json.stringify(data);
        for (Sse.Sink s : list) {
            if (!s.offer(event, json)) {
                disconnect(userId, s);
            }
        }
    }

    public void send(Collection<String> userIds, String event, Object data) {
        String json = Json.stringify(data);
        for (String u : userIds) {
            send(u, event, json);
        }
    }
}
