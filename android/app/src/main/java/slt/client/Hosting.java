package slt.client;

import slt.Http;
import slt.Json;

import java.nio.file.Path;
import java.util.Map;

/**
 * A phone does not host a Cable Club server: it sleeps, the system ends the apps it does not show, and mobile
 * networks do not let other players in. The settings say so; the PC (or the official server) hosts.
 */
final class Hosting {

    Hosting(Path dataDir, Runnable changed) {
    }

    Map<String, Object> start(int port, String serverName, boolean wantInternet) {
        throw new Http.Fail(409, "Un téléphone ne peut pas héberger de serveur Cable Club : utilisez le serveur officiel ou celui d'un PC.");
    }

    void stop() {
    }

    boolean running() {
        return false;
    }

    int port() {
        return -1;
    }

    String localUrl() {
        return null;
    }

    String fingerprint() {
        return null;
    }

    void setInternet(boolean on) {
        throw new Http.Fail(409, "Pas d'hébergement sur téléphone.");
    }

    Map<String, Object> allowFirewall() {
        throw new Http.Fail(409, "Pas d'hébergement sur téléphone.");
    }

    Map<String, Object> status() {
        return Json.map("running", false, "unsupported", true);
    }
}
