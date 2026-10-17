package lol.rowe.blocker;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks the public GitHub release for a newer sideload APK, verifies it against the
 * release's own checksum and this app's signing key, and keeps it until the phone hands
 * it to the system installer. Android still asks the person holding the phone to confirm.
 */
final class AppUpdater {
    static final String RELEASES_URL =
            "https://api.github.com/repos/jr551/hacs-parental-deviceblocker/releases/latest";
    /** Set by the accessibility service: a challenge lock is up, so no update may start. */
    static volatile boolean challengeActive;

    private static final String TAG = "AppUpdater";
    private static final String PREFS = "rowe_blocker";
    private static final String CHECKED_AT = "update_checked_at";
    private static final String PENDING_VERSION = "update_pending_version";
    private static final String PENDING_APK = "update_pending_apk";
    private static final String UPDATE_DIR = "update";
    private static final String PARTIAL_NAME = "update.apk.part";
    /**
     * The file the verified update is kept in. The name is fixed on purpose:
     * a release asset name is text from outside this app, and no part of it
     * may reach a file path. The version that was downloaded lives in
     * {@link #PENDING_VERSION} and on the update screen.
     */
    private static final String APK_NAME = "ParentalDeviceBlocker.apk";
    private static final Pattern APK_VERSION =
            Pattern.compile("ParentalDeviceBlocker-(\\d+(?:\\.\\d+)*)\\.apk");
    /** A release version: digits and dots only, e.g. "0.5.8". */
    private static final Pattern VERSION = Pattern.compile("\\d+(?:\\.\\d+)*");
    private static final long MIN_INTERVAL_MS = ReportSchedule.HOURLY_REPORT_MS;
    private static final int MAX_APK_BYTES = 30 * 1024 * 1024;

    private AppUpdater() {
    }

    /** A downloaded, verified update waiting for the child to install it. */
    static final class Pending {
        final String version;
        final File apk;

        private Pending(String version, File apk) {
            this.version = version;
            this.apk = apk;
        }
    }

    /**
     * True when a release version is digits and dots only, e.g. "0.5.8". A
     * version that is anything else is refused, so no release-supplied text
     * can reach a file path, a comparison or the update screen.
     */
    static boolean isValidVersion(String version) {
        return version != null && VERSION.matcher(version).matches();
    }

    /**
     * The version in a release asset name, or "" when the name is not one of
     * ours or carries a version that is not digits and dots. A release whose
     * asset name does not parse is rejected rather than repaired.
     */
    static String versionFromAssetName(String name) {
        if (name == null) {
            return "";
        }
        Matcher matcher = APK_VERSION.matcher(name);
        if (!matcher.matches()) {
            return "";
        }
        String version = matcher.group(1);
        return isValidVersion(version) ? version : "";
    }

    /** The verified update waiting to be installed, or null. */
    static Pending pending(Context context) {
        try {
            SharedPreferences preferences = preferences(context);
            String version = preferences.getString(PENDING_VERSION, null);
            String path = preferences.getString(PENDING_APK, null);
            if (version == null || path == null) {
                return null;
            }
            File apk = new File(path);
            if (!apk.isFile() || apk.length() == 0L
                    || !UpdateGate.isNewer(runningVersion(context), version)) {
                clearPending(preferences, apk);
                return null;
            }
            return new Pending(version, apk);
        } catch (Exception exception) {
            Log.w(TAG, "could not read the pending update", exception);
            return null;
        }
    }

    /** Check the release and download+verify a newer APK. Safe to call often (throttled to 1/hour). */
    static void check(Context context) {
        try {
            if (challengeActive) {
                return;
            }
            SharedPreferences preferences = preferences(context);
            long now = System.currentTimeMillis();
            if (now - preferences.getLong(CHECKED_AT, 0L) < MIN_INTERVAL_MS) {
                return;
            }
            preferences.edit().putLong(CHECKED_AT, now).apply();
            Release release = newerRelease(runningVersion(context));
            if (release == null) {
                return;
            }
            downloadAndVerify(context, release);
        } catch (Exception exception) {
            // The next check retries. A failed update must not affect blocking.
            Log.w(TAG, "update check failed", exception);
        }
    }

    /** Hand the pending APK to the system installer. */
    static void install(Context context) {
        Pending pending = pending(context);
        if (pending == null) {
            return;
        }
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        int sessionId;
        try {
            sessionId = installer.createSession(params);
        } catch (Exception exception) {
            Log.w(TAG, "could not start an install session", exception);
            return;
        }
        boolean committed = false;
        try (PackageInstaller.Session session = installer.openSession(sessionId);
             InputStream input = new FileInputStream(pending.apk);
             OutputStream output = session.openWrite("update", 0, pending.apk.length())) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            session.fsync(output);
            UpdateGate.installInProgress = true;
            Intent callback = new Intent(context, UpdateResultReceiver.class);
            PendingIntent result = PendingIntent.getBroadcast(
                    context,
                    sessionId,
                    callback,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
            try {
                session.commit(result.getIntentSender());
                committed = true;
            } catch (Exception exception) {
                UpdateGate.installInProgress = false;
                throw exception;
            }
        } catch (Exception exception) {
            Log.w(TAG, "could not hand the update to the installer", exception);
        } finally {
            if (!committed) {
                try {
                    installer.abandonSession(sessionId);
                } catch (Exception ignored) {
                    // The session was already closed or committed.
                }
            }
        }
    }

