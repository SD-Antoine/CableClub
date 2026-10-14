package slt.client;

import slt.Json;
import slt.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * The Windows firewall, as friends joining the server hosted here meet it:
 * SLT must be allowed to receive connections on the network in use. When
 * Windows asked, ticking only « Réseaux privés » while Windows calls the
 * network public (its default) creates a rule that silently blocks every
 * friend: the most common reason nobody can join.
 */
final class Firewall {

    static final String RULE = "SLT - Soul Link Together";

    private Firewall() {
    }

    /** The program friends connect to (SLT.exe once packaged, java.exe in development). */
    static Path program() {
        return ProcessHandle.current().info().command().map(Path::of).orElse(null);
    }

    static boolean supported() {
        return System.getProperty("os.name", "").startsWith("Windows") && program() != null;
    }

    /**
     * State for the networks in use: "ok", "blocked" (a rule blocks SLT),
     * "missing" (no rule yet: Windows blocks unless allowed), "off" (firewall
     * disabled), "unknown" (could not be read).
     */
    static Map<String, Object> check() {
        Path exe = program();
        String script = "$exe = " + quote(exe.toString()) + "\n"
                + "$prof = @(Get-NetConnectionProfile -ErrorAction SilentlyContinue | ForEach-Object { \"$($_.NetworkCategory)\" })\n"
                + "$fw = @(Get-NetFirewallProfile | ForEach-Object { @{ n = \"$($_.Name)\"; on = \"$($_.Enabled)\" } })\n"
                + "$rules = @(Get-NetFirewallApplicationFilter -Program $exe -ErrorAction SilentlyContinue"
                + " | Get-NetFirewallRule -ErrorAction SilentlyContinue"
                + " | ForEach-Object { @{ en = \"$($_.Enabled)\"; dir = \"$($_.Direction)\"; act = \"$($_.Action)\"; prof = \"$($_.Profile)\" } })\n"
                + "@{ profiles = $prof; firewall = $fw; rules = $rules } | ConvertTo-Json -Depth 4 -Compress\n";
        try {
            Map<String, Object> r = Json.parseObject(run(script, 20));
            return verdict(Json.arr(r.get("profiles")), Json.arr(r.get("firewall")), Json.arr(r.get("rules")));
        } catch (Exception e) {
            Log.warn("Pare-feu Windows illisible : " + e.getMessage());
            return Json.map("state", "unknown");
        }
    }

    static Map<String, Object> verdict(List<Object> profiles, List<Object> firewall, List<Object> rules) {
        List<String> active = new ArrayList<>();
        for (Object o : profiles == null ? List.of() : profiles) {
            String p = String.valueOf(o);
            String name = p.startsWith("Domain") ? "Domain" : p;
            if (!active.contains(name)) {
                active.add(name);
            }
        }
        if (active.isEmpty()) {
            return Json.map("state", "unknown");
        }
        String worst = "off";
        String network = null;
        for (String p : active) {
            if (!enabled(firewall, p)) {
                continue;
            }
            boolean allow = false;
            boolean block = false;
            for (Object o : rules == null ? List.of() : rules) {
                Map<String, Object> rule = Json.obj(o);
                String prof = Json.str(rule, "prof");
                if (!"True".equals(Json.str(rule, "en")) || !"Inbound".equals(Json.str(rule, "dir"))
                        || prof == null || !(prof.contains("Any") || prof.contains(p))) {
                    continue;
                }
                if ("Block".equals(Json.str(rule, "act"))) {
                    block = true;
                } else if ("Allow".equals(Json.str(rule, "act"))) {
                    allow = true;
                }
            }
            // a blocking rule wins over an allowing one
            String s = block ? "blocked" : allow ? "ok" : "missing";
            if (rank(s) > rank(worst)) {
                worst = s;
                network = p;
            }
        }
        String label = network == null ? null : switch (network) {
            case "Public" -> "public";
            case "Private" -> "privé";
            default -> "d'entreprise";
        };
        return Json.map("state", worst, "network", label);
    }

