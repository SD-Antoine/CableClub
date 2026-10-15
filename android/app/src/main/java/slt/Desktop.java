package slt;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

/** What the PC does with its desktop, on the phone: web pages open in the phone's browser; there is no explorer. */
public final class Desktop {

    private static volatile Context context;

    private Desktop() {
    }

    /** Given once by the application. */
    public static void init(Context c) {
        context = c.getApplicationContext();
    }

    public static void browse(String url) {
        Context c = context;
        if (c == null) {
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (RuntimeException e) {
            Log.warn("Ouverture du navigateur impossible : " + e.getMessage());
        }
    }

    /** No file explorer on a phone: the interface does not offer it there. */
    public static void reveal(String path) {
        throw new Http.Fail(400, "Pas d'explorateur de fichiers sur téléphone.");
    }
}
