package lol.rowe.blocker;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class WakeAlarmReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null || !WakeAlarm.ACTION_RING.equals(intent.getAction())) {
            return;
        }
        Intent ringing = new Intent(context, WakeAlarmActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        context.startActivity(ringing);
        WakeAlarm.schedule(context);
    }
}
