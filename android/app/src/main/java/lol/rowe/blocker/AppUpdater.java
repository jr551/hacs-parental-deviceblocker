package lol.rowe.blocker;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the public GitHub release for a newer sideload APK and starts the
 * system installer. Android still asks the person holding the phone to confirm.
 */
final class AppUpdater {
    static final String RELEASES_URL =
            "https://api.github.com/repos/jr551/hacs-parental-deviceblocker/releases/latest";
    private static final Pattern APK_VERSION =
            Pattern.compile("ParentalDeviceBlocker-(\\d+(?:\\.\\d+)*)\\.apk");
    private static final long MIN_INTERVAL_MS = ReportSchedule.HOURLY_REPORT_MS;
    private static final int MAX_APK_BYTES = 30 * 1024 * 1024;

    private AppUpdater() {
    }

    static void check(Context context) {
        try {
            long now = System.currentTimeMillis();
            long last = context.getSharedPreferences("rowe_blocker", Context.MODE_PRIVATE)
                    .getLong("update_checked_at", 0L);
            if (now - last < MIN_INTERVAL_MS) {
                return;
            }
            context.getSharedPreferences("rowe_blocker", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("update_checked_at", now)
                    .apply();
            String current = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0)
                    .versionName;
            String remoteUrl = newerApkUrl(current);
            if (remoteUrl == null) {
                return;
            }
            install(context, remoteUrl);
        } catch (Exception ignored) {
            // The next check retries. A failed update must not affect blocking.
        }
    }

    static String newerApkUrl(String currentVersion) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(RELEASES_URL).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "ParentalDeviceBlocker");
        try {
            if (connection.getResponseCode() != 200) {
                return null;
            }
            JSONObject release = new JSONObject(read(connection.getInputStream(), 1_000_000));
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) {
                return null;
            }
            String bestVersion = currentVersion;
            String bestUrl = null;
            for (int index = 0; index < assets.length(); index++) {
                JSONObject asset = assets.getJSONObject(index);
                String name = asset.optString("name", "");
                if (name.contains("debug")) {
                    continue;
                }
                Matcher matcher = APK_VERSION.matcher(name);
                if (!matcher.matches()) {
                    continue;
                }
                String version = matcher.group(1);
                if (UpdateGate.isNewer(bestVersion, version)) {
                    bestVersion = version;
                    bestUrl = asset.optString("browser_download_url", null);
                }
            }
            return bestUrl;
        } finally {
            connection.disconnect();
        }
    }

    private static void install(Context context, String apkUrl) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(apkUrl).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "ParentalDeviceBlocker");
        try {
            if (connection.getResponseCode() != 200) {
                return;
            }
            PackageInstaller installer = context.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(context.getPackageName());
            int sessionId = installer.createSession(params);
            try (PackageInstaller.Session session = installer.openSession(sessionId);
                 InputStream input = connection.getInputStream();
                 OutputStream output = session.openWrite("update", 0, -1)) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_APK_BYTES) {
                        session.abandon();
                        return;
                    }
                    output.write(buffer, 0, read);
                }
                session.fsync(output);
                UpdateGate.installInProgress = true;
                Intent callback = new Intent(context, UpdateResultReceiver.class);
                PendingIntent pending = PendingIntent.getBroadcast(
                        context,
                        sessionId,
                        callback,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
                session.commit(pending.getIntentSender());
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String read(InputStream input, int limit) throws Exception {
        byte[] buffer = new byte[4096];
        StringBuilder text = new StringBuilder();
        int read;
        while ((read = input.read(buffer)) != -1 && text.length() < limit) {
            text.append(new String(buffer, 0, read, java.nio.charset.StandardCharsets.UTF_8));
        }
        return text.toString();
    }
}
