package lol.rowe.blocker;

import android.content.Context;

/**
 * The child's "ask a parent to allow this app" request: the label shown on the
 * block screen, the once-per-ten-minutes throttle, and the outcome of the
 * {@code unblock_request} call.
 */
final class UnblockRequest {
    /** One request per app per ten minutes. */
    static final long THROTTLE_MS = 10 * 60 * 1000L;
    private static final String PREFS = "rowe_blocker";
    private static final String PREFIX = "unblock_request_at_";
    /** Home Assistant's error text is shown as-is; longer text is not a message. */
    private static final int MAX_ERROR_LENGTH = 160;

    /** What Home Assistant said about a request. */
    static final class Outcome {
        /** True when a parent will see the request: accepted, or already pending. */
        final boolean accepted;
        /** A message for the block screen. */
        final String message;

        private Outcome(boolean accepted, String message) {
            this.accepted = accepted;
            this.message = message == null ? "" : message;
        }

        static Outcome accepted(String message) {
            return new Outcome(true, message);
        }

        static Outcome failed(String message) {
            return new Outcome(false, message);
        }
    }

    private UnblockRequest() {
    }

    /** The app's name for display, or the package name when no label is known. */
    static String label(String packageName, String resolvedLabel) {
        String name = resolvedLabel == null ? "" : resolvedLabel.trim();
        return name.isEmpty() ? packageName : name;
    }

    /** True when a request for this app may be sent now. */
    static boolean mayRequest(long lastRequestAt, long now) {
        return lastRequestAt <= 0L || now - lastRequestAt >= THROTTLE_MS;
    }

    /** The last time this app was asked about, or 0 when it never was. */
    static long lastRequestAt(Context context, String packageName) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(PREFIX + packageName, 0L);
    }

    static void remember(Context context, String packageName, long now) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(PREFIX + packageName, now)
                .apply();
    }

    /**
     * The outcome of a POST to the unblock_request endpoint.
     *
     * @param status the HTTP status Home Assistant returned
     * @param error Home Assistant's error text, or "" when it sent none
     * @param label the app's name, for the messages
     */
    static Outcome fromResponse(int status, String error, String label) {
        String detail = error == null ? "" : error.trim();
        if (detail.length() > MAX_ERROR_LENGTH) {
            detail = "";
        }
        if (status == 200) {
            return Outcome.accepted("Asked a parent to allow " + label + ".");
        }
        if (status == 409) {
            // Already allowed, or already asked: either way the child must not
            // ask again, and Home Assistant's reason is the useful message.
            return Outcome.accepted(detail.isEmpty()
                    ? "A parent has already been asked to allow " + label + "."
                    : detail);
        }
        return Outcome.failed(detail.isEmpty()
                ? "Home Assistant refused the request (" + status + ")."
                : detail);
    }
}
