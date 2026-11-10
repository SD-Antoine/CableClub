package slt.server;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import slt.Json;
import slt.Log;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.concurrent.Executors;

/**
 * The SLT server: accounts, friends, rooms and the Soul Link referee. It runs
 * either inside the SLT application of one player ("Héberger le serveur sur
 * ce PC") or on its own ({@code CableClub.exe --server}) on an always-on machine.
 */
public final class SltServer {

    public static final String VERSION = slt.Version.VERSION;
    public static final int DEFAULT_PORT = 47330;
    /** Connections waiting to be accepted (Linux caps it with net.core.somaxconn): every player back at a restart. */
    static final int BACKLOG = 1024;

    /**
     * The settings of the JDK's HTTP server, as system properties: it reads them once, when its classes are loaded
     * (the first server created, the local API of the application included), so they are set before, from
     * {@link slt.Main}. A value given to the JVM ({@code -D…}) is kept.
     * <ul>
     *   <li>maxIdleConnections: beyond 200 idle keep-alive connections (the default) the server closes the one that
     *       just answered, and with HTTPS nearly every request of a player then pays a new TLS handshake (load test
     *       of 2026-11-10: 500 players collapse at 200, pass at 20000);</li>
     *   <li>maxReqTime: a request (headers and body) must arrive within 2 minutes, enough for a 4 MB save on a slow
     *       link, so that a client sending its headers a byte at a time does not hold its connection forever.</li>
     * </ul>
     * No maxRspTime: the event streams (SSE) are answers that never end.
     */
    public static void tuneHttp() {
        setDefault("sun.net.httpserver.maxIdleConnections", "20000");
        setDefault("sun.net.httpserver.maxReqTime", "120");
    }

    private static void setDefault(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }

