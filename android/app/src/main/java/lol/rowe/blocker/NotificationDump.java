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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The day's posted notifications, buffered on disk and uploaded to Home
 * Assistant once a day.
 *
 * <p>The buffer is one day wide. {@link #record} starts a new day when the
 * local date changes and {@link #flushIfDue} posts the day that has ended, so
 * a phone that was off at midnight still sends the older day when it comes
 * back. A parent can also ask for the buffer now: {@link #answerNow} posts it
 * tagged with the request id, and records that id only once Home Assistant
 * has it. Callers include the listener, the alarm and the policy poll, so the
 * buffer is guarded by {@link #LOCK} wherever it is read or written, and every
 * disk write and upload runs on {@link #WRITER}.
 */
final class NotificationDump {
    static final String ACTION_FLUSH = "lol.rowe.blocker.action.NOTIFICATION_DUMP";
    private static final String FILE_NAME = "notifications.json";
    private static final String PREFS = "rowe_blocker";
    private static final String ANSWERED_ID = "notification_request_answered";
    private static final int REQUEST = 0x4E4F5449;
    private static final Object LOCK = new Object();
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor();
    /** One on-demand answer at a time; a slow upload must not stack more. */
    private static final AtomicBoolean REQUEST_RUNNING = new AtomicBoolean();
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

    /**
     * True when this request id is one this phone has not answered yet.
     * Mirrors {@link OnDemandLocation#shouldFetch}: a blank id is nothing to
     * answer, and an id already answered is just a poll repeating itself.
     */
    static boolean shouldAnswer(String requestId, String lastRequestId) {
        String id = requestId == null ? "" : requestId.trim();
        return !id.isEmpty() && !id.equals(lastRequestId == null ? "" : lastRequestId.trim());
    }

    /** True when this request is still waiting for an answer from this phone. */
    static boolean requestPending(Context context, String requestId) {
        return shouldAnswer(requestId, lastRequestId(context));
    }

    /** The request id this phone last answered, or "". */
    static String lastRequestId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ANSWERED_ID, "");
    }

    static void rememberRequestId(Context context, String requestId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ANSWERED_ID, requestId == null ? "" : requestId.trim())
                .apply();
    }

    /**
     * Answer one of a parent's on-demand requests: post the buffered day now,
     * tagged with the request id. The work runs on {@link #WRITER}, so it
     * never holds up the policy poll. False means an answer is already in
     * flight; the request stays pending for the next poll rather than
     * stacking uploads behind a slow server.
     */
    static boolean answerNow(Context context, String requestId) {
        if (context == null || requestId == null || requestId.trim().isEmpty()) {
            return false;
        }
        if (!REQUEST_RUNNING.compareAndSet(false, true)) {
            return false;
        }
        String id = requestId.trim();
        try {
            WRITER.execute(() -> {
                try {
                    answer(context, id);
                } finally {
                    REQUEST_RUNNING.set(false);
                }
            });
            return true;
        } catch (RuntimeException ignored) {
            // The executor is shut down; the next poll retries.
            REQUEST_RUNNING.set(false);
            return false;
        }
    }

    private static void answer(Context context, String requestId) {
        NotificationLog.Day day;
        synchronized (LOCK) {
            day = buffer(context);
        }
        if (day == null) {
            // Nothing has been captured yet. An empty day still answers the
            // request, so a parent's button is not left hanging on silence.
            day = new NotificationLog.Day(today(), Collections.emptyList(), 0, false);
        }
        // The buffer stays: an answer is a snapshot of the day so far, and the
        // midnight flush replaces it with the whole day. Dropping it here would
        // leave Home Assistant showing only the afternoon.
        if (uploadNow(context, day, requestId, false)) {
            // Only a delivered answer is spent: a failed upload leaves the
            // request pending, and the next poll tries again.
            rememberRequestId(context, requestId);
        }
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
            WRITER.execute(() -> uploadNow(context, day, null, true));
        } catch (RuntimeException ignored) {
            // The executor is shut down; the next midnight retries.
        }
    }

    /**
     * POST one day and, when it was the finished day, drop it.
     *
     * @param requestId the on-demand request this upload answers, or null for
     *     the midnight flush
     * @param consume true for the finished day, which is replaced by the next
     *     day after a successful upload; false for an on-demand answer, whose
     *     day keeps growing until midnight
     * @return true when Home Assistant took the upload
     */
    private static boolean uploadNow(
            Context context, NotificationLog.Day day, String requestId, boolean consume) {
        AppConfig config = AppConfig.load(context);
        if (!config.isConfigured()) {
            return false;
        }
        try {
            new HomeAssistantClient(config)
                    .postNotifications(NotificationLog.payload(day), requestId);
        } catch (Exception ignored) {
            // Keep the buffer: the next midnight, or the next listener connect,
            // tries again.
            return false;
        }
        if (consume) {
            synchronized (LOCK) {
                if (buffer != null && day.date.equals(buffer.date)) {
                    buffer = null;
                    delete(context);
                }
            }
        }
        return true;
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
