package lol.rowe.blocker;

import java.time.Instant;
import java.time.format.DateTimeParseException;

final class Policy {
    static final Policy UNBLOCKED = new Policy(
            false,
            false,
            "This device is available.",
            false,
            null,
            null);

    final boolean blocked;
    final boolean blockRequested;
    final String message;
    final boolean extensionAvailable;
    final Instant enforceAt;
    final Instant extensionUntil;

    Policy(
            boolean blocked,
            boolean blockRequested,
            String message,
            boolean extensionAvailable,
            String enforceAt,
            String extensionUntil) {
        this.blocked = blocked;
        this.blockRequested = blockRequested;
        this.message = message == null || message.trim().isEmpty()
                ? "A parent has paused this device."
                : message;
        this.extensionAvailable = extensionAvailable;
        this.enforceAt = parseInstant(enforceAt);
        this.extensionUntil = parseInstant(extensionUntil);
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
