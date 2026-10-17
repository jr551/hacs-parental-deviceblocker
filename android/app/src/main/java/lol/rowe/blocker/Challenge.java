package lol.rowe.blocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * A chore challenge from Home Assistant: the phone stays locked until the child
 * types the phrase back, sends a photo as evidence, and a parent approves it.
 * The phone never accepts its own challenge — the lock lifts only when the
 * state payload stops carrying one.
 */
final class Challenge {
    private static final String PREFS = "rowe_blocker";
    private static final String HELD_ID = "challenge_held_id";
    private static final String HELD_PHRASE = "challenge_held_phrase";
    private static final String HELD_STATUS = "challenge_held_status";
    private static final String HELD_SUBMITTED_AT = "challenge_held_submitted_at";

    /** The child is filling the challenge in. */
    static final String STATUS_PENDING = "pending";
    /** The photo is in and a parent has to approve it. */
    static final String STATUS_SUBMITTED = "submitted";

    /** How long a parent is given to approve, as the waiting view counts it down. */
    static final long APPROVAL_WINDOW_SECONDS = 3600L;

    final String id;
    final String phrase;
    final String sentAt;
    /** {@link #STATUS_PENDING} or {@link #STATUS_SUBMITTED}. */
    final String status;
    /** ISO-8601 when the child submitted, or "" while they have not. */
    final String submittedAt;

    /** What Home Assistant said about an evidence upload or a submit. */
    static final class Outcome {
        /** True when Home Assistant took the call. */
        final boolean accepted;
        /** True when the challenge no longer exists, so the lock must be let go. */
        final boolean gone;
        /** A message for the lock screen. */
        final String message;

        private Outcome(boolean accepted, boolean gone, String message) {
            this.accepted = accepted;
            this.gone = gone;
            this.message = message == null ? "" : message;
        }

        static Outcome accepted(String message) {
            return new Outcome(true, false, message);
        }

        static Outcome gone(String message) {
            return new Outcome(false, true, message);
        }

        static Outcome failed(String message) {
            return new Outcome(false, false, message);
        }
    }

    Challenge(String id, String phrase, String sentAt) {
        this(id, phrase, sentAt, STATUS_PENDING, null);
    }

    Challenge(String id, String phrase, String sentAt, String status, String submittedAt) {
        this.id = id == null ? "" : id.trim();
        this.phrase = phrase == null ? "" : phrase.trim();
        this.sentAt = sentAt == null ? "" : sentAt;
        this.status = normalizeStatus(status);
        this.submittedAt = submittedAt == null ? "" : submittedAt;
    }

    /**
     * A status the phone understands. Anything else — including a missing one
     * from a Home Assistant that predates the submit flow — reads as pending,
     * so an older server still shows the form.
     */
    static String normalizeStatus(String status) {
        return STATUS_SUBMITTED.equals(status == null ? "" : status.trim())
                ? STATUS_SUBMITTED
                : STATUS_PENDING;
    }

    /** True when the typed answer matches the phrase after trimming and case-folding. */
    static boolean matches(String phrase, String typed) {
        if (phrase == null || typed == null) {
            return false;
        }
        String expected = phrase.trim();
        return !expected.isEmpty() && expected.equalsIgnoreCase(typed.trim());
    }

    /** True when the child may send the challenge: the phrase and the photo are both in. */
    static boolean canSubmit(boolean phraseMatches, boolean hasPhoto) {
        return phraseMatches && hasPhoto;
    }

    /** The step the child is on: type the phrase, take the photo, then send it. */
    static final int STEP_PHRASE = 1;
    static final int STEP_PHOTO = 2;
    static final int STEP_SEND = 3;

    /** Which element the lock highlights for the child's progress. */
    static int step(boolean phraseMatches, boolean hasPhoto) {
        if (!phraseMatches) {
            return STEP_PHRASE;
        }
        return hasPhoto ? STEP_SEND : STEP_PHOTO;
    }

    /** What the lock screen shows for a challenge. */
    static final int PANEL_UNLOCKED = 0;
    static final int PANEL_FORM = 1;
    static final int PANEL_WAITING = 2;

