package com.sun.net.httpserver;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A small HTTP/1.1 server with the shape of the JDK's (one handler for "/", keep-alive, chunked answers), enough
 * for the local API of Cable Club on Android.
 */
public class HttpServer {

    private final ServerSocket socket;
    private volatile HttpHandler handler;
    private volatile Executor executor;
    private volatile boolean running;

    private HttpServer(ServerSocket socket) {
        this.socket = socket;
    }

    public static HttpServer create(InetSocketAddress address, int backlog) throws IOException {
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(address, Math.max(backlog, 16));
        return new HttpServer(s);
    }

    /** Only one context is supported (the root): the application routes the paths itself. */
    public Object createContext(String path, HttpHandler h) {
        handler = h;
        return null;
    }

    public void setExecutor(Executor e) {
        executor = e;
    }

    public InetSocketAddress getAddress() {
        return (InetSocketAddress) socket.getLocalSocketAddress();
    }

    public void start() {
        if (executor == null) {
            ExecutorService pool = Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "slt-http");
                t.setDaemon(true);
                return t;
            });
            executor = pool;
        }
        running = true;
        Thread accept = new Thread(() -> {
            while (running) {
                try {
                    Socket c = socket.accept();
                    executor.execute(() -> serve(c));
                } catch (IOException e) {
                    if (!running) {
                        return;
                    }
                }
            }
        }, "slt-http-accept");
        accept.setDaemon(true);
        accept.start();
    }

    public void stop(int delaySeconds) {
        running = false;
        try {
            socket.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    // ------------------------------------------------------------- one connection

    private void serve(Socket c) {
        try (Socket s = c) {
            s.setTcpNoDelay(true);
            // an idle connection kept by the browser is closed after a while (it opens another one)
            s.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 16384);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 65536);
            while (running) {
                String requestLine = line(in);
                if (requestLine == null) {
                    return;
                }
                if (requestLine.isEmpty()) {
                    continue;
                }
                String[] parts = requestLine.split(" ");
                if (parts.length < 3) {
                    return;
                }
                Headers headers = new Headers();
                for (String h = line(in); h != null && !h.isEmpty(); h = line(in)) {
                    int colon = h.indexOf(':');
                    if (colon > 0) {
                        headers.add(h.substring(0, colon).trim(), h.substring(colon + 1).trim());
                    }
                }
                InputStream body;
                String te = headers.getFirst("Transfer-Encoding");
                if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
                    body = new ChunkedIn(in);
                } else {
                    long length = 0;
                    try {
                        String cl = headers.getFirst("Content-Length");
                        length = cl == null ? 0 : Long.parseLong(cl.trim());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    body = new LimitedIn(in, length);
                }
                String connection = headers.getFirst("Connection");
                boolean keepAlive = parts[2].equals("HTTP/1.1") ? connection == null || !connection.equalsIgnoreCase("close")
                        : connection != null && connection.equalsIgnoreCase("keep-alive");
                URI uri;
                try {
                    uri = new URI(parts[1]);
                } catch (Exception e) {
                    out.write("HTTP/1.1 400 Bad Request\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                    out.flush();
                    return;
                }
                HttpExchange ex = new HttpExchange(parts[0], uri, headers, body, out, (InetSocketAddress) s.getRemoteSocketAddress(),
                        (InetSocketAddress) s.getLocalSocketAddress(), keepAlive);
                try {
                    handler.handle(ex);
                } catch (RuntimeException e) {
                    ex.keepAlive = false;
                } finally {
                    ex.close();
                }
                if (!ex.keepAlive) {
                    return;
                }
            }
        } catch (SocketTimeoutException | SocketException e) {
            // the other side closed or went quiet
        } catch (IOException e) {
            // connection lost
        }
    }

    /** One line of the request head (CRLF or LF), null at the end of the stream. */
    private static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(128);
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') {
                String s = b.toString("ISO-8859-1");
                return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
            }
            if (b.size() > 16384) {
                throw new IOException("Ligne trop longue");
            }
            b.write(c);
        }
        return b.size() == 0 ? null : b.toString("ISO-8859-1");
    }

    /** The body of a request of known length. */
    private static final class LimitedIn extends InputStream {
        private final InputStream in;
        private long left;

        LimitedIn(InputStream in, long length) {
            this.in = in;
            this.left = length;
        }

        @Override
        public int read() throws IOException {
            if (left <= 0) {
                return -1;
            }
            int c = in.read();
            if (c >= 0) {
                left--;
            }
            return c;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (left <= 0) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), left);
        }
    }

    /** The body of a request sent in chunks. */
    private static final class ChunkedIn extends InputStream {
        private final InputStream in;
        private long left;
        private boolean end;

        ChunkedIn(InputStream in) {
            this.in = in;
        }

        private boolean next() throws IOException {
            if (end) {
                return false;
            }
            if (left == 0) {
                String size = line(in);
                if (size == null) {
                    end = true;
                    return false;
                }
                if (size.isEmpty()) {
                    size = line(in); // the CRLF after the previous chunk
                }
                int semi = size == null ? -1 : size.indexOf(';');
                try {
                    left = Long.parseLong((semi >= 0 ? size.substring(0, semi) : size).trim(), 16);
                } catch (RuntimeException e) {
                    throw new IOException("Morceau illisible");
                }
                if (left == 0) {
                    for (String t = line(in); t != null && !t.isEmpty(); t = line(in)) {
                        // trailers
                    }
                    end = true;
                    return false;
                }
            }
            return true;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int n = read(one, 0, 1);
            return n <= 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (!next()) {
                return -1;
            }
            int n = in.read(b, off, (int) Math.min(len, left));
            if (n > 0) {
                left -= n;
            }
            return n;
        }
    }
}
