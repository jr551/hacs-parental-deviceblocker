package lol.rowe.blocker;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class PolicyDecision {
    static final String WHATSAPP = "com.whatsapp";
    static final String GOOGLE_MAPS = "com.google.android.apps.maps";

    private static final Set<String> CORE_SYSTEM_PACKAGES = new HashSet<>(Arrays.asList(
            "android",
            "com.android.systemui",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.samsung.android.permissioncontroller",
            "com.samsung.android.honeyboard",
            "com.google.android.inputmethod.latin",
            "com.android.server.telecom",
            "com.android.incallui",
            "com.samsung.android.incallui"));

    private final String ownPackage;

    PolicyDecision(String ownPackage) {
        this.ownPackage = ownPackage;
    }

    boolean isAllowed(boolean blocked, String packageName) {
        if (!blocked) {
            return true;
        }
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        return packageName.equals(ownPackage)
                || packageName.equals(WHATSAPP)
                || packageName.equals(GOOGLE_MAPS)
                || CORE_SYSTEM_PACKAGES.contains(packageName);
    }

    boolean shouldShowBlockScreen(boolean blocked, String packageName) {
        return blocked && !isAllowed(true, packageName);
    }
}