    private static boolean enabled(List<Object> firewall, String profile) {
        for (Object o : firewall == null ? List.of() : firewall) {
            Map<String, Object> f = Json.obj(o);
            if (profile.equals(Json.str(f, "n"))) {
                return !"False".equals(Json.str(f, "on"));
            }
        }
        return true;
    }

    private static int rank(String s) {
        return switch (s) {
            case "blocked" -> 3;
            case "missing" -> 2;
            case "ok" -> 1;
            default -> 0;
        };
    }

    /**
     * Allows SLT to receive connections on every kind of network, replacing
     * the rules Windows created for it (blocking ones included). Windows asks
     * the player first (administrator prompt).
     *
     * @return false when the player declined or it failed
     */
    static boolean allow() {
        Path exe = program();
        String launcher = launcher(elevated(exe.toString()));
        try {
            Process p = start(launcher);
            p.getInputStream().readAllBytes();
            if (!p.waitFor(180, TimeUnit.SECONDS)) {
                p.destroy();
                return false;
            }
            int code = p.exitValue();
            if (code == 0) {
                Log.info("SLT autorisé dans le pare-feu Windows (" + exe + ")");
                return true;
            }
            Log.warn(code == 1223 ? "Autorisation du pare-feu refusée" : "Autorisation du pare-feu impossible (code " + code + ")");
            return false;
        } catch (Exception e) {
            Log.warn("Autorisation du pare-feu impossible : " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------ PowerShell

    /** Run as administrator: replaces SLT's inbound rules with one allowing it on every network. */
    static String elevated(String exe) {
        return "$exe = " + quote(exe) + "\n"
                + "try {\n"
                + "  Get-NetFirewallApplicationFilter -Program $exe -ErrorAction SilentlyContinue"
                + " | Get-NetFirewallRule -ErrorAction SilentlyContinue | Where-Object { \"$($_.Direction)\" -eq 'Inbound' }"
                + " | Remove-NetFirewallRule -ErrorAction Stop\n"
                + "  New-NetFirewallRule -DisplayName " + quote(RULE) + " -Description " + quote("Laisse vos amis rejoindre le serveur Cable Club hébergé sur ce PC.")
                + " -Direction Inbound -Program $exe -Action Allow -Profile Any -ErrorAction Stop | Out-Null\n"
                + "  exit 0\n"
                + "} catch { exit 2 }\n";
    }

    /** Starts {@code script} elevated (Windows asks the player) and exits with its code, 1223 when declined. */
    static String launcher(String script) {
        return "try {\n"
                + "  $p = Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -PassThru"
                + " -ArgumentList '-NoProfile','-ExecutionPolicy','Bypass','-EncodedCommand','" + encode(script) + "'\n"
                + "  exit $p.ExitCode\n"
                + "} catch { exit 1223 }\n";
    }

    private static String run(String script, int seconds) throws Exception {
        Process p = start("[Console]::OutputEncoding = [Text.Encoding]::UTF8\n" + script);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = p.getInputStream()) {
            in.transferTo(out);
        }
        if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("PowerShell ne répond pas");
        }
        return out.toString(StandardCharsets.UTF_8).trim();
    }

    private static Process start(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-EncodedCommand", encode(script));
        pb.redirectErrorStream(false);
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        return pb.start();
    }

    private static String encode(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static String quote(String s) {
        return "'" + s.replace("'", "''") + "'";
    }

    static String describe(Map<String, Object> fw) {
        String state = Json.str(fw, "state");
        String net = Json.str(fw, "network");
        if ("blocked".equals(state)) {
            return "Le pare-feu Windows bloque Cable Club sur ce réseau" + (net != null ? " (réseau " + net + ")" : "")
                    + " : vos amis ne peuvent pas vous rejoindre.";
        }
        if ("missing".equals(state)) {
            return "Windows n'a pas encore autorisé Cable Club à recevoir des connexions" + (net != null ? " sur ce réseau (réseau " + net + ")" : "")
                    + " : vos amis risquent d'être bloqués.";
        }
        return null;
    }
}
