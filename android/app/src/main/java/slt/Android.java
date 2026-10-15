package slt;

import android.content.Context;

/** The application's context, for the classes of Cable Club that need Android (game process, updates, browser). */
public final class Android {

    private static volatile Context context;

    private Android() {
    }

    public static void init(Context c) {
        context = c.getApplicationContext();
    }

    public static Context context() {
        Context c = context;
        if (c == null) {
            throw new IllegalStateException("Application Android non initialisée");
        }
        return c;
    }
}
