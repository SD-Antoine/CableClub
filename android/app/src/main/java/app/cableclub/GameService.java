package app.cableclub;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import slt.Log;
import slt.player.GameRun;

import java.nio.file.Path;
import java.util.Map;

/**
 * The game of a room, in the app's process ":jeu" (the emulator core keeps global state: one game per process, as
 * on the PC). Started by the application with the game's arguments; the process ends with the game.
 */
public final class GameService extends Service {

    /** Codes of the application (see slt.client.GameProcess). */
    private static final int QUIT = IBinder.FIRST_CALL_TRANSACTION;
    private static final int PID = IBinder.FIRST_CALL_TRANSACTION + 1;

    private volatile Runnable quit;
    private volatile boolean started;

    private final Binder binder = new Binder() {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == QUIT) {
                end();
                return true;
            }
            if (code == PID) {
                if (reply != null) {
                    reply.writeInt(android.os.Process.myPid());
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (started) {
            return binder;
        }
        started = true;
        String[] args = intent.getStringArrayExtra("args");
        String secret = intent.getStringExtra("secret");
        Map<String, String> a = GameRun.parse(args == null ? new String[0] : args);
        Path data = Path.of(a.getOrDefault("--data", getFilesDir().getAbsolutePath()));
        Log.init(data, "lecteur.log");
        Thread t = new Thread(() -> {
            try {
                quit = GameRun.start(a, data, secret);
            } catch (Throwable e) {
                Log.error("Impossible de lancer le jeu", e);
                System.exit(1);
            }
        }, "slt-lancement");
        t.start();
        return binder;
    }

    /** The application let the game go (closed, or gone): the game stops with it, as on the PC. */
    @Override
    public boolean onUnbind(Intent intent) {
        end();
        return false;
    }

    private void end() {
        Runnable q = quit;
        if (q != null) {
            q.run(); // the session writes the save, then ends the process
        }
        // a game that does not stop (or never started) does not stay behind
        Thread guard = new Thread(() -> {
            try {
                Thread.sleep(q == null ? 500 : 8000);
            } catch (InterruptedException ignored) {
                // end now
            }
            System.exit(0);
        }, "slt-fin");
        guard.setDaemon(true);
        guard.start();
    }
}