    final Accounts accounts;
    final Hub hub = new Hub();
    final Rooms rooms;
    final Avatars avatars;
    final Chat chat;
    final Battles battles;
    final Reports reports;
    final ModeGallery gallery;
    final RunReviews reviews;
    final String name;
    private final java.util.concurrent.ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "slt-server-timer");
                t.setDaemon(true);
                return t;
            });
    private HttpServer http;
    private int port;

    public SltServer(Path dataDir, String name) throws IOException {
        Files.createDirectories(dataDir);
        this.accounts = new Accounts(dataDir);
        this.rooms = new Rooms(dataDir, accounts, hub);
        this.avatars = new Avatars(dataDir);
        this.chat = new Chat(dataDir, accounts, rooms, hub);
        this.battles = new Battles(rooms, hub);
        this.reports = new Reports(dataDir);
        this.gallery = new ModeGallery(dataDir, accounts);
        this.reviews = new RunReviews(dataDir);
        // the checks of the runs: none on a server published with Cable Club, the official server adds its own jar
        Room.checks = RunCheck.load();
        if (!Room.checks.isEmpty()) {
            Log.info("Serveur : " + Room.checks.size() + " contrôle(s) des parties chargé(s)");
        }
        rooms.gallery = gallery;
        Room.publishedMode = gallery::published;
        this.name = name == null || name.isBlank() ? "Serveur Cable Club" : name;
        hub.avatars(userId -> {
            Accounts.User u = accounts.get(userId);
            return u == null ? 0 : u.avatarAt;
        });
        rooms.onDeleted = id -> {
            chat.roomDeleted(id);
            battles.roomDeleted(id);
        };
        // what each player read in the chat is written every few seconds, not at each message
        timer.scheduleWithFixedDelay(() -> {
            try {
                chat.flush();
            } catch (RuntimeException e) {
                Log.warn("Serveur : " + e.getMessage());
            }
        }, 5, 5, java.util.concurrent.TimeUnit.SECONDS);
        hub.onPresence((userId, online) -> {
            Accounts.User u = accounts.get(userId);
            if (u != null) {
                for (String f : u.friends) {
                    hub.send(f, "friends", Json.map("reason", "presence", "userId", userId, "online", online));
                }
            }
            rooms.presence(userId);
        });
    }

    /**
     * Starts listening.
     *
     * @param bind     address to listen on ("0.0.0.0" = every network interface)
     * @param keystore optional PKCS#12 file for HTTPS (null = plain HTTP)
     */
    public void start(String bind, int port, Path keystore, String keystorePassword) throws Exception {
        tuneHttp(); // no effect once an HTTP server was created: slt.Main sets them first
        InetSocketAddress addr = new InetSocketAddress(bind == null ? "0.0.0.0" : bind, port);
        if (keystore != null) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            char[] pw = keystorePassword == null ? new char[0] : keystorePassword.toCharArray();
            try (InputStream in = Files.newInputStream(keystore)) {
                ks.load(in, pw);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, pw);
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(kmf.getKeyManagers(), null, null);
            HttpsServer s = HttpsServer.create(addr, BACKLOG);
            s.setHttpsConfigurator(new HttpsConfigurator(ssl));
            http = s;
        } else {
            http = HttpServer.create(addr, BACKLOG);
        }
        ServerApi api = new ServerApi(this);
        http.createContext("/", api::handle);
        http.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        http.start();
        this.port = http.getAddress().getPort();
        Log.info("Serveur SLT « " + name + " » à l'écoute sur " + (keystore != null ? "https" : "http") + "://"
                + addr.getHostString() + ":" + this.port);
    }

    public int port() {
        return port;
    }

    public void stop() {
        chat.flush();
        if (http != null) {
            http.stop(1);
            http = null;
            Log.info("Serveur SLT arrêté");
        }
    }

    int userCount() {
        return accounts.count();
    }

    /** {@code CableClub.exe --server [--port=47330] [--data=dossier] [--bind=0.0.0.0] [--name=…] [--keystore=f.p12 --keystore-password=…]} */
    public static void main(String[] args) throws Exception {
        int port = DEFAULT_PORT;
        String bind = "0.0.0.0";
        String data = null;
        String name = null;
        String keystore = null;
        String keystorePassword = System.getenv("SLT_KEYSTORE_PASSWORD");
        for (String a : args) {
            if (a.startsWith("--port=")) {
                port = Integer.parseInt(a.substring(7));
            } else if (a.startsWith("--bind=")) {
                bind = a.substring(7);
            } else if (a.startsWith("--data=")) {
                data = a.substring(7);
            } else if (a.startsWith("--name=")) {
                name = a.substring(7);
            } else if (a.startsWith("--keystore=")) {
                keystore = a.substring(11);
            } else if (a.startsWith("--keystore-password=")) {
                keystorePassword = a.substring(20);
            }
        }
        boolean plain = java.util.Arrays.asList(args).contains("--http");
        Path dir = Path.of(data != null ? data : "serveur").toAbsolutePath();
        Log.init(dir, "serveur.log");
        SltServer s = new SltServer(dir, name);
        if (keystore == null && !plain) {
            // encrypted by default with the server's own certificate; friends join with the invitation code
            Certificates.Cert cert = Certificates.ensure(dir);
            s.start(bind, port, cert.keystore(), cert.password());
            java.util.List<String> urls = new java.util.ArrayList<>();
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                for (java.net.InetAddress a : java.util.Collections.list(ni.getInetAddresses())) {
                    if (a instanceof java.net.Inet4Address && !a.isLinkLocalAddress()) {
                        urls.add("https://" + a.getHostAddress() + ":" + s.port());
                    } else if (a instanceof java.net.Inet6Address && (a.getAddress()[0] & 0xE0) == 0x20) {
                        String h = a.getHostAddress();
                        urls.add("https://[" + (h.contains("%") ? h.substring(0, h.indexOf('%')) : h) + "]:" + s.port());
                    }
                }
            }
            Log.info("Empreinte du certificat : " + cert.fingerprint());
            Log.info("Code d'invitation (à coller dans SLT) : " + slt.Invitation.encode(s.name, urls, cert.fingerprint()));
            Log.info("Si ce serveur a une adresse publique ou un nom de domaine, les joueurs peuvent aussi saisir "
                    + "https://ADRESSE:" + s.port() + " (le certificat est mémorisé au premier contact).");
        } else {
            s.start(bind, port, keystore == null ? null : Path.of(keystore), keystorePassword);
        }
        Log.info("Données du serveur : " + dir);
        Thread.currentThread().join();
    }
}
