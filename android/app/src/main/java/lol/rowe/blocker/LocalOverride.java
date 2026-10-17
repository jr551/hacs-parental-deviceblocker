package lol.rowe.blocker;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * A parent override granted on the phone itself, when Home Assistant refuses
 * the override or cannot be reached. It is stored in the app's preferences so
 * it survives a process restart, and it lapses on its own.
 */
final class LocalOverride {
    /** How long a local override lasts. */
    static final long DURATION_MS = 60 * 60 * 1000L;
    private static final String PREFS = "rowe_blocker";
    private static final String UNTIL = "local_override_until";

    private LocalOverride() {
    }

    /** Grant an override that ends {@code millis} from now. */
    static void grantFor(Context context, long millis) {
        preferences(context)
                .edit()
                .putLong(UNTIL, System.currentTimeMillis() + Math.max(0L, millis))
                .apply();
    }

    /** When the override ends, or 0 when none is active. */
    static long activeUntil(Context context) {
        long until = preferences(context).getLong(UNTIL, 0L);
        return isActive(until, System.currentTimeMillis()) ? until : 0L;
    }

    static boolean isActive(Context context) {
        return activeUntil(context) > 0L;
    }

    static void clear(Context context) {
        preferences(context).edit().remove(UNTIL).apply();
    }

    /** True when an override ending at {@code until} is still running at {@code now}. */
    static boolean isActive(long until, long now) {
        return until > now;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
