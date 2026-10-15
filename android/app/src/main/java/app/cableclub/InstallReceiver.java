package app.cableclub;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import slt.Json;
import slt.Log;
import slt.client.ClientApp;

/** Answers of Android's installer to an update: the confirmation to show, or how it ended. */
public final class InstallReceiver extends BroadcastReceiver {

    @SuppressWarnings("deprecation")
    private static Intent legacyIntent(Intent intent) {
        return intent.getParcelableExtra(Intent.EXTRA_INTENT);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = android.os.Build.VERSION.SDK_INT >= 33 ? intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class)
                    : legacyIntent(intent);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        if (status != PackageInstaller.STATUS_SUCCESS) {
            String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Log.warn("Mise à jour non installée (" + status + ") : " + message);
            ClientApp app = CableClubApp.client();
            if (app != null) {
                app.notifyInterface("notice", Json.map("level", "warn", "local", true,
                        "text", status == PackageInstaller.STATUS_FAILURE_ABORTED ? "Mise à jour annulée."
                                : "La mise à jour n'a pas pu s'installer" + (message == null ? "." : " : " + message)));
            }
        }
    }
}
