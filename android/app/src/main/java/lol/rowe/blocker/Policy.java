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
            FamilyPeer familyPc) {
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
