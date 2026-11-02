package slt.client;

/**
 * Non-regression test of the Host check that protects the local API against DNS rebinding
 * (see {@link LocalApi#loopbackHost}). A loopback Host (what the real interface and the game player send) is accepted;
 * an attacker's domain — what a page rebound to 127.0.0.1 carries — is refused.
 */
public final class LocalApiHostTest {

    public static void main(String[] args) {
        int failed = 0;
        // accepted: the real local interface, with or without a port, IPv4 and IPv6 loopback, and no Host at all
        failed += accept("127.0.0.1:47331");
        failed += accept("127.0.0.1");
        failed += accept("localhost:47331");
        failed += accept("localhost");
        failed += accept("[::1]:47331");
        failed += accept("[::1]");
        failed += accept("127.2.3.4:8080");
        failed += accept("[0:0:0:0:0:0:0:1]");
        failed += accept(null);
        failed += accept("");
        // refused: any real domain (DNS rebinding reaches the API with its own domain in Host)
        failed += refuse("evil.example:47331");
        failed += refuse("rebinding.attacker.test");
        failed += refuse("play.cableclub.app");
        failed += refuse("127.0.0.1.attacker.test");
        failed += refuse("127x0x0x1");
        failed += refuse("192.168.1.10:47331");
        failed += refuse("10.0.0.5");
        System.out.println(failed == 0 ? "ok     contrôle de l'en-tête Host (DNS rebinding)"
                : "ÉCHEC  " + failed + " cas du contrôle de l'en-tête Host");
        System.exit(failed == 0 ? 0 : 1);
    }

    private static int accept(String host) {
        if (LocalApi.loopbackHost(host)) {
            return 0;
        }
        System.out.println("  devait être accepté : " + host);
        return 1;
    }

    private static int refuse(String host) {
        if (!LocalApi.loopbackHost(host)) {
            return 0;
        }
        System.out.println("  devait être refusé : " + host);
        return 1;
    }
}
