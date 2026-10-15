package com.sun.net.httpserver;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * One request and its answer. The answer is sent with a length ({@code sendResponseHeaders(status, n)}, n > 0),
 * without body (n = -1) or in chunks as it is written (n = 0: event streams).
 */
public class HttpExchange {

    private final String method;
    private final URI uri;
    private final Headers requestHeaders;
    private final Headers responseHeaders = new Headers();
    private final InputStream requestBody;
    private final OutputStream socketOut;
    private final InetSocketAddress remote;
    private final InetSocketAddress local;
    private OutputStream responseBody;
    private boolean sent;
    private boolean closed;
    boolean keepAlive;

    HttpExchange(String method, URI uri, Headers requestHeaders, InputStream requestBody, OutputStream socketOut,
                 InetSocketAddress remote, InetSocketAddress local, boolean keepAlive) {
        this.method = method;
        this.uri = uri;
        this.requestHeaders = requestHeaders;
        this.requestBody = requestBody;
        this.socketOut = socketOut;
        this.remote = remote;
        this.local = local;
        this.keepAlive = keepAlive;
    }

    public Headers getRequestHeaders() {
        return requestHeaders;
    }

    public Headers getResponseHeaders() {
        return responseHeaders;
    }

    public URI getRequestURI() {
        return uri;
    }

    public String getRequestMethod() {
        return method;
    }

    public InputStream getRequestBody() {
        return new FilterInputStream(requestBody) {
            @Override
            public void close() {
                // the connection stays open for the next request: only the exchange closes it
            }
        };
    }

    public InetSocketAddress getRemoteAddress() {
        return remote;
    }

    public InetSocketAddress getLocalAddress() {
        return local;
    }

    public String getProtocol() {
        return "HTTP/1.1";
    }

    public void sendResponseHeaders(int status, long length) throws IOException {
        if (sent) {
            throw new IOException("En-têtes déjà envoyés");
        }
        sent = true;
        boolean head = "HEAD".equals(method);
        StringBuilder sb = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason(status)).append("\r\n");
        boolean chunked = length == 0 && !head && status != 204 && status != 304;
        if (chunked) {
            responseHeaders.set("Transfer-Encoding", "chunked");
        } else if (length > 0) {
            responseHeaders.set("Content-Length", Long.toString(length));
        } else if (!head) {
            responseHeaders.set("Content-Length", "0");
        }
        if (!keepAlive) {
            responseHeaders.set("Connection", "close");
        }
        for (Map.Entry<String, List<String>> e : responseHeaders.entrySet()) {
            for (String v : e.getValue()) {
                sb.append(e.getKey()).append(": ").append(v).append("\r\n");
            }
        }
        sb.append("\r\n");
        socketOut.write(sb.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (head || length < 0) {
            responseBody = new Fixed(socketOut, 0);
        } else if (chunked) {
            responseBody = new Chunked(socketOut);
        } else {
            responseBody = new Fixed(socketOut, length);
        }
        if (length < 0 || head) {
            socketOut.flush();
        }
    }

    public OutputStream getResponseBody() {
        if (responseBody == null) {
            // written before the headers: the JDK would fail too, but late
            return new OutputStream() {
                @Override
                public void write(int b) throws IOException {
                    throw new IOException("Réponse sans en-têtes");
                }
            };
        }
        return responseBody;
    }

    /** Ends the exchange: the rest of the request is read, the answer finished. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (!sent) {
                keepAlive = false;
                sendResponseHeaders(500, -1);
            }
            responseBody.close();
            socketOut.flush();
            byte[] skip = new byte[8192];
            while (requestBody.read(skip) > 0) {
                // the next request starts after this one's body
            }
        } catch (IOException e) {
            keepAlive = false;
        }
    }

    boolean finished() {
        return closed;
    }

    private static String reason(int status) {
        switch (status) {
            case 200: return "OK";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 409: return "Conflict";
            case 413: return "Payload Too Large";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 502: return "Bad Gateway";
            default: return "Status";
        }
    }

    /** A body of known length. */
    private static final class Fixed extends OutputStream {
        private final OutputStream out;
        private long left;

        Fixed(OutputStream out, long length) {
            this.out = out;
            this.left = length;
        }

        @Override
        public void write(int b) throws IOException {
            if (left <= 0) {
                throw new IOException("Réponse plus longue que annoncé");
            }
            out.write(b);
            left--;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (len > left) {
                throw new IOException("Réponse plus longue que annoncé");
            }
            out.write(b, off, len);
            left -= len;
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            out.flush();
        }
    }

    /** A body sent in chunks, each write one chunk (event streams flush as they go). */
    private static final class Chunked extends OutputStream {
        private final OutputStream out;
        private boolean done;

        Chunked(OutputStream out) {
            this.out = out;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[]{(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            if (done) {
                throw new IOException("Réponse terminée");
            }
            if (len == 0) {
                return;
            }
            out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(b, off, len);
            out.write('\r');
            out.write('\n');
        }

        @Override
        public void flush() throws IOException {
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (!done) {
                done = true;
                out.write("0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
        }
    }
}
