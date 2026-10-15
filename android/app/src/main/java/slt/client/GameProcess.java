package slt.client;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import slt.Android;
import slt.Log;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

/**
 * The game of a room runs in a process of its own (the emulator core keeps global state), as on the PC: here the
 * service app.cableclub.GameService, in the process ":jeu" of the app. It ends with the game (the process exits),
 * which this side learns when the connection to the service breaks.
 */
final class GameProcess {

    /** Codes understood by the game's service. */
    static final int QUIT = IBinder.FIRST_CALL_TRANSACTION;
    static final int PID = IBinder.FIRST_CALL_TRANSACTION + 1;

    private static GameProcess current;

    private final Context context;
    private final CountDownLatch ended = new CountDownLatch(1);
    private volatile IBinder binder;
    private volatile boolean alive = true;
    private volatile IntConsumer onExit;
    private ServiceConnection connection;

    private GameProcess(Context context) {
        this.context = context;
    }

    static synchronized GameProcess start(List<String> args, String secret) throws IOException {
        GameProcess previous = current;
        if (previous != null && previous.alive) {
            // one game at a time in the game's process: the previous one must be gone first
            previous.stop();
            previous.waitEnd(4000);
            previous.kill();
            previous.waitEnd(2000);
        }
        Context c = Android.context();
        GameProcess g = new GameProcess(c);
        Intent i = new Intent().setClassName(c, "app.cableclub.GameService")
                // a distinct address for each game: the service gets this game's arguments, not the last one's
                .setData(Uri.parse("cableclub-jeu://" + Long.toHexString(System.nanoTime())))
                .putExtra("args", args.toArray(new String[0]))
                .putExtra("secret", secret);
        g.connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                g.binder = service;
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                g.exited(0);
            }

            @Override
            public void onBindingDied(ComponentName name) {
                g.exited(0);
            }

            @Override
            public void onNullBinding(ComponentName name) {
                g.exited(1);
            }
        };
        if (!c.bindService(i, g.connection, Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT)) {
            throw new IOException("Le jeu n'a pas pu démarrer sur ce téléphone.");
        }
        current = g;
        return g;
    }

    private void exited(int code) {
        if (!alive) {
            return;
        }
        alive = false;
        try {
            context.unbindService(connection);
        } catch (RuntimeException ignored) {
            // already unbound
        }
        ended.countDown();
        IntConsumer done = onExit;
        if (done != null) {
            // Android tells this on its main thread, where no request may go out (the save is sent to the server)
            slt.Jobs.background("slt-fin-du-jeu", () -> done.accept(code));
        }
    }

    private void waitEnd(long millis) {
        try {
            ended.await(millis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean alive() {
        return alive;
    }

    /** Asks the game to end (it writes its save first). */
    void stop() {
        IBinder b = binder;
        if (b == null || !alive) {
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            b.transact(QUIT, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            exited(0);
        } finally {
            data.recycle();
        }
    }

    /** Ends the game's process at once (it did not stop when asked). */
    private void kill() {
        IBinder b = binder;
        if (b == null || !alive) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            b.transact(PID, data, reply, 0);
            int pid = reply.readInt();
            if (pid > 0) {
                Log.warn("Le jeu ne s'est pas arrêté : processus " + pid + " terminé");
                android.os.Process.killProcess(pid);
            }
        } catch (RemoteException e) {
            exited(0);
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Runs once the game has ended, with its exit code. */
    void onExit(IntConsumer done) {
        onExit = done;
        if (!alive) {
            slt.Jobs.background("slt-fin-du-jeu", () -> done.accept(0));
        }
    }

    /** The cartridge save file of the emulator: every core of the phone gives its save to Cable Club (rom.srm). */
    static String saveName(boolean ds) {
        return "rom.srm";
    }

    /** The cores are native libraries of the app. */
    static Path coresDir() {
        return Path.of(Android.context().getApplicationInfo().nativeLibraryDir);
    }
}
