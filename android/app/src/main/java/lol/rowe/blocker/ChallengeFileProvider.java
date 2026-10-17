package lol.rowe.blocker;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

/**
 * Serves the challenge photo files in the app's cache directory to the system
 * camera, so {@code ACTION_IMAGE_CAPTURE} can write the shot without this app
 * holding the CAMERA permission. Only files directly inside
 * {@code cache/challenge} are served.
 *
 * <p>This is a small FileProvider of our own: the app deliberately has no
 * AndroidX dependency, and this is all the camera needs.
 */
public final class ChallengeFileProvider extends ContentProvider {
    /** The authority suffix; the manifest declares {@code ${applicationId}} plus this. */
    static final String AUTHORITY_SUFFIX = ".challengefiles";
    private static final String DIRECTORY = "challenge";

    /** The content URI the camera writes a new photo to. */
    static Uri uriFor(Context context, File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(file.getName())
                .build();
    }

    /** The cache directory the camera writes into. */
    static File directory(Context context) {
        return new File(context.getCacheDir(), DIRECTORY);
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public String getType(Uri uri) {
        return "image/jpeg";
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        File file = fileFor(uri);
        if (file == null) {
            throw new FileNotFoundException("Unknown challenge photo");
        }
        int flags;
        if ("r".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_READ_ONLY;
        } else if ("w".equals(mode) || "wt".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else if ("wa".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_WRITE_ONLY
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_APPEND;
        } else if ("rw".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE;
        } else if ("rwt".equals(mode)) {
            flags = ParcelFileDescriptor.MODE_READ_WRITE
                    | ParcelFileDescriptor.MODE_CREATE
                    | ParcelFileDescriptor.MODE_TRUNCATE;
        } else {
            throw new FileNotFoundException("Bad mode for " + uri + ": " + mode);
        }
        return ParcelFileDescriptor.open(file, flags);
    }

    @Override
    public Cursor query(
            Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = fileFor(uri);
        if (file == null) {
            return null;
        }
        String[] columns = projection == null
                ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
                : projection;
        MatrixCursor cursor = new MatrixCursor(columns, 1);
        MatrixCursor.RowBuilder row = cursor.newRow();
        for (String column : columns) {
            if (OpenableColumns.DISPLAY_NAME.equals(column)) {
                row.add(file.getName());
            } else if (OpenableColumns.SIZE.equals(column)) {
                row.add(file.length());
            } else {
                row.add(null);
            }
        }
        return cursor;
    }

    @Override
    public int delete(Uri uri, String selection, String[] selectionArgs) {
        File file = fileFor(uri);
        return file != null && file.delete() ? 1 : 0;
    }

    @Override
    public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Challenge photos are written by the camera");
    }

    @Override
    public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Challenge photos are written by the camera");
    }

    /** The cache file this URI names, or null when it escapes the photo directory. */
    private File fileFor(Uri uri) {
        if (uri == null || uri.getPathSegments().size() != 1) {
            return null;
        }
        String name = uri.getPathSegments().get(0);
        if (name.isEmpty() || name.contains("/") || name.contains("..")) {
            return null;
        }
        Context context = getContext();
        return context == null ? null : new File(directory(context), name);
    }
}
