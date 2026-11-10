package slt.server;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * The settings of the JDK's HTTP server ({@link SltServer#tuneHttp}, load test of 2026-11-10): many players keep
 * their keep-alive connection (beyond 200 idle ones the server used to close them, and every request then paid a
 * new TLS handshake), a value given to the JVM is kept, and a request that never ends is cut. Runs a throwaway
 * server on 127.0.0.1 (plain HTTP, made-up data directory).
 */
public final class HttpLimitsTest {

    /** More than the 200 idle connections of the JDK's default. */
    private static final int CONNECTIONS = 300;

    private static int failed;

    public static void main(String[] args) throws Exception {
        // a value given to the JVM is kept: here a short request time, so that the slow request is cut quickly
        System.setProperty("sun.net.httpserver.maxReqTime", "2");
        SltServer.tuneHttp();
        check("2".equals(System.getProperty("sun.net.httpserver.maxReqTime")), "valeur donnée à la JVM gardée");
        check("20000".equals(System.getProperty("sun.net.httpserver.maxIdleConnections")), "connexions inactives : 20000");

        Path dir = Files.createTempDirectory("cableclub-http-test");
        SltServer server = new SltServer(dir, "Serveur de test");
        try {
            server.start("127.0.0.1", 0, null, null);
            InetSocketAddress addr = new InetSocketAddress("127.0.0.1", server.port());
            keepAlive(addr);
            slowRequest(addr);
        } finally {
            server.stop();
            try (Stream<Path> files = Files.walk(dir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
        System.out.println(failed == 0 ? "ok     réglages du serveur HTTP (connexions gardées, requête lente coupée)"
                : "ÉCHEC  réglages du serveur HTTP : " + failed + " cas");
        System.exit(failed == 0 ? 0 : 1);
    }

    /** Every connection answers a request, all of them wait idle together, then each answers a second one. */
    private static void keepAlive(InetSocketAddress addr) throws IOException, InterruptedException {
        List<Socket> sockets = new ArrayList<>();
        try {
            int first = 0;
            for (int i = 0; i < CONNECTIONS; i++) {
                Socket s = new Socket();
                s.connect(addr, 5000);
                s.setSoTimeout(5000);
                sockets.add(s);
                first += get(s) ? 1 : 0;
            }
            check(first == CONNECTIONS, "première requête sur " + CONNECTIONS + " connexions (" + first + ")");
            Thread.sleep(300);
            int second = 0;
            for (Socket s : sockets) {
                second += get(s) ? 1 : 0;
            }
            check(second == CONNECTIONS, CONNECTIONS + " connexions gardées ouvertes (" + second + " répondent encore)");
        } finally {
            for (Socket s : sockets) {
                s.close();
            }
        }
    }

    /** A request whose headers never end is cut once its time is over. */
    private static void slowRequest(InetSocketAddress addr) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(addr, 5000);
            s.setSoTimeout(10_000);
            s.getOutputStream().write("GET /api/server HTTP/1.1\r\nHost: 127.0.0.1\r\n".getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            long start = System.nanoTime();
            boolean closed;
            try {
                closed = s.getInputStream().read() < 0;
            } catch (IOException e) {
                closed = !(e instanceof java.net.SocketTimeoutException);
            }
            long ms = (System.nanoTime() - start) / 1_000_000;
            check(closed && ms < 9000, "requête inachevée coupée (" + ms + " ms)");
        }
    }

    /** One GET on a kept-alive connection: true when a whole 200 answer came back. */
    private static boolean get(Socket s) {
        try {
            OutputStream out = s.getOutputStream();
            out.write("GET /api/server HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            InputStream in = s.getInputStream();
            String head = headers(in);
            if (head == null || !head.startsWith("HTTP/1.1 200")) {
                return false;
            }
            int length = -1;
            for (String line : head.split("\r\n")) {
                if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
                    length = Integer.parseInt(line.substring(15).trim());
                }
            }
            if (length < 0) {
                return false;
            }
            for (int i = 0; i < length; i++) {
                if (in.read() < 0) {
                    return false;
                }
            }
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    private static String headers(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) >= 0) {
            buf.write(b);
            int n = buf.size();
            byte[] a = buf.toByteArray();
            if (n >= 4 && a[n - 4] == '\r' && a[n - 3] == '\n' && a[n - 2] == '\r' && a[n - 1] == '\n') {
                return new String(a, StandardCharsets.US_ASCII);
            }
        }
        return null;
    }

    private static void check(boolean ok, String what) {
        if (!ok) {
            failed++;
            System.out.println("  échec : " + what);
        }
    }
}
