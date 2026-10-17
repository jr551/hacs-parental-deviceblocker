package lol.rowe.blocker;

import android.content.Context;

/**
 * A chore challenge from Home Assistant: the phone stays locked until the child
 * types the phrase back and sends a photo as evidence.
 */
final class Challenge {
    /** The longest edge of the photo uploaded as evidence. */
    static final int MAX_UPLOAD_EDGE = 1600;
    /** JPEG quality for the uploaded photo. */
    static final int UPLOAD_QUALITY = 80;
    private static final String PREFS = "rowe_blocker";
    private static final String ACCEPTED_ID = "challenge_accepted_id";

    final String id;
    final String phrase;
    final String sentAt;

    /** What Home Assistant said about an evidence upload or an accept. */
    static final class Outcome {
        /** True when Home Assistant took the call. */
        final boolean accepted;
        /** A message for the lock screen. */
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

    Challenge(String id, String phrase, String sentAt) {
        this.id = id == null ? "" : id.trim();
        this.phrase = phrase == null ? "" : phrase.trim();
        this.sentAt = sentAt == null ? "" : sentAt;
    }

    /** True when the typed answer matches the phrase after trimming and case-folding. */
    static boolean matches(String phrase, String typed) {
        if (phrase == null || typed == null) {
            return false;
        }
        String expected = phrase.trim();
        return !expected.isEmpty() && expected.equalsIgnoreCase(typed.trim());
    }

    /** True when this challenge must be shown: a new id the child has not accepted. */
    static boolean needsAnswer(String challengeId, String acceptedId) {
        String id = challengeId == null ? "" : challengeId.trim();
        if (id.isEmpty()) {
            return false;
        }
        return !id.equals(acceptedId == null ? "" : acceptedId.trim());
    }

    /** True when the child may press Accept: the phrase and the photo are both in. */
    static boolean canAccept(boolean phraseMatches, boolean hasPhoto) {
        return phraseMatches && hasPhoto;
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

    /** The challenge id the child has already answered, or "". */
    static String acceptedId(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(ACCEPTED_ID, "");
    }

    static void rememberAccepted(Context context, String challengeId) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(ACCEPTED_ID, challengeId == null ? "" : challengeId)
                .apply();
    }
}
