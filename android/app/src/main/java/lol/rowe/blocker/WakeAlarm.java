package lol.rowe.blocker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;

import java.time.ZoneId;
import java.util.Locale;

final class WakeAlarm {
    static final String ACTION_RING = "lol.rowe.blocker.action.WAKE_ALARM";
    private static final String PREFS = "wake_alarm";
    private static final int REQUEST = 0x57414B;

    private WakeAlarm() {
    }

    static boolean isEnabled(Context context) {
        return preferences(context).getBoolean("enabled", false);
    }

    static int hour(Context context) {
        return preferences(context).getInt("hour", 7);
    }

    static int minute(Context context) {
        return preferences(context).getInt("minute", 0);
    }

    static int dayBits(Context context) {
        return preferences(context).getInt("days", WakeSchedule.WEEKDAYS);
    }

    static int volumePercent(Context context) {
        return preferences(context).getInt("volume", 100);
    }

    static String statusText(Context context) {
        if (!isEnabled(context)) {
            if (!preferences(context).getBoolean("has_server", false)) {
                return "Wake alarm is set in Home Assistant. Nothing has been received yet.";
            }
            return "Wake alarm is off. Parents change it in Home Assistant.";
        }
        return String.format(
                Locale.US,
                "Wake alarm %02d:%02d on %s, volume %d of 100. Parents change it in Home Assistant.",
                hour(context),
                minute(context),
                WakeSchedule.dayList(dayBits(context)),
                volumePercent(context));
    }

    /**
     * Store the alarm from this phone's latest Home Assistant state poll.
     * A null update, including a failed poll, leaves the last saved alarm scheduled.
     */
    static void applyServer(Context context, WakeSchedule.Update update) {
        if (context == null || update == null) {
            return;
        }
        SharedPreferences prefs = preferences(context);
        boolean unchanged = prefs.getBoolean("has_server", false)
                && prefs.getBoolean("enabled", false) == update.enabled
                && prefs.getInt("hour", -1) == update.hour
                && prefs.getInt("minute", -1) == update.minute
                && prefs.getInt("days", -1) == update.days
                && prefs.getInt("volume", -1) == update.volume;
        if (unchanged) {
            return;
        }
        prefs.edit()
                .putBoolean("has_server", true)
                .putBoolean("enabled", update.enabled)
                .putInt("hour", update.hour)
                .putInt("minute", update.minute)
                .putInt("days", update.days)
                .putInt("volume", update.volume)
                .commit();
        schedule(context);
    }

    static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return;
        }
        PendingIntent pending = pending(context);
        alarms.cancel(pending);
        if (!isEnabled(context)) {
            return;
        }
        Long trigger = WakeSchedule.nextTriggerMillis(
                System.currentTimeMillis(),
                hour(context),
                minute(context),
                dayBits(context),
                ZoneId.systemDefault());
        if (trigger == null) {
            return;
        }
        AlarmManager.AlarmClockInfo info = new AlarmManager.AlarmClockInfo(trigger, pending);
        try {
            alarms.setAlarmClock(info, pending);
        } catch (SecurityException ignored) {
            // Exact alarms need the parent to allow them in system settings.
        }
    }

    static boolean canScheduleExact(Context context) {
        if (Build.VERSION.SDK_INT < 31) {
            return true;
        }
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        return alarms != null && alarms.canScheduleExactAlarms();
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, WakeAlarmReceiver.class).setAction(ACTION_RING);
        return PendingIntent.getBroadcast(
                context,
                REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
