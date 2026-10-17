package lol.rowe.blocker;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The launchable apps on this phone, reported to Home Assistant so a parent can
 * pick an allow-list. The list is only sent when it changes, and the last
 * fingerprint is remembered across restarts so a reboot does not resend it.
 *
 * <p>Package visibility comes from the {@code MAIN}/{@code LAUNCHER} intent
 * query in the manifest, not from {@code QUERY_ALL_PACKAGES}: only apps with a
 * launcher activity are visible, which is exactly the set reported here.
 */
final class InstalledApps {
    /** More than this is noise; the parent's allow-list never needs it. */
    static final int MAX_APPS = 200;
    private static final String PREFS = "installed_apps_state";
    private static final String FINGERPRINT = "fingerprint";

    static final class App {
        final String packageName;
        final String label;

        App(String packageName, String label) {
            this.packageName = packageName == null ? "" : packageName;
            this.label = label == null ? "" : label;
        }
    }

    private InstalledApps() {
    }

    /** Launchable apps only, deduplicated by package and capped. */
    static List<App> launchable(PackageManager packages) {
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = packages.queryIntentActivities(launcher, 0);
        if (resolved == null) {
            return Collections.emptyList();
        }
        Set<String> seen = new HashSet<>();
        List<App> apps = new ArrayList<>();
        for (ResolveInfo info : resolved) {
            if (info == null || info.activityInfo == null) {
                continue;
            }
            String packageName = info.activityInfo.packageName;
            if (packageName == null || packageName.isEmpty() || !seen.add(packageName)) {
                continue;
            }
            CharSequence label = info.loadLabel(packages);
            apps.add(new App(packageName, label == null ? "" : label.toString()));
        }
        Collections.sort(apps, (left, right) -> left.label.compareToIgnoreCase(right.label));
        if (apps.size() > MAX_APPS) {
            return new ArrayList<>(apps.subList(0, MAX_APPS));
        }
        return apps;
    }

    /** Deterministic identity of a reported set, independent of query order. */
    static String fingerprint(List<App> apps) {
        List<String> lines = new ArrayList<>();
        for (App app : apps) {
            lines.add(app.packageName + "\u0000" + app.label);
        }
        Collections.sort(lines);
        return String.join("\n", lines);
    }

    /** True when this set differs from the last one sent. */
    static boolean shouldSend(String lastSentFingerprint, String currentFingerprint) {
        String previous = lastSentFingerprint == null ? "" : lastSentFingerprint;
        return !previous.equals(currentFingerprint == null ? "" : currentFingerprint);
    }

    static String lastFingerprint(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(FINGERPRINT, "");
    }

    static void rememberFingerprint(Context context, String fingerprint) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(FINGERPRINT, fingerprint)
                .apply();
    }
}
