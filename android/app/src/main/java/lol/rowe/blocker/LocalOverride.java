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
    private static final String GRANTED = "local_override_granted";

    private LocalOverride() {
    }

    /** Grant an override that ends {@code millis} from now. */
    static void grantFor(Context context, long millis) {
        long now = System.currentTimeMillis();
        preferences(context)
                .edit()
                .putLong(UNTIL, now + Math.max(0L, millis))
                .putLong(GRANTED, now)
                .apply();
    }

    /**
     * When the running override was granted, or 0 when none is. A block a
     * parent starts after this instant supersedes the override, so turning the
     * switch off and on is how a parent revokes a local unlock.
     *
     * <p>An override granted before the grant timestamp was recorded still has
     * its deadline, and the deadline is always the grant plus the duration, so
     * the grant can be derived from it. Without that, an override written by an
     * older build could never be superseded and a parent would have no lever
     * until it lapsed on its own.
     */
    static long grantedAtMillis(Context context) {
        SharedPreferences prefs = preferences(context);
        return grantMillis(prefs.getLong(UNTIL, 0L), prefs.getLong(GRANTED, 0L));
    }

    /** The grant instant behind {@code until}, falling back to the duration. */
    static long grantMillis(long until, long granted) {
        if (granted > 0L) {
            return granted;
        }
        return until > 0L ? Math.max(0L, until - DURATION_MS) : 0L;
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
        preferences(context).edit().remove(UNTIL).remove(GRANTED).apply();
    }

    /** True when an override ending at {@code until} is still running at {@code now}. */
    static boolean isActive(long until, long now) {
        return until > now;
    }

    /**
     * True when a block enforced at {@code enforceAtMillis} was requested after
     * the unlock granted at {@code grantedAtMillis}, so the block wins. A grant
     * of 0 means no override is recorded, and nothing can supersede it.
     */
    static boolean blockSupersedes(long enforceAtMillis, long grantedAtMillis) {
        return grantedAtMillis > 0L && enforceAtMillis > grantedAtMillis;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
