package lol.rowe.blocker;

import android.content.Context;
import android.content.SharedPreferences;

/** Parent-visible note stored for the PIN-gated setup screen. */
final class EnforcementNote {
    private static final String PREFS = "rowe_blocker";
    private static final String STATUS = "enforcement_status";
    private static final String DETAIL = "enforcement_detail";

    private EnforcementNote() {
    }

    static void record(Context context, String status, String detail) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(STATUS, status == null ? "" : status)
                .putString(DETAIL, detail == null ? "" : detail)
                .apply();
    }

    static String parentText(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return EnforcementStatus.parentText(
                preferences.getString(STATUS, ""),
                preferences.getString(DETAIL, ""));
    }
}
