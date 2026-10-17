package lol.rowe.blocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Fires at local midnight to send the day that just ended. */
public final class NotificationDumpReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !NotificationDump.ACTION_FLUSH.equals(intent.getAction())) {
            return;
        }
        NotificationDump.flushIfDue(context);
        NotificationDump.schedule(context);
    }
}
