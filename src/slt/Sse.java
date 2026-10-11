package slt;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Server-Sent Events: streams JSON events to a client until it disconnects. */
public final class Sse {

    /** Receives events for one connected client; false = client gone or too slow. */
    @FunctionalInterface
    public interface Sink {
        boolean offer(String event, String json);
    }

    private Sse() {
    }

    /**
     * Streams whatever is offered to the sink. Blocks the (virtual) handler
     * thread until the client disconnects.
     */
    public static void serve(HttpExchange ex, String helloJson, Consumer<Sink> register, Consumer<Sink> unregister)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        BlockingQueue<String> queue = new LinkedBlockingQueue<>(4000);
        boolean[] dead = {false};
        Sink sink = (event, data) -> {
            if (dead[0]) {
                return false;
            }
            boolean ok = queue.offer("event: " + event + "\ndata: " + data + "\n\n");
            if (!ok) {
                dead[0] = true;
            }
            return ok;
        };
        OutputStream os = ex.getResponseBody();
        try {
            os.write(("retry: 2000\nevent: hello\ndata: " + (helloJson == null ? "{}" : helloJson) + "\n\n")
                    .getBytes(StandardCharsets.UTF_8));
            os.flush();
            register.accept(sink);
            while (!dead[0]) {
                String msg = queue.poll(15, TimeUnit.SECONDS);
                if (msg == null) {
                    msg = ": ping\n\n";
                }
                os.write(msg.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } catch (IOException | InterruptedException ignored) {
            // client went away
        } finally {
            dead[0] = true;
            unregister.accept(sink);
            try {
                os.close();
            } catch (IOException ignored) {
                // already closed
            }
            ex.close();
        }
    }
}
