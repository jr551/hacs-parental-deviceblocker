package lol.rowe.blocker;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Keeps a process alive while Accessibility is the only enforcement channel.
 * This is not Device Owner protection: a child can still uninstall the app or
 * turn Accessibility off. The service exists so that disablement is reported
 * and Samsung is less likely to kill the process silently.
 */
public final class EnforcementWatchdogService extends Service {
    private static final int NOTIFICATION_ID = 7042;
    /**
     * The neutral channel. Android keeps an existing channel's name and
     * importance once it is created, so the old, revealing channel is deleted
     * and this one is created under a new id.
     */
    private static final String CHANNEL_ID = "device_services";
    private static final String LEGACY_CHANNEL_ID = "enforcement_watchdog";
    /**
     * A freshly started watchdog proves liveness within a minute instead of
     * staying silent for a full hour. The hourly cadence is unchanged, so this
     * adds no wakeup source of its own.
     */
    private static final long INITIAL_CHECK_IN_DELAY_MS = 60_000L;
    private ScheduledExecutorService executor;

    static void start(Context context) {
        Intent intent = new Intent(context, EnforcementWatchdogService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
        } catch (RuntimeException ignored) {
            // Android 12+ can refuse a background start. Boot, the setup
            // screen, and the periodic job remain the other entry points.
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        executor = Executors.newSingleThreadScheduledExecutor();
        executor.scheduleWithFixedDelay(
                this::checkInSafely,
                INITIAL_CHECK_IN_DELAY_MS,
                ReportSchedule.HOURLY_REPORT_MS,
                TimeUnit.MILLISECONDS);
    }

    /**
     * A scheduled task that throws is cancelled permanently, so the check-in
     * body must swallow every Throwable — including Errors — or one bad pass
     * would silently stop the heartbeat for the life of the process.
     */
    private void checkInSafely() {
        try {
            EnforcementReporter.checkIn(this);
        } catch (Throwable ignored) {
            // The next scheduled pass retries.
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * The foreground notification Android requires. It is deliberately
     * anonymous: a child who opens the shade must not learn from it that the
     * phone is supervised, and a parent finds the app by its launcher label.
     */
    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.deleteNotificationChannel(LEGACY_CHANNEL_ID);
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "System services",
                        NotificationManager.IMPORTANCE_MIN);
                channel.setDescription("Keeps this device's background services running.");
                channel.setShowBadge(false);
                channel.setSound(null, null);
                channel.enableVibration(false);
                channel.setLockscreenVisibility(Notification.VISIBILITY_SECRET);
                manager.createNotificationChannel(channel);
            }
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_menu_manage)
                .setContentTitle("Android Settings")
                .setContentText("Keeping this device up to date.")
                .setOngoing(true)
                .setSound(null)
                .setVibrate(null)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .build();
    }
}
