package lol.rowe.blocker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

final class MediaBackupDatabase extends SQLiteOpenHelper {
    private static final String DATABASE = "media_backup.db";
    private static final int VERSION = 1;

    MediaBackupDatabase(Context context) {
        super(context, DATABASE, null, VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase database) {
        database.execSQL(
                "CREATE TABLE uploaded ("
                        + "identity TEXT PRIMARY KEY, "
                        + "size INTEGER NOT NULL, "
                        + "modified INTEGER NOT NULL, "
                        + "backed_up_at INTEGER NOT NULL)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase database, int oldVersion, int newVersion) {
        database.execSQL("DROP TABLE IF EXISTS uploaded");
        onCreate(database);
    }

    boolean isUploaded(String identity, long size, long modified) {
        try (Cursor cursor = getReadableDatabase().query(
                "uploaded",
                new String[] {"identity"},
                "identity=? AND size=? AND modified=?",
                new String[] {identity, Long.toString(size), Long.toString(modified)},
                null,
                null,
                null,
                "1")) {
            return cursor.moveToFirst();
        }
    }

    void markUploaded(String identity, long size, long modified) {
        ContentValues values = new ContentValues();
        values.put("identity", identity);
        values.put("size", size);
        values.put("modified", modified);
        values.put("backed_up_at", System.currentTimeMillis());
        getWritableDatabase().insertWithOnConflict(
                "uploaded", null, values, SQLiteDatabase.CONFLICT_REPLACE);
    }

    long uploadedCount() {
        try (Cursor cursor = getReadableDatabase().rawQuery(
                "SELECT COUNT(*) FROM uploaded", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    void reset() {
        getWritableDatabase().delete("uploaded", null, null);
    }
}
