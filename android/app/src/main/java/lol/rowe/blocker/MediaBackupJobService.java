package lol.rowe.blocker;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.net.Network;
import android.provider.MediaStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MediaBackupJobService extends JobService {
    private static final int BATCH_SIZE = 20;
    private ExecutorService executor;
    private volatile boolean stopped;

    @Override
    public boolean onStartJob(JobParameters parameters) {
        stopped = false;
        executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> runBackup(parameters));
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters parameters) {
        stopped = true;
        if (executor != null) {
            executor.shutdownNow();
        }
        return true;
    }

    private void runBackup(JobParameters parameters) {
        boolean retry = false;
        try {
            AppConfig appConfig = AppConfig.load(this);
            if (!appConfig.isConfigured()) {
                finish(parameters, false);
                return;
            }
            HomeAssistantClient client = new HomeAssistantClient(appConfig);
            HomeAssistantClient.MediaBackupConfig backupConfig =
                    client.getMediaBackupConfig();
            if (!backupConfig.enabled) {
                postStatus(client, "disabled", false, 0, "");
                MediaBackupScheduler.cancel(this);
                finish(parameters, false);
                return;
            }
            if (!MediaBackupScheduler.hasMediaPermission(this)) {
                postStatus(client, "permission_required",
                        MediaBackupScheduler.isInitialComplete(this),
                        0,
                        "Photo and video access must be granted by a parent.");
                finish(parameters, false);
                return;
            }

            try (MediaBackupDatabase database = new MediaBackupDatabase(this)) {
                if (MediaBackupScheduler.destinationChanged(
                        this, backupConfig.configurationId)) {
                    database.reset();
                }
                boolean wasInitialComplete = MediaBackupScheduler.isInitialComplete(this);
                Network wifiNetwork = MediaBackupScheduler.wifiNetwork(this);
                if (!BackupNetworkPolicy.mayUpload(
                        wasInitialComplete, wifiNetwork != null)) {
                    postStatus(client, "waiting_for_wifi", false, 0, "");
                    finish(parameters, true);
                    return;
                }
                ScanResult scan = pendingMedia(database, backupConfig.maxFileBytes);
                if (scan.pending.isEmpty()) {
                    MediaBackupScheduler.setInitialComplete(this, true);
                    postStatus(
                            client,
                            scan.skipped == 0 ? "complete" : "complete_with_skips",
                            true,
                            scan.skipped,
                            skippedMessage(scan.skipped));
                    if (BackupNetworkPolicy.shouldInstallPeriodicSchedule(wasInitialComplete)) {
                        MediaBackupScheduler.schedule(this);
                    }
                    finish(parameters, false);
                    return;
                }

                postStatus(client, "syncing", wasInitialComplete, scan.skipped,
                        skippedMessage(scan.skipped));
                for (MediaItem item : scan.pending) {
                    if (stopped) {
                        retry = true;
                        break;
                    }
                    HomeAssistantClient.PresignedUpload grant = client.requestMediaUpload(
                            item.relativePath, item.displayName, item.size);
                    upload(item, grant, wasInitialComplete ? null : wifiNetwork);
                    database.markUploaded(item.identity, item.size, item.modified);
                }

                boolean initialComplete = wasInitialComplete
                        || (scan.pendingCount <= scan.pending.size() && !stopped);
                if (initialComplete) {
                    MediaBackupScheduler.setInitialComplete(this, true);
                    postStatus(
                            client,
                            scan.skipped == 0 ? "complete" : "complete_with_skips",
                            true,
                            scan.skipped,
                            skippedMessage(scan.skipped));
                    if (BackupNetworkPolicy.shouldInstallPeriodicSchedule(wasInitialComplete)) {
                        MediaBackupScheduler.schedule(this);
                    }
                } else {
                    postStatus(client, "syncing", false, scan.skipped,
                            skippedMessage(scan.skipped));
                    retry = true;
                }
            }
        } catch (SecurityException exception) {
            try {
                AppConfig config = AppConfig.load(this);
                if (config.isConfigured()) {
                    postStatus(new HomeAssistantClient(config), "permission_required",
                            MediaBackupScheduler.isInitialComplete(this),
                            0,
                            "Photo and video access must be granted by a parent.");
                }
            } catch (Exception ignored) {
                // The parent-facing status will retry after connectivity returns.
            }
        } catch (Exception exception) {
            retry = true;
            try {
                AppConfig config = AppConfig.load(this);
                if (config.isConfigured()) {
                    postStatus(new HomeAssistantClient(config), "error",
                            MediaBackupScheduler.isInitialComplete(this),
                            0,
                            safeError(exception));
                }
            } catch (Exception ignored) {
                // A failed status report must not expose credentials or crash the job.
            }
        }
        finish(parameters, retry);
    }

    private ScanResult pendingMedia(
            MediaBackupDatabase database, long maxFileBytes) {
        List<MediaItem> pending = new ArrayList<>();
        int pendingCount = 0;
        int skipped = 0;
        Uri collection = MediaStore.Files.getContentUri("external");
        String[] projection = {
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.RELATIVE_PATH,
                MediaStore.Files.FileColumns.SIZE,
                MediaStore.Files.FileColumns.DATE_MODIFIED,
        };
        String selection = MediaStore.Files.FileColumns.MEDIA_TYPE + "=? OR "
                + MediaStore.Files.FileColumns.MEDIA_TYPE + "=?";
        String[] arguments = {
                Integer.toString(MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE),
                Integer.toString(MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO),
        };
        try (Cursor cursor = getContentResolver().query(
                collection,
                projection,
                selection,
                arguments,
                MediaStore.Files.FileColumns.DATE_MODIFIED + " ASC")) {
            if (cursor == null) {
                throw new IllegalStateException("Android media library is unavailable");
            }
            int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID);
            int nameColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Files.FileColumns.DISPLAY_NAME);
            int pathColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Files.FileColumns.RELATIVE_PATH);
            int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE);
            int modifiedColumn = cursor.getColumnIndexOrThrow(
                    MediaStore.Files.FileColumns.DATE_MODIFIED);
            while (cursor.moveToNext()) {
                long id = cursor.getLong(idColumn);
                long size = cursor.getLong(sizeColumn);
                long modified = cursor.getLong(modifiedColumn);
                if (size <= 0 || size > maxFileBytes) {
                    skipped++;
                    continue;
                }
                Uri uri = ContentUris.withAppendedId(collection, id);
                String identity = uri.toString();
                if (!database.isUploaded(identity, size, modified)) {
                    pendingCount++;
                    if (pending.size() < BATCH_SIZE) {
                        pending.add(new MediaItem(
                                uri,
                                identity,
                                cursor.getString(nameColumn),
                                cursor.getString(pathColumn),
                                size,
                                modified));
                    }
                }
            }
        }
        return new ScanResult(pending, pendingCount, skipped);
    }

    private void upload(
            MediaItem item,
            HomeAssistantClient.PresignedUpload grant,
            Network requiredNetwork) throws Exception {
        java.net.URL url = URI.create(grant.url).toURL();
        HttpURLConnection connection = (HttpURLConnection) (requiredNetwork == null
                ? url.openConnection()
                : requiredNetwork.openConnection(url));
        try {
            connection.setRequestMethod("PUT");
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(120_000);
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setFixedLengthStreamingMode(grant.contentLength);
            try (InputStream input = getContentResolver().openInputStream(item.uri);
                    OutputStream output = connection.getOutputStream()) {
                if (input == null) {
                    throw new IOException("Android could not open a media item");
                }
                byte[] buffer = new byte[128 * 1024];
                int read;
                while (!stopped && (read = input.read(buffer)) != -1) {
                    output.write(buffer, 0, read);
                }
                if (stopped) {
                    throw new IOException("Backup job was stopped");
                }
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new IOException("Object storage returned " + status);
            }
        } finally {
            connection.disconnect();
        }
    }

    private void postStatus(
            HomeAssistantClient client,
            String status,
            boolean initialComplete,
            long skipped,
            String error) throws Exception {
        long count;
        try (MediaBackupDatabase database = new MediaBackupDatabase(this)) {
            count = database.uploadedCount();
        }
        client.postMediaBackupStatus(
                status,
                count,
                skipped,
                initialComplete,
                status.startsWith("complete") ? Instant.now().toString() : "",
                error);
    }

    private static String skippedMessage(int skipped) {
        return skipped == 0
                ? ""
                : skipped + " media item(s) were empty or exceeded the configured size limit.";
    }

    private void finish(JobParameters parameters, boolean retry) {
        if (executor != null) {
            executor.shutdown();
        }
        jobFinished(parameters, retry);
    }

    private static String safeError(Exception exception) {
        String message = exception.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return "Media backup failed and will retry.";
        }
        return message.replaceAll("https?://\\S+", "the configured endpoint");
    }

    private static final class MediaItem {
        final Uri uri;
        final String identity;
        final String displayName;
        final String relativePath;
        final long size;
        final long modified;

        MediaItem(
                Uri uri,
                String identity,
                String displayName,
                String relativePath,
                long size,
                long modified) {
            this.uri = uri;
            this.identity = identity;
            this.displayName = displayName == null ? "media" : displayName;
            this.relativePath = relativePath == null ? "" : relativePath;
            this.size = size;
            this.modified = modified;
        }
    }

    private static final class ScanResult {
        final List<MediaItem> pending;
        final int pendingCount;
        final int skipped;

        ScanResult(List<MediaItem> pending, int pendingCount, int skipped) {
            this.pending = pending;
            this.pendingCount = pendingCount;
            this.skipped = skipped;
        }
    }
}
