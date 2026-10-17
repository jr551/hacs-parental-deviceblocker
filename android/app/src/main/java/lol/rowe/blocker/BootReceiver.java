package lol.rowe.blocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Restores the wake alarm and the enforcement watchdog after a reboot or an app
 * update. Nothing else has to run at boot: the accessibility service is started
 * by the system when it is enabled.
 */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            return;
        }
        WakeAlarm.schedule(context);
        if (AppConfig.load(context).isConfigured()) {
            EnforcementWatchdogJobService.schedule(context);
            EnforcementWatchdogService.start(context);
        }
    }
}
