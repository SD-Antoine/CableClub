package slt;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** HTTP requests of the application, with HttpURLConnection (Android has no java.net.http). */
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

    private final SSLContext ssl;
    private final HostnameVerifier names;
    private final int connectMillis;

    private Net(SSLContext ssl, HostnameVerifier names, int connectMillis) {
        this.ssl = ssl;
        this.names = names;
        this.connectMillis = connectMillis;
    }

    /**
     * @param ssl    how encrypted servers are recognised (null: the usual certificate checks)
     * @param pinned whether a connection was accepted by its certificate's fingerprint: then the server's name is
     *               not checked (a server of its own has no certificate for the address typed); otherwise the
     *               usual check of the name applies
     */
    public static Net client(SSLContext ssl, Predicate<SSLSession> pinned, int connectMillis) {
        HostnameVerifier names = null;
        if (pinned != null) {
            HostnameVerifier usual = HttpsURLConnection.getDefaultHostnameVerifier();
            names = (host, session) -> pinned.test(session) || usual.verify(host, session);
        }
        return new Net(ssl, names, connectMillis);
    }

    private HttpURLConnection connect(String method, String url, Map<String, String> headers, int readMillis) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        if (c instanceof HttpsURLConnection https) {
            if (ssl != null) {
                https.setSSLSocketFactory(ssl.getSocketFactory());
            }
            if (names != null) {
                https.setHostnameVerifier(names);
            }
        }
        c.setConnectTimeout(connectMillis);
        c.setReadTimeout(readMillis);
        c.setRequestMethod(method);
        c.setInstanceFollowRedirects(false);
        c.setUseCaches(false);
        if (headers != null) {
            headers.forEach(c::setRequestProperty);
        }
        return c;
    }

    /** One request. A timeout ends in a {@link java.net.SocketTimeoutException}. */
    public Response send(String method, String url, Map<String, String> headers, byte[] body, int timeoutMillis) throws IOException {
        HttpURLConnection c = connect(method, url, headers, timeoutMillis);
        try {
            if (body != null) {
                c.setDoOutput(true);
                c.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = c.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = c.getResponseCode();
            InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
            byte[] data = in == null ? new byte[0] : readAll(in);
            return new Response(status, c.getHeaderFields(), data);
        } finally {
            c.disconnect();
        }
    }

    /** A GET whose answer is read as it arrives, without time limit. */
    public Stream open(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection c = connect("GET", url, headers, 0);
        int status = c.getResponseCode();
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        InputStream body = in == null ? new java.io.ByteArrayInputStream(new byte[0]) : in;
        return new Stream() {
            @Override
            public int status() {
                return status;
            }

            @Override
            public InputStream body() {
                return body;
            }

            @Override
            public void close() {
                // disconnecting ends a read blocked on the stream
                c.disconnect();
            }
        };
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
            byte[] buf = new byte[16384];
            int n;
            while ((n = i.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
