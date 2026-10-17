package lol.rowe.blocker;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;

final class Policy {
    static final Policy UNBLOCKED = new Policy(
            false,
            false,
            "This device is available.",
            false,
            null,
            null);

    /** The child's other device, as reported by Home Assistant. */
    static final class FamilyPeer {
        final String deviceId;
        final String deviceType;
        final boolean blocked;
        final String message;
        final boolean agentOnline;
        /** ISO-8601 timestamp, or null when the peer has never checked in. */
        final String lastSeen;
        final String activeApplication;

        FamilyPeer(
                String deviceId,
                String deviceType,
                boolean blocked,
                String message,
                boolean agentOnline,
                String lastSeen,
                String activeApplication) {
            this.deviceId = deviceId == null ? "" : deviceId;
            this.deviceType = deviceType == null ? "" : deviceType;
            this.blocked = blocked;
            this.message = message == null ? "" : message;
            this.agentOnline = agentOnline;
            this.lastSeen = lastSeen;
            this.activeApplication = activeApplication == null ? "" : activeApplication;
        }
    }

    final boolean blocked;
    final boolean blockRequested;
    final String message;
    final boolean extensionAvailable;
    final Instant enforceAt;
    final Instant extensionUntil;
    final Integer pointsBalance;
    final int pointsHourCost;
    /** Null when this response did not include an alarm. The phone keeps its cache. */
    final WakeSchedule.Update wakeAlarm;
    /** Home Assistant's allow-list; empty means "use the built-in defaults". */
    final List<String> allowedApps;
    final FamilyPeer familyPhone;
    final FamilyPeer familyPc;
    /** The active chore challenge, or null when there is none. */
    final Challenge challenge;
    /** A parent's on-demand location request, or null when there is none. */
    final LocationRequest locationRequest;
    /** How many unblock requests the child has pending, and the parent's cap. */
    final RequestBudget requestBudget;

    /**
     * The child's "ask a parent to allow another app" budget, as Home Assistant
     * reports it. Both halves are null when an older Home Assistant sends
     * neither, which leaves the child free to ask.
     */
    static final class RequestBudget {
        /** Requests already waiting for a parent, or null when it was not sent. */
        final Integer pending;
        /** The most requests a parent will look at, or null when it was not sent. */
        final Integer limit;

        static final RequestBudget NONE = new RequestBudget(null, null);

        RequestBudget(Integer pending, Integer limit) {
            this.pending = pending;
            this.limit = limit;
        }

        /** True when a parent is already looking at as many requests as they will. */
        boolean exhausted() {
            return pending != null && limit != null && limit > 0 && pending >= limit;
        }
    }

    /** A parent asking this phone for one position. */
    static final class LocationRequest {
        final String id;
        final String requestedAt;

        LocationRequest(String id, String requestedAt) {
            this.id = id == null ? "" : id.trim();
            this.requestedAt = requestedAt == null ? "" : requestedAt;
        }
    }

    Policy(
            boolean blocked,
            boolean blockRequested,
            String message,
            boolean extensionAvailable,
            String enforceAt,
            String extensionUntil) {
        this(blocked, blockRequested, message, extensionAvailable, enforceAt, extensionUntil, null, 0, null);
    }

    Policy(
            boolean blocked,
            boolean blockRequested,
            String message,
            boolean extensionAvailable,
            String enforceAt,
            String extensionUntil,
            Integer pointsBalance,
            int pointsHourCost) {
        this(
                blocked,
                blockRequested,
                message,
                extensionAvailable,
                enforceAt,
                extensionUntil,
                pointsBalance,
                pointsHourCost,
                null);
    }

    Policy(
            boolean blocked,
            boolean blockRequested,
            String message,
            boolean extensionAvailable,
            String enforceAt,
            String extensionUntil,
            Integer pointsBalance,
            int pointsHourCost,
            WakeSchedule.Update wakeAlarm) {
        this(
                blocked,
                blockRequested,
                message,
                extensionAvailable,
                enforceAt,
                extensionUntil,
                pointsBalance,
                pointsHourCost,
                wakeAlarm,
                Collections.emptyList(),
                null,
                null,
                null);
    }

    Policy(
            boolean blocked,
            boolean blockRequested,
            String message,
            boolean extensionAvailable,
            String enforceAt,
            String extensionUntil,
            Integer pointsBalance,
            int pointsHourCost,
            WakeSchedule.Update wakeAlarm,
            List<String> allowedApps,
            FamilyPeer familyPhone,
            FamilyPeer familyPc,
            Challenge challenge) {
        this(
                blocked,
                blockRequested,
                message,
                extensionAvailable,
                enforceAt,
                extensionUntil,
                pointsBalance,
                pointsHourCost,
                wakeAlarm,
                allowedApps,
                familyPhone,
                familyPc,
                challenge,
                null);
    }

