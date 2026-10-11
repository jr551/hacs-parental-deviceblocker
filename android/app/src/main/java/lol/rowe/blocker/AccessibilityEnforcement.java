package lol.rowe.blocker;

import android.content.ComponentName;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;

final class AccessibilityEnforcement {
    private AccessibilityEnforcement() {
    }

    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) {
            return false;
        }
        String expected = new ComponentName(context, BlockAccessibilityService.class).flattenToString();
        for (String item : enabled.split(":")) {
            if (expected.equalsIgnoreCase(item)) {
                return true;
            }
        }
        return false;
    }
}
