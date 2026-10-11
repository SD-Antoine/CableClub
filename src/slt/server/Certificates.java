package slt.server;

import slt.Ids;
import slt.Json;
import slt.Log;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The server's own certificate, made once with the keytool shipped in SLT's
 * Java runtime. Connections are encrypted; clients recognise the server by
 * the certificate's fingerprint (given in the invitation code), so no
 * certificate authority or domain name is needed.
 */
public final class Certificates {

    /** The keystore of the server and the SHA-256 fingerprint of its certificate. */
    public record Cert(Path keystore, String password, String fingerprint) {
    }

    private Certificates() {
    }

    public static synchronized Cert ensure(Path dir) throws IOException {
        Files.createDirectories(dir);
        Path ks = dir.resolve("certificat.p12");
        Path meta = dir.resolve("certificat.json");
        if (Files.isRegularFile(ks) && Files.isRegularFile(meta)) {
            try {
                Map<String, Object> m = Json.parseObject(Files.readString(meta));
                String pw = Json.str(m, "password");
                return new Cert(ks, pw, fingerprint(ks, pw));
            } catch (IOException | RuntimeException e) {
                Log.warn("Certificat du serveur illisible, nouveau certificat : " + e.getMessage());
            }
        }
        String pw = Ids.token();
        Files.deleteIfExists(ks);
        String exe = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "keytool.exe" : "keytool").toString();
        Process p = new ProcessBuilder(List.of(exe, "-genkeypair", "-alias", "slt", "-keyalg", "EC", "-groupname", "secp256r1",
                "-sigalg", "SHA256withECDSA", "-dname", "CN=Serveur SLT", "-validity", "7300", "-storetype", "PKCS12",
                "-keystore", ks.toString(), "-storepass", pw, "-keypass", pw))
                .redirectErrorStream(true).start();
        String output = new String(p.getInputStream().readAllBytes());
        try {
            if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0 || !Files.isRegularFile(ks)) {
                throw new IOException("keytool : " + output.trim());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu");
        }
        Files.writeString(meta, Json.stringify(Json.map("password", pw)));
        Cert c = new Cert(ks, pw, fingerprint(ks, pw));
        Log.info("Certificat du serveur créé (empreinte " + c.fingerprint() + ")");
        return c;
    }

    private static String fingerprint(Path ks, String pw) throws IOException {
        try (InputStream in = Files.newInputStream(ks)) {
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(in, pw.toCharArray());
            Certificate cert = store.getCertificate("slt");
            return sha256(cert.getEncoded());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage(), e);
        }
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
