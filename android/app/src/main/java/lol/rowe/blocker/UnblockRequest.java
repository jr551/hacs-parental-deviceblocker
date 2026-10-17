package lol.rowe.blocker;

import android.content.Context;

import java.time.Instant;
import java.time.ZoneId;

/**
 * The child's "ask a parent to allow this app" request: the label shown on the
 * block screen, the once-per-day limit, and the outcome of the
 * {@code unblock_request} call.
 */
final class UnblockRequest {
    private static final String PREFS = "rowe_blocker";
    /** One time for the whole device: the limit is per child, not per app. */
    private static final String LAST_REQUEST = "unblock_request_at";
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

    /**
     * True when this phone may ask a parent again. Home Assistant is the
     * authority and answers 429 for a second request in a day; this is the
     * same rule kept locally, for the whole device rather than per app, so a
     * child cannot shop for an answer by asking about app after app.
     */
    static boolean mayRequest(long lastRequestAt, long now) {
        return lastRequestAt <= 0L || !sameLocalDay(lastRequestAt, now);
    }

    private static boolean sameLocalDay(long left, long right) {
        ZoneId zone = ZoneId.systemDefault();
        return Instant.ofEpochMilli(left).atZone(zone).toLocalDate()
                .equals(Instant.ofEpochMilli(right).atZone(zone).toLocalDate());
    }

    /** The last time this phone asked about any app, or 0 when it never has. */
    static long lastRequestAt(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(LAST_REQUEST, 0L);
    }

    static void remember(Context context, long now) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putLong(LAST_REQUEST, now)
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
        if (status == 429) {
            // The daily limit is a rule, not a fault. Show Home Assistant's
            // own explanation when it sends one, so the child reads why
            // rather than a bare status code.
            return Outcome.failed(detail.isEmpty()
                    ? "A parent has already been asked today. You can ask again tomorrow."
                    : detail);
        }
        return Outcome.failed(detail.isEmpty()
                ? "Home Assistant refused the request (" + status + ")."
                : detail);
    }
}
