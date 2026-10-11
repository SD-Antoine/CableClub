package slt;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Invitation code of a server: every address it may be reached at (same
 * network, Internet IPv4, IPv6) and the fingerprint of its certificate, in
 * one string to copy: {@code SLT1-…}. The friend's SLT tries the addresses
 * and keeps the one that answers with that certificate.
 */
public final class Invitation {

    private static final String PREFIX = "SLT1-";

    /** What a code holds. */
    public record Code(String name, List<String> urls, String fingerprint) {
    }

    private Invitation() {
    }

    public static String encode(String name, List<String> urls, String fingerprint) {
        String json = Json.stringify(Json.map("n", name, "u", new ArrayList<Object>(urls), "f", fingerprint));
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    public static boolean looksLike(String text) {
        return text != null && text.trim().toUpperCase(java.util.Locale.ROOT).startsWith(PREFIX);
    }

    /** @return the code, or null when the text is not a valid invitation code */
    public static Code decode(String text) {
        if (!looksLike(text)) {
            return null;
        }
        try {
            String b64 = text.trim().substring(PREFIX.length()).replaceAll("\\s", "");
            Map<String, Object> m = Json.parseObject(new String(Base64.getUrlDecoder().decode(b64), StandardCharsets.UTF_8));
            List<String> urls = new ArrayList<>();
            List<Object> u = Json.arr(m.get("u"));
            if (u != null) {
                for (Object o : u) {
                    String s = String.valueOf(o);
                    if (s.startsWith("https://") || s.startsWith("http://")) {
                        urls.add(s);
                    }
                }
            }
            String fp = Json.str(m, "f");
            if (urls.isEmpty() || fp == null || !fp.matches("[0-9A-Fa-f]{64}")) {
                return null;
            }
            return new Code(Json.str(m, "n"), urls, fp.toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return null;
        }
    }
}
