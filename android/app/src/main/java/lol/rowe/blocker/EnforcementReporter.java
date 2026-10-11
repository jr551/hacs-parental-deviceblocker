package lol.rowe.blocker;

import android.content.Context;

/**
 * Reports a disabled Accessibility service to Home Assistant. While the service
 * is enabled, that service owns healthy and fail-open reports so the two
 * channels do not overwrite each other.
 */
final class EnforcementReporter {
    private EnforcementReporter() {
    }

    static void reportIfAccessibilityDisabled(Context context) {
        AppConfig config = AppConfig.load(context);
        if (!config.isConfigured() || AccessibilityEnforcement.isEnabled(context)) {
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
}