    /**
     * The single decision point for the lock screen: a challenge the child has
     * not sent shows the form, one waiting for a parent shows the wait, and no
     * challenge at all means the phone is unlocked. A challenge a parent sent
     * back reads as pending again, so it returns to the form.
     */
    static int panelState(String status, boolean hasChallenge) {
        if (!hasChallenge) {
            return PANEL_UNLOCKED;
        }
        return STATUS_SUBMITTED.equals(normalizeStatus(status)) ? PANEL_WAITING : PANEL_FORM;
    }

    /** The status change the panel must react to. */
    static final int TRANSITION_NONE = 0;
    /** A parent sent the challenge back: the evidence is gone and the photo must be retaken. */
    static final int TRANSITION_REJECTED = 1;

    /**
     * What changed between two statuses of the same challenge. Only a challenge
     * that was waiting for a parent and is now pending again has been sent back.
     */
    static int statusTransition(String previousStatus, String nextStatus) {
        if (panelState(previousStatus, true) == PANEL_WAITING
                && panelState(nextStatus, true) == PANEL_FORM) {
            return TRANSITION_REJECTED;
        }
        return TRANSITION_NONE;
    }

    /** The countdown line for the waiting view, from the seconds since the child sent it. */
    static String countdownText(long elapsedSeconds) {
        long remaining = APPROVAL_WINDOW_SECONDS - Math.max(0L, elapsedSeconds);
        if (remaining <= 0) {
            return "A parent still needs to approve this.";
        }
        return String.format(Locale.US, "%d:%02d left", remaining / 60, remaining % 60);
    }

    /** The epoch millis of an ISO-8601 timestamp, or 0 when it is missing or unreadable. */
    static long epochMillis(String timestamp) {
        if (timestamp == null || timestamp.trim().isEmpty()) {
            return 0L;
        }
        try {
            return Instant.parse(timestamp.trim()).toEpochMilli();
        } catch (DateTimeParseException exception) {
            return 0L;
        }
    }

    /**
     * The size to scale a captured photo to so its longest edge is at most
     * {@code maxEdge}, or null when it is already small enough to upload.
     */
    static int[] uploadSize(int width, int height, int maxEdge) {
        if (width <= 0 || height <= 0 || maxEdge <= 0) {
            return null;
        }
        int longest = Math.max(width, height);
        if (longest <= maxEdge) {
            return null;
        }
        double scale = (double) maxEdge / longest;
        return new int[] {
                Math.max(1, (int) Math.round(width * scale)),
                Math.max(1, (int) Math.round(height * scale)),
        };
    }

    /**
     * The challenge this phone is holding, or null. A challenge is held from the
     * moment Home Assistant sends it until a parent approves it or clears it, so
     * a poll that fails while the child is taking the photo or waiting for a
     * parent cannot hand the phone back mid-chore.
     */
    static Challenge held(Context context) {
        SharedPreferences preferences =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = preferences.getString(HELD_ID, "");
        String phrase = preferences.getString(HELD_PHRASE, "");
        if (id == null || id.trim().isEmpty() || phrase == null || phrase.trim().isEmpty()) {
            return null;
        }
        return new Challenge(
                id,
                phrase,
                null,
                preferences.getString(HELD_STATUS, STATUS_PENDING),
                preferences.getString(HELD_SUBMITTED_AT, null));
    }

    /** Hold {@code challenge}, or clear the held one when it is null. */
    static void hold(Context context, Challenge challenge) {
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
        if (challenge == null) {
            editor.remove(HELD_ID)
                    .remove(HELD_PHRASE)
                    .remove(HELD_STATUS)
                    .remove(HELD_SUBMITTED_AT);
        } else {
            editor.putString(HELD_ID, challenge.id)
                    .putString(HELD_PHRASE, challenge.phrase)
                    .putString(HELD_STATUS, challenge.status)
                    .putString(HELD_SUBMITTED_AT, challenge.submittedAt);
        }
        editor.apply();
    }

    /**
     * The policy the phone enforces. A successful poll is authoritative: a
     * challenge it does not carry has been approved or cleared by a parent, so
     * the held one is dropped and the phone unlocks. A failed poll keeps the
     * held challenge — the block still fails open, but a chore a parent has not
     * approved stays on screen.
     */
    static Policy enforced(
            Context context, Policy fromServer, boolean pollFailed, boolean localOverrideActive) {
        if (!pollFailed && fromServer != null) {
            hold(context, fromServer.challenge);
        }
        return Policy.enforced(fromServer, pollFailed, localOverrideActive, held(context));
    }
}
