package slt.client;

import slt.server.Certificates;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Encrypted connections to an SLT server recognised by the fingerprint of its
 * own certificate (given by the invitation code). Without a fingerprint, the
 * usual certificate checks apply (server behind a real certificate), or the
 * certificate is only recorded (first contact with a typed address).
 */
final class PinnedTls {

    private PinnedTls() {
    }

    /**
     * @param pin      expected fingerprint, read at each connection (null = none)
     * @param seen     receives the fingerprint of the server's certificate
     * @param acceptAny without a pin: accept any certificate (first contact, the fingerprint is then kept)
     */
    static SSLContext context(Supplier<String> pin, AtomicReference<String> seen, boolean acceptAny) {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            X509ExtendedTrustManager system = null;
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager x) {
                    system = x;
                }
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new Pinning(pin, seen, acceptAny, system)}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Another server answered (not the certificate of the invitation code). */
    static final class Mismatch extends CertificateException {
        Mismatch() {
            super("Ce n'est pas le serveur attendu (empreinte différente)");
        }
    }

    private static final class Pinning extends X509ExtendedTrustManager {
        private final Supplier<String> pin;
        private final AtomicReference<String> seen;
        private final boolean acceptAny;
        private final X509ExtendedTrustManager system;

        Pinning(Supplier<String> pin, AtomicReference<String> seen, boolean acceptAny, X509ExtendedTrustManager system) {
            this.pin = pin;
            this.seen = seen;
            this.acceptAny = acceptAny;
            this.system = system;
        }

        /** @return true when the pin (or "accept any") decides, false to fall back on the system checks */
        private boolean pinned(X509Certificate[] chain) throws CertificateException {
            if (chain == null || chain.length == 0) {
                throw new CertificateException("Aucun certificat");
            }
            String fp = Certificates.sha256(chain[0].getEncoded());
            if (seen != null) {
                seen.set(fp);
            }
            String expected = pin.get();
            if (expected != null) {
                if (!expected.equalsIgnoreCase(fp)) {
                    throw new Mismatch();
                }
                return true;
            }
            return acceptAny;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            if (!pinned(chain)) {
                system.checkServerTrusted(chain, authType, engine);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            if (!pinned(chain)) {
                system.checkServerTrusted(chain, authType, socket);
            }
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            if (!pinned(chain)) {
                ((X509TrustManager) system).checkServerTrusted(chain, authType);
            }
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
            throw new CertificateException("Pas de certificat client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
            throw new CertificateException("Pas de certificat client");
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new CertificateException("Pas de certificat client");
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return system == null ? new X509Certificate[0] : system.getAcceptedIssuers();
        }
    }
}
