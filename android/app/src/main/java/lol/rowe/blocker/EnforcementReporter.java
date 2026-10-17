package lol.rowe.blocker;

import android.content.Context;

/**
 * The watchdog's check-in with Home Assistant, run on every watchdog pass.
 *
 * <p>While Accessibility is off, this reports that state so a parent sees why
 * the phone is not enforcing. While Accessibility is on, the accessibility
 * service owns the status fields, so this only refreshes the server's
 * {@code last_seen}: its policy poller can stop silently while the service
 * stays bound and enabled, and without a check-in from somewhere else that
 * looks exactly like a healthy phone that has gone offline.
 */
final class EnforcementReporter {
    private EnforcementReporter() {
    }

    static void checkIn(Context context) {
        AppConfig config = AppConfig.load(context);
        if (!config.isConfigured()) {
            return;
        }
        if (AccessibilityEnforcement.isEnabled(context)) {
            heartbeat(config);
            return;
        }
        String detail = "Accessibility is off, so this phone is not enforcing blocks.";
        EnforcementNote.record(context, EnforcementStatus.ACCESSIBILITY_DISABLED, detail);
        try {
            new HomeAssistantClient(config).postActivity(
                    "unknown",
                    "",
                    null,
                    EnforcementStatus.ACCESSIBILITY_DISABLED,
                    detail);
        } catch (Exception ignored) {
            // The periodic job retries. Until a report lands, Home Assistant
            // shows the agent offline.
        }
    }

    /**
     * A plain liveness check-in. The state read refreshes {@code last_seen}
     * server-side and leaves the accessibility service's own status alone.
     */
    private static void heartbeat(AppConfig config) {
        try {
            new HomeAssistantClient(config).postHeartbeat();
        } catch (Exception ignored) {
            // The next watchdog pass retries.
        }
    }
}
