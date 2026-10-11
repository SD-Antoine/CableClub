package slt.client;

import slt.Http;
import slt.Invitation;
import slt.Json;
import slt.Log;
import slt.server.Certificates;
import slt.server.SltServer;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * "Héberger le serveur sur ce PC": the SLT server running inside this
 * application, encrypted with its own certificate. With "Accessible depuis
 * Internet", SLT asks the box (UPnP) to forward the server's port to this PC,
 * renews it while SLT runs and removes it when hosting stops — what a VPN
 * like Hamachi or Tailscale would otherwise be needed for.
 */
final class Hosting {

    private static final int LEASE = 3600;

    private final Path dir;
    private final Upnp upnp = new Upnp();
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "slt-upnp");
        t.setDaemon(true);
        return t;
    });
    private volatile SltServer server;
    private volatile Certificates.Cert cert;
    private volatile String name;
    // Internet access through the box
    private volatile boolean internet;
    private volatile String netState = "off";   // off, opening, open, error
    private volatile String netMessage;
    private volatile String publicIp;
    private volatile Upnp.Gateway gateway;
    private volatile boolean permanentLease;
    private ScheduledFuture<?> renewal;
    // Windows firewall: can friends reach SLT on the network in use?
    private volatile Map<String, Object> firewall;
    private volatile long firewallAt;
    private final java.util.concurrent.atomic.AtomicBoolean checkingFirewall = new java.util.concurrent.atomic.AtomicBoolean();
    private final Runnable changed;

    /** @param changed told whenever {@link #status()} changes (the interface shows it live) */
    Hosting(Path dataDir, Runnable changed) {
        this.dir = dataDir.resolve("serveur");
        this.changed = changed;
    }

    private void changed() {
        try {
            changed.run();
        } catch (RuntimeException e) {
            Log.warn("Hébergement : " + e.getMessage());
        }
    }

    synchronized Map<String, Object> start(int port, String serverName, boolean wantInternet) {
        if (server == null) {
            try {
                cert = Certificates.ensure(dir);
                SltServer s = new SltServer(dir, serverName);
                s.start("0.0.0.0", port, cert.keystore(), cert.password());
                server = s;
                name = serverName;
                refreshFirewall();
            } catch (java.net.BindException e) {
                throw new Http.Fail(409, "Le port " + port + " est déjà utilisé sur ce PC (un autre serveur SLT ?).");
            } catch (Exception e) {
                Log.error("Hébergement impossible", e);
                throw new Http.Fail(500, "Impossible de démarrer le serveur : " + e.getMessage());
            }
        }
        setInternet(wantInternet);
        changed();
        return status();
    }

    synchronized void stop() {
        setInternet(false);
        if (server != null) {
            server.stop();
            server = null;
            changed();
        }
    }

    boolean running() {
        return server != null;
    }

    int port() {
        SltServer s = server;
        return s == null ? SltServer.DEFAULT_PORT : s.port();
    }

    /** Address and fingerprint this application uses to reach its own server. */
    String localUrl() {
        return "https://127.0.0.1:" + port();
    }

    String fingerprint() {
        Certificates.Cert c = cert;
        return c == null ? null : c.fingerprint();
    }

    // ------------------------------------------------------- Internet access

    /** Opens (or closes) the server's port on the box, in the background. */
    synchronized void setInternet(boolean on) {
        if (on == internet && !(on && "error".equals(netState))) {
            return;
        }
        internet = on;
        if (renewal != null) {
            renewal.cancel(false);
            renewal = null;
        }
        if (on) {
            netState = "opening";
            netMessage = "Recherche de la box…";
            timer.execute(this::open);
            changed();
        } else {
            Upnp.Gateway g = gateway;
            int port = port();
            netState = "off";
            netMessage = null;
            if (g != null) {
                timer.execute(() -> {
                    try {
                        upnp.deleteMapping(g, port);
                        Log.info("Port " + port + " refermé sur la box");
                    } catch (Exception e) {
                        Log.warn("Port non refermé sur la box : " + e.getMessage());
                    }
                });
            }
            changed();
        }
    }

    private void open() {
        if (!internet || server == null) {
            return;
        }
        int port = port();
        try {
            Upnp.Gateway g = gateway != null ? gateway : upnp.discover();
            if (g == null) {
                fail("Votre box ne répond pas à l'ouverture automatique (UPnP). Activez « UPnP » dans les réglages de la box, "
                        + "ou ouvrez le port TCP " + port + " vers ce PC.");
                return;
            }
            gateway = g;
            try {
                upnp.addMapping(g, port, permanentLease ? 0 : LEASE, "SLT - Soul Link Together");
            } catch (Upnp.Refused e) {
                if (e.code == 725 && !permanentLease) {
                    // the box only accepts mappings without a time limit: removed when hosting stops
                    permanentLease = true;
                    upnp.addMapping(g, port, 0, "SLT - Soul Link Together");
                } else {
                    throw e;
                }
            }
            publicIp = upnp.externalIp(g);
            String unreachable = unreachableBehind(publicIp, g.model(), port);
            if (unreachable != null) {
                fail(unreachable);
                return;
            }
            boolean was = "open".equals(netState);
            netState = "open";
            netMessage = "Port " + port + " ouvert sur la box (" + g.model() + ")";
            if (!was) {
                Log.info(netMessage + ", adresse publique " + publicIp);
                changed();
            }
            if (!permanentLease) {
                synchronized (this) {
                    renewal = timer.schedule(this::open, LEASE / 2, TimeUnit.SECONDS);
                }
            }
        } catch (Upnp.Refused e) {
            fail(e.code == 718
                    ? "Le port " + port + " de la box est déjà redirigé vers un autre appareil."
                    : "La box refuse d'ouvrir le port " + port + " (" + e.getMessage() + "). Si votre adresse IPv4 est partagée "
                    + "(certaines offres Free), les ports utilisables sont limités : voyez les réglages de la box.");
        } catch (Exception e) {
            fail("Ouverture du port impossible : " + e.getMessage());
        }
    }

    private void fail(String message) {
        boolean again = "error".equals(netState) && message.equals(netMessage);
        netState = "error";
        netMessage = message;
        if (!again) {
            Log.warn(message);
            changed();
        }
        synchronized (this) {
            if (internet) {
                renewal = timer.schedule(this::open, 300, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Why the public address given by the box cannot be reached from the
     * Internet, or null when it can: the box itself sits behind another
     * router (double box), or the operator shares one IPv4 address between
     * several customers (CGNAT).
     */
    static String unreachableBehind(String ip, String model, int port) {
        if (ip == null || ip.isBlank() || ip.startsWith("0.")) {
            return "La box (" + model + ") n'indique pas d'adresse Internet : est-elle connectée ?";
        }
        String kind = addressKind(ip);
        byte[] b = ipv4(ip);
        if (b != null && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64) {
            return "Votre opérateur partage votre adresse IPv4 avec d'autres abonnés (" + ip + ") : personne ne peut vous joindre "
                    + "en IPv4. Demandez-lui une adresse IPv4 dédiée (souvent gratuit), ou laissez héberger un ami.";
        }
        if ("local".equals(kind)) {
            return "Le port est ouvert sur « " + model + " », mais cet appareil est lui-même branché derrière une autre box "
                    + "(son adresse : " + ip + "). Ouvrez aussi le port TCP " + port + " sur la box principale, vers cet appareil.";
        }
        return null;
    }

    // -------------------------------------------------------- Windows firewall

    /** Reads the firewall again in the background (it takes about a second). */
    void refreshFirewall() {
        if (!Firewall.supported() || !checkingFirewall.compareAndSet(false, true)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                Map<String, Object> before = firewall;
                Map<String, Object> now = Firewall.check();
                firewall = now;
                firewallAt = System.currentTimeMillis();
                if (before == null || !before.equals(now)) {
                    String why = Firewall.describe(now);
                    if (why != null) {
                        Log.warn(why);
                    }
                    changed();
                }
            } finally {
                checkingFirewall.set(false);
            }
        });
    }

    /** Asks Windows (administrator prompt) to let friends reach SLT on every network. */
    Map<String, Object> allowFirewall() {
        if (!Firewall.supported()) {
            throw new Http.Fail(400, "Réglage propre à Windows.");
        }
        boolean ok = Firewall.allow();
        firewall = Firewall.check();
        firewallAt = System.currentTimeMillis();
        changed();
        if (!ok) {
            throw new Http.Fail(409, "Autorisation annulée : Windows doit demander la permission (fenêtre « Contrôle de compte "
                    + "d'utilisateur »), répondez Oui.");
        }
        return status();
    }

    // ---------------------------------------------------------------- status

    Map<String, Object> status() {
        SltServer s = server;
        if (s == null) {
            return Json.map("running", false);
        }
        int port = s.port();
        List<Object> addresses = new ArrayList<>();
        List<Object> networks = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        String ip4 = publicIp;
        if ("open".equals(netState) && ip4 != null && !ip4.isBlank()) {
            networks.add(Json.map("address", ip4 + ":" + port, "kind", "public", "name", "Internet (box)"));
            urls.add("https://" + ip4 + ":" + port);
        }
        for (String[] n : networks()) {
            boolean v6 = n[0].contains(":");
            String address = v6 ? "[" + n[0] + "]:" + port : n[0] + ":" + port;
            addresses.add(address);
            networks.add(Json.map("address", address, "kind", n[1], "name", n[2]));
            urls.add("https://" + address);
        }
        String code = cert == null ? null : Invitation.encode(name, urls, cert.fingerprint());
        Map<String, Object> fw = firewall;
        if (System.currentTimeMillis() - firewallAt > 60_000) {
            refreshFirewall(); // the network in use may have changed
        }
        return Json.map("running", true, "port", (long) port, "addresses", addresses, "networks", networks,
                "fingerprint", fingerprint(), "invitation", code,
                "internet", Json.map("on", internet, "state", netState, "message", netMessage, "publicIp", ip4),
                "firewall", fw == null ? null : Json.map("state", Json.str(fw, "state"), "message", Firewall.describe(fw)),
                "dataDir", dir.toString());
    }

    /**
     * Addresses of this PC with the kind of network: "local" (inside the box:
     * only friends on the same network), "vpn" (Tailscale, ZeroTier, Radmin,
     * Hamachi), "public" (directly on the Internet, e.g. IPv6). IPv6 addresses
     * are listed only when public (2000::/3).
     */
    static List<String[]> networks() {
        List<String[]> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) {
                    continue;
                }
                String nm = ni.getDisplayName() == null ? ni.getName() : ni.getDisplayName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLinkLocalAddress()) {
                        out.add(new String[]{a.getHostAddress(), kind(a.getAddress(), nm), nm});
                    } else if (a instanceof Inet6Address && (a.getAddress()[0] & 0xE0) == 0x20) {
                        String host = a.getHostAddress();
                        int scope = host.indexOf('%');
                        out.add(new String[]{scope < 0 ? host : host.substring(0, scope), "public", nm + " (IPv6)"});
                    }
                }
            }
        } catch (Exception e) {
            Log.warn("Adresses réseau indisponibles : " + e.getMessage());
        }
        out.sort((x, y) -> rank(x[1]) - rank(y[1]));
        return out;
    }

    private static int rank(String kind) {
        return switch (kind) {
            case "vpn" -> 0;
            case "public" -> 1;
            default -> 2;
        };
    }

    /**
     * Kind of an address found in an invitation code: "ipv6", "local" (inside
     * one home network), "vpn", or "public" (an Internet address or a name).
     */
    static String addressKind(String host) {
        if (host == null) {
            return "public";
        }
        String h = host.startsWith("[") ? host.substring(1, host.length() - 1) : host;
        if (h.contains(":")) {
            return "ipv6";
        }
        byte[] b = ipv4(h);
        return b == null ? "public" : kind(b, "");
    }

    private static byte[] ipv4(String s) {
        String[] parts = s.split("\\.");
        if (parts.length != 4) {
            return null;
        }
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            try {
                int v = Integer.parseInt(parts[i]);
                if (v < 0 || v > 255) {
                    return null;
                }
                b[i] = (byte) v;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return b;
    }

    private static String kind(byte[] ip, String name) {
        int a = ip[0] & 0xFF;
        int b = ip[1] & 0xFF;
        String n = name.toLowerCase(java.util.Locale.ROOT);
        if ((a == 100 && b >= 64 && b <= 127) || a == 25 || a == 26 || n.contains("tailscale") || n.contains("zerotier")
                || n.contains("radmin") || n.contains("hamachi")) {
            return "vpn";
        }
        if (a == 10 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168)) {
            return "local";
        }
        return "public";
    }

    /** True for an address that only works inside one home network (box). */
    static boolean isLocalAddress(String host) {
        try {
            InetAddress a = InetAddress.getByName(host);
            return a instanceof Inet4Address && "local".equals(kind(a.getAddress(), ""));
        } catch (Exception e) {
            return false;
        }
    }
}
