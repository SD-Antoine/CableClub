package slt.player;

import slt.Log;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Minimal WebSocket server (RFC 6455) on 127.0.0.1 linking the game player to
 * the SLT window: images of the game go out as binary messages (a slow client
 * only ever gets the latest image), keys and commands come in as text.
 */
final class WsServer {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private final ServerSocket socket;
    private final String token;
    private final Consumer<String> onText;
    private final Runnable onConnect;
    private final List<Client> clients = new CopyOnWriteArrayList<>();

    WsServer(String token, Consumer<String> onText, Runnable onConnect) throws IOException {
        this.socket = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        this.token = token;
        this.onText = onText;
        this.onConnect = onConnect;
        Thread t = new Thread(this::acceptLoop, "slt-ws");
        t.setDaemon(true);
        t.start();
    }

    int port() {
        return socket.getLocalPort();
    }

    boolean hasClients() {
        return !clients.isEmpty();
    }

    /** Latest image for every client (older unsent images are dropped). */
    void frame(byte[] message) {
        for (Client c : clients) {
            c.offerFrame(message);
        }
    }

    void text(String message) {
        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        for (Client c : clients) {
            c.offerText(data);
        }
    }

    void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // closing anyway
        }
        for (Client c : clients) {
            c.close();
        }
    }

    private void acceptLoop() {
        while (!socket.isClosed()) {
            try {
                Socket s = socket.accept();
                s.setTcpNoDelay(true);
                Thread t = new Thread(() -> serve(s), "slt-ws-client");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!socket.isClosed()) {
                    Log.warn("WebSocket : " + e.getMessage());
                }
            }
        }
    }

    private void serve(Socket s) {
        Client c = null;
        try {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            String request = readHeaders(in);
            if (request == null || !request.startsWith("GET ")) {
                s.close();
                return;
            }
            String path = request.substring(4, request.indexOf(' ', 4));
            String key = header(request, "Sec-WebSocket-Key");
            if (key == null || !path.contains("t=" + token)) {
                out.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                s.close();
                return;
            }
            String accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1")
                    .digest((key.trim() + GUID).getBytes(StandardCharsets.US_ASCII)));
            out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            c = new Client(s, out);
            clients.add(c);
            onConnect.run();
            readLoop(in, c);
        } catch (Exception e) {
            // client gone
        } finally {
            if (c != null) {
                clients.remove(c);
                c.close();
            } else {
                try {
                    s.close();
                } catch (IOException ignored) {
                    // already closed
                }
            }
        }
    }

    private void readLoop(InputStream in, Client c) throws IOException {
        ByteArrayOutputStream message = new ByteArrayOutputStream();
        int messageOp = 0;
        while (true) {
            int b0 = in.read();
            int b1 = in.read();
            if (b0 < 0 || b1 < 0) {
                return;
            }
            boolean fin = (b0 & 0x80) != 0;
            int op = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                len = (in.read() << 8) | in.read();
            } else if (len == 127) {
                len = 0;
                for (int i = 0; i < 8; i++) {
                    len = (len << 8) | in.read();
                }
            }
            if (len > 1 << 20) {
                return; // nothing legitimate is that big
            }
            byte[] mask = new byte[4];
            if (masked) {
                readFully(in, mask);
            }
            byte[] payload = new byte[(int) len];
            readFully(in, payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) {
                    payload[i] ^= mask[i & 3];
                }
            }
            switch (op) {
                case 0x8 -> {
                    return; // close
                }
                case 0x9 -> c.send(0xA, payload); // ping -> pong
                case 0xA -> {
                    // pong
                }
                default -> {
                    if (op != 0) {
                        messageOp = op;
                        message.reset();
                    }
                    message.write(payload);
                    if (fin && messageOp == 0x1) {
                        try {
                            onText.accept(message.toString(StandardCharsets.UTF_8));
                        } catch (RuntimeException e) {
                            Log.warn("Commande du jeu ignorée : " + e.getMessage());
                        }
                    }
                }
            }
        }
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new IOException("fin du flux");
            }
            off += n;
        }
    }

    private static String readHeaders(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int last4 = 0;
        while (sb.length() < 8192) {
            int b = in.read();
            if (b < 0) {
                return null;
            }
            sb.append((char) b);
            last4 = (last4 << 8) | b;
            if (last4 == 0x0D0A0D0A) {
                return sb.toString();
            }
        }
        return null;
    }

    private static String header(String request, String name) {
        for (String line : request.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase(name)) {
                return line.substring(colon + 1).trim();
            }
        }
        return null;
    }

    /** One connected window: its own writer thread so a slow client never blocks the game. */
    private static final class Client {
        private final Socket socket;
        private final OutputStream out;
        private final AtomicReference<byte[]> pendingFrame = new AtomicReference<>();
        private final java.util.concurrent.LinkedBlockingQueue<byte[]> texts = new java.util.concurrent.LinkedBlockingQueue<>(256);
        private final Object signal = new Object();
        private volatile boolean closed;

        Client(Socket socket, OutputStream out) {
            this.socket = socket;
            this.out = out;
            Thread t = new Thread(this::writeLoop, "slt-ws-writer");
            t.setDaemon(true);
            t.start();
        }

        void offerFrame(byte[] data) {
            pendingFrame.set(data);
            synchronized (signal) {
                signal.notify();
            }
        }

        void offerText(byte[] data) {
            texts.offer(data);
            synchronized (signal) {
                signal.notify();
            }
        }

        private void writeLoop() {
            try {
                while (!closed) {
                    byte[] t;
                    while ((t = texts.poll()) != null) {
                        send(0x1, t);
                    }
                    byte[] f = pendingFrame.getAndSet(null);
                    if (f != null) {
                        send(0x2, f);
                        continue;
                    }
                    synchronized (signal) {
                        if (pendingFrame.get() == null && texts.isEmpty() && !closed) {
                            signal.wait(250);
                        }
                    }
                }
            } catch (SocketException e) {
                // window closed
            } catch (IOException | InterruptedException e) {
                // stop writing
            } finally {
                close();
            }
        }

        synchronized void send(int op, byte[] payload) throws IOException {
            int len = payload.length;
            if (len < 126) {
                out.write(new byte[]{(byte) (0x80 | op), (byte) len});
            } else if (len < 65536) {
                out.write(new byte[]{(byte) (0x80 | op), 126, (byte) (len >> 8), (byte) len});
            } else {
                byte[] h = new byte[10];
                h[0] = (byte) (0x80 | op);
                h[1] = 127;
                for (int i = 0; i < 8; i++) {
                    h[2 + i] = (byte) ((long) len >>> (56 - 8 * i));
                }
                out.write(h);
            }
            out.write(payload);
            out.flush();
        }

        void close() {
            closed = true;
            synchronized (signal) {
                signal.notify();
            }
            try {
                socket.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
