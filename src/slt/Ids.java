package slt;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random identifiers, invitation codes, session tokens and hashes. */
public final class Ids {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] ID_CHARS = "abcdefghijkmnopqrstuvwxyz23456789".toCharArray();
    // no 0/O, 1/I/L: codes are read aloud and typed by hand
    private static final char[] CODE_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private Ids() {
    }

    public static String id(int len) {
        return random(ID_CHARS, len);
    }

    public static String code(int len) {
        return random(CODE_CHARS, len);
    }

    private static String random(char[] chars, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars[RNG.nextInt(chars.length)]);
        }
        return sb.toString();
    }

    /** 256-bit secret, URL-safe. */
    public static String token() {
        byte[] b = new byte[32];
        RNG.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static byte[] bytes(int n) {
        byte[] b = new byte[n];
        RNG.nextBytes(b);
        return b;
    }

    public static long seed() {
        return RNG.nextLong() & Long.MAX_VALUE;
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time comparison. */
    public static boolean same(byte[] a, byte[] b) {
        return MessageDigest.isEqual(a, b);
    }
}
