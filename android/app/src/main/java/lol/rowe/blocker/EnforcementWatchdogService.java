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
    private static final String CHANNEL_ID = "enforcement_watchdog";
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
                () -> EnforcementReporter.reportIfAccessibilityDisabled(this),
                ReportSchedule.HOURLY_REPORT_MS,
                ReportSchedule.HOURLY_REPORT_MS,
                TimeUnit.MILLISECONDS);
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

    private Notification buildNotification() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Parental enforcement",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Keeps parental enforcement running and reports if Accessibility is turned off.");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        return builder
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle("Parental Device Blocker")
                .setContentText("Parental enforcement is running.")
                .setOngoing(true)
                .build();
    }
}
