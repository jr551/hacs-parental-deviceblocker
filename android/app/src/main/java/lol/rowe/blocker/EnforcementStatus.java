package lol.rowe.blocker;

/**
 * Status values reported to Home Assistant so a fail-open or a disabled
 * Accessibility service is visible to a parent instead of looking like a
 * healthy unblocked phone.
 */
final class EnforcementStatus {
    static final String MONITORING = "monitoring";
    static final String ENFORCING = "enforcing";
    static final String FAIL_OPEN = "fail_open";
    static final String ACCESSIBILITY_DISABLED = "accessibility_disabled";
    static final String ACCESSIBILITY_INTERRUPTED = "accessibility_interrupted";
    static final String NOT_CONFIGURED = "not_configured";

    private EnforcementStatus() {
    }

    static boolean isDegraded(String status) {
        return FAIL_OPEN.equals(status)
                || ACCESSIBILITY_DISABLED.equals(status)
                || ACCESSIBILITY_INTERRUPTED.equals(status);
    }

    static String parentText(String status, String detail) {
        String extra = detail == null ? "" : detail.trim();
        if (FAIL_OPEN.equals(status)) {
            return extra.isEmpty()
                    ? "Last check failed open. Blocking is paused until Home Assistant answers again."
                    : "Last check failed open: " + extra;
        }
        if (ACCESSIBILITY_DISABLED.equals(status)) {
            return "Accessibility is off. This phone is not enforcing blocks.";
        }
        if (ACCESSIBILITY_INTERRUPTED.equals(status)) {
            return "Accessibility was interrupted. Blocking failed open.";
        }
        if (NOT_CONFIGURED.equals(status)) {
            return "Home Assistant is not configured. Blocking is disabled.";
        }
        if (ENFORCING.equals(status)) {
            return "A block is currently being enforced.";
        }
        if (MONITORING.equals(status)) {
            return "Accessibility is on. The latest policy was not a block.";
        }
        return extra;
    }
}