    Policy(
            boolean blocked,
            boolean blockRequested,
            String message,
            boolean extensionAvailable,
            String enforceAt,
            String extensionUntil,
            Integer pointsBalance,
            int pointsHourCost,
            WakeSchedule.Update wakeAlarm,
            List<String> allowedApps,
            FamilyPeer familyPhone,
            FamilyPeer familyPc,
            Challenge challenge,
            LocationRequest locationRequest) {
        this(
                blocked,
                blockRequested,
                message,
                extensionAvailable,
                enforceAt,
                extensionUntil,
                pointsBalance,
                pointsHourCost,
                wakeAlarm,
                allowedApps,
                familyPhone,
                familyPc,
                challenge,
                locationRequest,
                RequestBudget.NONE);
    }

    Policy(
            boolean blocked,
            boolean blockRequested,
            String message,
            boolean extensionAvailable,
            String enforceAt,
            String extensionUntil,
            Integer pointsBalance,
            int pointsHourCost,
            WakeSchedule.Update wakeAlarm,
            List<String> allowedApps,
            FamilyPeer familyPhone,
            FamilyPeer familyPc,
            Challenge challenge,
            LocationRequest locationRequest,
            RequestBudget requestBudget) {
        this.blocked = blocked;
        this.blockRequested = blockRequested;
        this.message = message == null || message.trim().isEmpty()
                ? "A parent has paused this device."
                : message;
        this.extensionAvailable = extensionAvailable;
        this.enforceAt = parseInstant(enforceAt);
        this.extensionUntil = parseInstant(extensionUntil);
        this.pointsBalance = pointsBalance;
        this.pointsHourCost = pointsHourCost;
        this.wakeAlarm = wakeAlarm;
        this.allowedApps = allowedApps == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(allowedApps);
        this.familyPhone = familyPhone;
        this.familyPc = familyPc;
        this.challenge = challenge;
        this.locationRequest = locationRequest;
        this.requestBudget = requestBudget == null ? RequestBudget.NONE : requestBudget;
    }

    /**
     * The policy the phone enforces. A poll that failed and a parent's local
     * override both mean "unblocked": the phone must never stay blocked on a
     * policy it could not verify, and a parent's decision wins over the server.
     * A challenge the phone is holding survives a failed poll, so a chore a
     * parent has not approved cannot be escaped by taking the phone offline.
     */
    static Policy enforced(
            Policy fromServer, boolean pollFailed, boolean localOverrideActive, Challenge held) {
        if (localOverrideActive) {
            return UNBLOCKED;
        }
        if (pollFailed || fromServer == null) {
            return held == null ? UNBLOCKED : UNBLOCKED.withChallenge(held);
        }
        // A successful poll is authoritative: a challenge it does not carry has
        // been approved or cleared by a parent, and the held one was dropped to
        // match. That null is the phone's unlock signal.
        return fromServer;
    }

    /** The same policy with a different challenge. */
    Policy withChallenge(Challenge replacement) {
        return new Policy(
                blocked,
                blockRequested,
                message,
                extensionAvailable,
                enforceAt == null ? null : enforceAt.toString(),
                extensionUntil == null ? null : extensionUntil.toString(),
                pointsBalance,
                pointsHourCost,
                wakeAlarm,
                allowedApps,
                familyPhone,
                familyPc,
                replacement,
                locationRequest,
                requestBudget);
    }

    boolean isExtensionActive() {
        return extensionUntil != null && extensionUntil.isAfter(Instant.now());
    }

    boolean shouldCaptureForeground() {
        // Fail open: only a fresh server response with blocked=true may capture apps.
        // A cached grace policy must never turn into a block on the device clock while
        // Home Assistant or the network is unavailable.
        return blocked;
    }

    /**
     * True when the challenge lock is in force: this policy carries a challenge
     * a parent has not approved yet, and no parent override is running. The
     * challenge is a block with a different lock screen, so it is enforced by
     * the same path as {@link #blocked}. The lock lifts only when a successful
     * poll stops carrying the challenge.
     */
    boolean challengePending() {
        return challenge != null && !isExtensionActive();
    }

    /** True when the phone must be locked: a parent block, or an unapproved challenge. */
    boolean isLocked() {
        return shouldCaptureForeground() || challengePending();
    }

    boolean shouldWarnGrace() {
        return blockRequested
                && !blocked
                && !isExtensionActive()
                && enforceAt != null
                && Instant.now().isBefore(enforceAt);
    }

    long secondsUntilEnforcement() {
        if (enforceAt == null) {
            return 0;
        }
        return Math.max(0, enforceAt.getEpochSecond() - Instant.now().getEpochSecond());
    }

    private static Instant parseInstant(String value) {
        if (value == null || value.trim().isEmpty() || "null".equals(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
