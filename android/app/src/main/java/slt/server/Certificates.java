package slt.server;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** The phone does not host a server: only the fingerprint of a server's certificate is needed (as on the PC). */
public final class Certificates {

    private Certificates() {
    }

    /** SHA-256 in uppercase hexadecimal, like the PC's fingerprints. */
    public static String sha256(byte[] data) {
        try {
            return slt.Ids.hex(MessageDigest.getInstance("SHA-256").digest(data)).toUpperCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
