package slt.client;

import slt.Log;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The networks of this device and the kinds of addresses (local network, VPN, Internet). */
final class Networks {

    private Networks() {
    }

    /**
     * Addresses of this PC with the kind of network: "local" (inside the box:
     * only friends on the same network), "vpn" (Tailscale, ZeroTier, Radmin,
     * Hamachi), "public" (directly on the Internet, e.g. IPv6). IPv6 addresses
     * are listed only when public (2000::/3).
     */
    static List<String[]> list() {
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

    static byte[] ipv4(String s) {
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

    static String kind(byte[] ip, String name) {
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
