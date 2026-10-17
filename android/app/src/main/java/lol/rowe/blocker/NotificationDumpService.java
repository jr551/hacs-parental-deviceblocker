package lol.rowe.blocker;

import android.app.Notification;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import java.time.Instant;

/**
 * Records every notification this phone posts, so a parent can see what a
 * child was sent. The day's buffer is uploaded by {@link NotificationDump}.
 *
 * <p>The parent switches this on in system settings; {@link
 * NotificationDump#hasAccess} reports whether it is on.
 */
public final class NotificationDumpService extends NotificationListenerService {
    @Override
    public void onNotificationPosted(StatusBarNotification posted) {
        if (posted == null) {
            return;
        }
        Notification notification = posted.getNotification();
        if (notification == null || NotificationLog.isNoise(notification.flags)) {
            return;
        }
        String packageName = posted.getPackageName();
        if (packageName == null || getPackageName().equals(packageName)) {
            return;
        }
        Bundle extras = notification.extras;
        CharSequence title = extras == null ? null : extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence text = extras == null ? null : extras.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null && extras != null) {
            text = extras.getCharSequence(Notification.EXTRA_TEXT);
        }
        NotificationDump.record(this, NotificationLog.item(
                packageName,
                label(packageName),
                string(title),
                string(text),
                Instant.ofEpochMilli(posted.getPostTime()).toString()));
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        NotificationDump.schedule(this);
        NotificationDump.flushIfDue(this);
    }

    /** The app's name as the launcher shows it, or the package when it has none. */
    private String label(String packageName) {
        PackageManager packages = getPackageManager();
        try {
            ApplicationInfo info = packages.getApplicationInfo(packageName, 0);
            return string(packages.getApplicationLabel(info));
        } catch (PackageManager.NameNotFoundException exception) {
            return packageName;
        }
    }

    private static String string(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
