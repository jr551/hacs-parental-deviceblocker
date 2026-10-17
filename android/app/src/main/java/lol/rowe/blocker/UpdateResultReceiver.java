package lol.rowe.blocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.widget.Toast;

public final class UpdateResultReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            // The system installer prompt is open, so the uninstall guard stays stood down.
            UpdateGate.installInProgress = true;
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(confirm);
            }
            return;
        }
        UpdateGate.installInProgress = false;
        if (status != PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Phone update did not install.", Toast.LENGTH_LONG).show();
        }
    }
}
