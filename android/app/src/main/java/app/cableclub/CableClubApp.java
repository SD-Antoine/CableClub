package app.cableclub;

import android.app.Application;
import slt.Log;
import slt.client.ClientApp;

import java.io.File;
import java.nio.file.Path;

/**
 * The app. Its main process runs Cable Club's application (local API, server link, ROM library); the game runs in
 * the process ":jeu" (see GameService), where nothing of this starts.
 */
public final class CableClubApp extends Application {

    private static volatile ClientApp client;
    private static volatile Throwable failure;

    @Override
    public void onCreate() {
        super.onCreate();
        if (!getPackageName().equals(Application.getProcessName())) {
            return; // the game's process
        }
        try {
            client = ClientApp.start(this, dataDir(this));
        } catch (Throwable t) {
            failure = t;
            Log.error("Cable Club n'a pas pu démarrer", t);
        }
    }

    /** Where Cable Club keeps its data: the app's folder on the phone's storage (visible over USB), or its private one. */
    static Path dataDir(android.content.Context c) {
        File external = c.getExternalFilesDir(null);
        File dir = external != null ? external : c.getFilesDir();
        return dir.toPath().resolve("data");
    }

    static ClientApp client() {
        return client;
    }

    static Throwable failure() {
        return failure;
    }
}
