package slt.client;

import slt.Http;
import slt.Json;
import slt.Version;

import java.util.Map;
import java.util.function.Consumer;

/**
 * Updates of the Play Store version: Google Play installs them (its rules forbid an app to update itself any other
 * way). Cable Club does not look for a new version anywhere; the settings say so.
 */
final class Updates {

    static final String PAGE = "https://play.google.com/store/apps/details?id=app.cableclub";

    private final Map<String, Object> status = Json.map("state", "store", "current", Version.VERSION);

    Updates(ClientApp app) {
    }

    Map<String, Object> status() {
        return status;
    }

    void start() {
    }

    Map<String, Object> check() {
        return status;
    }

    Map<String, Object> install(Consumer<String> progress) {
        throw new Http.Fail(409, "Cette version de Cable Club se met à jour par le Play Store.");
    }
}