    /** The newest release APK that is newer than the running version, with its checksum. */
    private static Release newerRelease(String currentVersion) throws Exception {
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
            String bestName = null;
            String bestUrl = null;
            for (int index = 0; index < assets.length(); index++) {
                JSONObject asset = assets.getJSONObject(index);
                String name = asset.optString("name", "");
                if (name.contains("debug")) {
                    continue;
                }
                String version = versionFromAssetName(name);
                if (version.isEmpty()) {
                    // Not one of our APKs, or a version that is not digits and
                    // dots: the release is refused, never repaired.
                    continue;
                }
                if (UpdateGate.isNewer(bestVersion, version)) {
                    bestVersion = version;
                    bestName = name;
                    bestUrl = asset.optString("browser_download_url", null);
                }
            }
            if (bestName == null || bestUrl == null) {
                return null;
            }
            String checksumUrl = assetUrl(assets, bestName + ".sha256");
            if (checksumUrl == null) {
                // Without the release's own checksum the download cannot be verified.
                return null;
            }
            return new Release(bestVersion, bestUrl, checksumUrl);
        } finally {
            connection.disconnect();
        }
    }

    private static String assetUrl(JSONArray assets, String name) throws Exception {
        for (int index = 0; index < assets.length(); index++) {
            JSONObject asset = assets.getJSONObject(index);
            if (name.equals(asset.optString("name", ""))) {
                return asset.optString("browser_download_url", null);
            }
        }
        return null;
    }

    private static void downloadAndVerify(Context context, Release release) throws Exception {
        if (!isValidVersion(release.version)) {
            Log.w(TAG, "refusing an update whose version is not digits and dots");
            return;
        }
        File directory = new File(context.getFilesDir(), UPDATE_DIR);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            Log.w(TAG, "could not create " + directory);
            return;
        }
        File partial = new File(directory, PARTIAL_NAME);
        File apk = new File(directory, APK_NAME);
        try {
            download(release.apkUrl, partial);
            String expected = fetchText(release.checksumUrl, 4096);
            if (!UpdateGate.checksumMatches(expected, UpdateGate.sha256Hex(partial))) {
                Log.w(TAG, "the downloaded update did not match its published checksum");
                return;
            }
            if (!signatureMatches(context, partial)) {
                Log.w(TAG, "the downloaded update is not signed with this app's key");
                return;
            }
            if (!partial.renameTo(apk)) {
                Log.w(TAG, "could not move the verified update into place");
                return;
            }
            recordPending(context, release.version, apk);
        } finally {
            if (partial.exists() && !partial.delete()) {
                Log.w(TAG, "could not delete " + partial);
            }
        }
    }

    private static void recordPending(Context context, String version, File apk) {
        SharedPreferences preferences = preferences(context);
        String previous = preferences.getString(PENDING_APK, null);
        preferences.edit()
                .putString(PENDING_VERSION, version)
                .putString(PENDING_APK, apk.getAbsolutePath())
                .apply();
        if (previous != null && !previous.equals(apk.getAbsolutePath())) {
            File superseded = new File(previous);
            if (superseded.exists() && !superseded.delete()) {
                Log.w(TAG, "could not delete the superseded update " + superseded);
            }
        }
    }

    private static void clearPending(SharedPreferences preferences, File apk) {
        preferences.edit().remove(PENDING_VERSION).remove(PENDING_APK).apply();
        if (apk.exists() && !apk.delete()) {
            Log.w(TAG, "could not delete " + apk);
        }
    }

    private static void download(String url, File target) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(60000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "ParentalDeviceBlocker");
        try {
            int code = connection.getResponseCode();
            if (code != 200) {
                throw new IOException("HTTP " + code + " for " + url);
            }
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int total = 0;
                int read;
                while ((read = input.read(buffer)) != -1) {
                    total += read;
                    if (total > MAX_APK_BYTES) {
                        throw new IOException("the update APK is over " + MAX_APK_BYTES + " bytes");
                    }
                    output.write(buffer, 0, read);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static String fetchText(String url, int limit) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(20000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "ParentalDeviceBlocker");
        try {
            if (connection.getResponseCode() != 200) {
                return null;
            }
            return read(connection.getInputStream(), limit);
        } finally {
            connection.disconnect();
        }
    }

    /** True when the downloaded APK carries the same signing certificate as the running app. */
    private static boolean signatureMatches(Context context, File apk) {
        PackageManager manager = context.getPackageManager();
        PackageInfo archive = manager.getPackageArchiveInfo(
                apk.getAbsolutePath(), PackageManager.GET_SIGNING_CERTIFICATES);
        PackageInfo installed;
        try {
            installed = manager.getPackageInfo(
                    context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
        } catch (PackageManager.NameNotFoundException exception) {
            Log.w(TAG, "could not read this app's signing certificate", exception);
            return false;
        }
        if (archive == null || archive.signingInfo == null || installed.signingInfo == null) {
            return false;
        }
        Signature[] archiveSigners = archive.signingInfo.getApkContentsSigners();
        Signature[] installedSigners = installed.signingInfo.getApkContentsSigners();
        if (archiveSigners == null || installedSigners == null
                || archiveSigners.length == 0 || installedSigners.length == 0
                || archiveSigners.length != installedSigners.length) {
            return false;
        }
        for (int index = 0; index < archiveSigners.length; index++) {
            if (!Arrays.equals(archiveSigners[index].toByteArray(),
                    installedSigners[index].toByteArray())) {
                return false;
            }
        }
        return true;
    }

    private static String runningVersion(Context context) throws Exception {
        String version = context.getPackageManager()
                .getPackageInfo(context.getPackageName(), 0)
                .versionName;
        return version == null ? "" : version;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
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

    /** A release APK and the checksum asset that has to match it. */
    private static final class Release {
        final String version;
        final String apkUrl;
        final String checksumUrl;

        Release(String version, String apkUrl, String checksumUrl) {
            this.version = version;
            this.apkUrl = apkUrl;
            this.checksumUrl = checksumUrl;
        }
    }
}
