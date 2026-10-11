package slt.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * UPnP Internet Gateway Device client: finds the box on the local network
 * and asks it to forward a TCP port to this PC (what an online game does to
 * be reachable), and reads the box's public IPv4 address.
 */
final class Upnp {

    /** The box: where to send the commands, and this PC's address as the box sees it. */
    record Gateway(String controlUrl, String serviceType, String localIp, String model) {
    }

    /** A refusal of the box (UPnP error code and description). */
    static final class Refused extends IOException {
        final int code;

        Refused(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    private static final String[] SEARCH = {
        "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
        "urn:schemas-upnp-org:device:InternetGatewayDevice:2",
        "urn:schemas-upnp-org:service:WANIPConnection:1",
    };
    private static final Pattern LOCATION = Pattern.compile("(?im)^location:\\s*(\\S+)");
    private static final Pattern SERVICE = Pattern.compile(
            "<service>\\s*<serviceType>(urn:schemas-upnp-org:service:(?:WANIPConnection|WANPPPConnection):\\d)</serviceType>.*?"
                    + "<controlURL>([^<]*)</controlURL>", Pattern.DOTALL);

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

    /** @return the box, or null when no UPnP gateway answers (UPnP disabled on the box) */
    Gateway discover() throws IOException {
        Set<String> locations = new LinkedHashSet<>();
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(800);
            InetSocketAddress group = new InetSocketAddress(InetAddress.getByName("239.255.255.250"), 1900);
            for (String st : SEARCH) {
                byte[] msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: "
                        + st + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
                socket.send(new DatagramPacket(msg, msg.length, group));
            }
            long end = System.currentTimeMillis() + 2500;
            byte[] buf = new byte[4096];
            while (System.currentTimeMillis() < end) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                try {
                    socket.receive(p);
                } catch (SocketTimeoutException e) {
                    continue;
                }
                Matcher m = LOCATION.matcher(new String(p.getData(), 0, p.getLength(), StandardCharsets.US_ASCII));
                if (m.find()) {
                    locations.add(m.group(1));
                }
            }
        }
        for (String loc : locations) {
            Gateway g = describe(loc);
            if (g != null) {
                return g;
            }
        }
        return null;
    }

    private Gateway describe(String location) {
        try {
            String xml = get(location);
            Matcher m = SERVICE.matcher(xml);
            if (!m.find()) {
                return null;
            }
            URI base = URI.create(location);
            Matcher urlBase = Pattern.compile("<URLBase>([^<]*)</URLBase>").matcher(xml);
            if (urlBase.find() && !urlBase.group(1).isBlank()) {
                base = URI.create(urlBase.group(1).trim());
            }
            String control = base.resolve(m.group(2).trim()).toString();
            Matcher model = Pattern.compile("<modelName>([^<]*)</modelName>").matcher(xml);
            // this PC's address on the network that leads to the box
            String localIp;
            try (DatagramSocket s = new DatagramSocket()) {
                s.connect(InetAddress.getByName(base.getHost()), base.getPort() > 0 ? base.getPort() : 80);
                localIp = s.getLocalAddress().getHostAddress();
            }
            return new Gateway(control, m.group(1), localIp, model.find() ? model.group(1).trim() : "box");
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    String externalIp(Gateway g) throws IOException {
        String r = soap(g, "GetExternalIPAddress", "");
        Matcher m = Pattern.compile("<NewExternalIPAddress>([^<]*)</NewExternalIPAddress>").matcher(r);
        return m.find() ? m.group(1).trim() : null;
    }

    /** Forwards TCP {@code port} of the box to this PC; {@code lease} in seconds (0 = until removed). */
    void addMapping(Gateway g, int port, int lease, String description) throws IOException {
        soap(g, "AddPortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol><NewInternalPort>" + port + "</NewInternalPort>"
                + "<NewInternalClient>" + g.localIp() + "</NewInternalClient><NewEnabled>1</NewEnabled>"
                + "<NewPortMappingDescription>" + description + "</NewPortMappingDescription>"
                + "<NewLeaseDuration>" + lease + "</NewLeaseDuration>");
    }

    void deleteMapping(Gateway g, int port) throws IOException {
        soap(g, "DeletePortMapping", "<NewRemoteHost></NewRemoteHost><NewExternalPort>" + port + "</NewExternalPort>"
                + "<NewProtocol>TCP</NewProtocol>");
    }

    private String soap(Gateway g, String action, String args) throws IOException {
        String body = "<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" "
                + "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:" + action + " xmlns:u=\""
                + g.serviceType() + "\">" + args + "</u:" + action + "></s:Body></s:Envelope>";
        HttpRequest r = HttpRequest.newBuilder(URI.create(g.controlUrl())).timeout(Duration.ofSeconds(6))
                .header("Content-Type", "text/xml; charset=\"utf-8\"")
                .header("SOAPAction", "\"" + g.serviceType() + "#" + action + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        try {
            HttpResponse<String> res = http.send(r, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (res.statusCode() != 200) {
                Matcher code = Pattern.compile("<errorCode>(\\d+)</errorCode>").matcher(res.body());
                Matcher desc = Pattern.compile("<errorDescription>([^<]*)</errorDescription>").matcher(res.body());
                throw new Refused(code.find() ? Integer.parseInt(code.group(1)) : res.statusCode(),
                        desc.find() ? desc.group(1) : "refus de la box (" + res.statusCode() + ")");
            }
            return res.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrompu");
        }
    }

    private String get(String url) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(4)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body();
    }
}
