package lol.rowe.blocker;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The day's posted notifications, buffered on disk and uploaded to Home
 * Assistant once a day.
 *
 * <p>The buffer is one day wide. {@link #record} starts a new day when the
 * local date changes and {@link #flushIfDue} posts the day that has ended, so
 * a phone that was off at midnight still sends the older day when it comes
 * back. Both are called from the main thread, so the buffer is guarded by
 * {@link #LOCK} and every disk write and upload runs on {@link #WRITER}.
 */
final class NotificationDump {
    static final String ACTION_FLUSH = "lol.rowe.blocker.action.NOTIFICATION_DUMP";
    private static final String FILE_NAME = "notifications.json";
    private static final int REQUEST = 0x4E4F5449;
    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    /** The day being recorded; null until the file is read, and after a flush. */
    private static NotificationLog.Day buffer;
    private static boolean loaded;

    private NotificationDump() {
    }

    /** Append one posted notification to today's buffer. */
    static void record(Context context, NotificationLog.Item item) {
        if (context == null || item == null || context.getPackageName().equals(item.packageName)) {
            return;
        }
        NotificationLog.Day stale = null;
        String snapshot;
        synchronized (LOCK) {
            NotificationLog.Day current = buffer(context);
            String today = today();
            if (current != null && !today.equals(current.date)) {
                // The day ended while this phone was off, or the midnight alarm
                // was missed. Offer the old day to the uploader before it is
                // replaced, so a late flush still has something to send.
                stale = current;
                current = null;
            }
            if (current == null) {
                current = new NotificationLog.Day(today, Collections.emptyList(), 0, false);
            }
            buffer = NotificationLog.record(current, item);
            snapshot = NotificationLog.store(buffer);
        }
        writeLater(context, snapshot);
        if (stale != null) {
            uploadLater(context, stale);
        }
    }

    /** Upload the finished day, once the local date has moved on. */
    static void flushIfDue(Context context) {
        if (context == null) {
            return;
        }
        NotificationLog.Day day;
        synchronized (LOCK) {
            day = buffer(context);
        }
        if (day == null || !NotificationLog.flushDue(day.date, today())) {
            return;
        }
        uploadLater(context, day);
    }

    /** Arm the alarm that flushes the day just ended. */
    static void schedule(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) {
            return;
        }
        PendingIntent pending = pending(context);
        alarms.cancel(pending);
        long trigger = nextMidnightMillis(System.currentTimeMillis(), ZoneId.systemDefault());
        try {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
        } catch (SecurityException ignored) {
            // Android 12+ can refuse an exact alarm. An inexact one still fires
            // within the hour, and the listener's next connect flushes anyway.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pending);
        }
    }

    /** True when the parent has switched this app's notification listener on. */
    static boolean hasAccess(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null
                && enabled.contains(new ComponentName(context, NotificationDumpService.class)
                        .flattenToString());
    }

    private static void uploadLater(Context context, NotificationLog.Day day) {
        try {
            WRITER.execute(() -> upload(context, day));
        } catch (RuntimeException ignored) {
            // The executor is shut down; the next midnight retries.
        }
    }

    /** POST one day and, once Home Assistant has it, drop it. */
    private static void upload(Context context, NotificationLog.Day day) {
        AppConfig config = AppConfig.load(context);
        if (!config.isConfigured()) {
            return;
        }
        try {
            new HomeAssistantClient(config).postNotifications(NotificationLog.payload(day));
        } catch (Exception ignored) {
            // Keep the buffer: the next midnight, or the next listener connect,
            // tries again.
            return;
        }
        synchronized (LOCK) {
            if (buffer != null && day.date.equals(buffer.date)) {
                buffer = null;
                delete(context);
            }
        }
    }

    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, NotificationDumpReceiver.class).setAction(ACTION_FLUSH);
        return PendingIntent.getBroadcast(
                context,
                REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static long nextMidnightMillis(long now, ZoneId zone) {
        return Instant.ofEpochMilli(now)
                .atZone(zone)
                .toLocalDate()
                .plusDays(1)
                .atStartOfDay(zone)
                .toInstant()
                .toEpochMilli();
    }

    private static String today() {
        return LocalDate.now(ZoneId.systemDefault()).toString();
    }

    /** The buffer, read from disk on first use. Callers hold {@link #LOCK}. */
    private static NotificationLog.Day buffer(Context context) {
        if (!loaded) {
            loaded = true;
            buffer = NotificationLog.parse(read(file(context)));
        }
        return buffer;
    }

    private static String read(File file) {
        try {
            return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        } catch (IOException exception) {
            return null;
        }
    }

    private static void writeLater(Context context, String snapshot) {
        try {
            WRITER.execute(() -> write(context, snapshot));
        } catch (RuntimeException ignored) {
            // The executor is shut down; the next change rewrites the file.
        }
    }

    /**
     * Replace the buffer file. The bytes land in a temporary file first, so a
     * process death mid-write cannot leave an unreadable buffer behind.
     */
    private static void write(Context context, String snapshot) {
        File file = file(context);
        File temporary = new File(file.getParentFile(), file.getName() + ".tmp");
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(snapshot.getBytes(StandardCharsets.UTF_8));
            }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            // The buffer in memory is still correct; the next change retries.
        }
    }

    private static void delete(Context context) {
        try {
            Files.deleteIfExists(file(context).toPath());
        } catch (IOException ignored) {
            // A stale file is harmless: the next record overwrites it.
        }
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE_NAME);
    }
}
