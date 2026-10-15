package slt;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * HTTP requests of the application: to the Cable Club server, and between the game and the application. The
 * Android version of Cable Club has its own class of the same name (Android has no java.net.http).
 */
public final class Net {

    /** A complete answer. */
    public record Response(int status, Map<String, List<String>> headers, byte[] body) {

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        /** First value of a header (any case), or null. */
        public String header(String name) {
            for (Map.Entry<String, List<String>> e : headers.entrySet()) {
                if (e.getKey() != null && e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) {
                    return e.getValue().get(0);
                }
            }
            return null;
        }
    }

    /** An answer read as it arrives (event streams): closing it ends the connection. */
    public interface Stream extends Closeable {
        int status();

        InputStream body();
    }

    private final HttpClient client;

    private Net(HttpClient client) {
        this.client = client;
    }

    /**
     * @param ssl     how encrypted servers are recognised (null: the usual certificate checks)
     * @param pinned  whether an encrypted connection was accepted by its certificate's fingerprint, which then
     *                stands for the check of the server's name (only Android needs it: here the trust manager of
     *                {@code ssl} does both); null when {@code ssl} is null
     * @param connectMillis how long to wait for the server to answer the connection
     */
    public static Net client(SSLContext ssl, Predicate<SSLSession> pinned, int connectMillis) {
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(connectMillis)).version(HttpClient.Version.HTTP_1_1);
        if (ssl != null) {
            b.sslContext(ssl);
        }
        return new Net(b.build());
    }

    /**
     * One request. A timeout ends in a {@link SocketTimeoutException}.
     *
     * @param body null for none
     */
    public Response send(String method, String url, Map<String, String> headers, byte[] body, int timeoutMillis) throws IOException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMillis(timeoutMillis));
        if (headers != null) {
            headers.forEach(rb::header);
        }
        rb.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<byte[]> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(r.statusCode(), r.headers().map(), r.body() == null ? new byte[0] : r.body());
        } catch (HttpTimeoutException e) {
            throw timeout(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
    }

    /** A GET whose answer is read as it arrives, without time limit. */
    public Stream open(String url, Map<String, String> headers) throws IOException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).GET();
        if (headers != null) {
            headers.forEach(rb::header);
        }
        try {
            HttpResponse<InputStream> r = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            return new Stream() {
                @Override
                public int status() {
                    return r.statusCode();
                }

                @Override
                public InputStream body() {
                    return r.body();
                }

                @Override
                public void close() throws IOException {
                    r.body().close();
                }
            };
        } catch (HttpTimeoutException e) {
            throw timeout(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu", e);
        }
    }

    private static SocketTimeoutException timeout(HttpTimeoutException e) {
        SocketTimeoutException t = new SocketTimeoutException(e.getMessage());
        t.initCause(e);
        return t;
    }
}
