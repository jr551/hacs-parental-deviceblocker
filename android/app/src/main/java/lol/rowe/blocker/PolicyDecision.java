package lol.rowe.blocker;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class PolicyDecision {
    static final String WHATSAPP = "com.whatsapp";
    static final String GOOGLE_MAPS = "com.google.android.apps.maps";
    static final String CLASSROOM = "com.google.android.apps.classroom";
    static final String MAPMYWALK = "com.mapmywalk.android2";
    static final String SAMSUNG_DIALER = "com.samsung.android.dialer";
    static final String SAMSUNG_CLOCK = "com.sec.android.app.clockpackage";
    static final String SAMSUNG_CAMERA = "com.sec.android.app.camera";

    private static final Set<String> ALLOWED_APPS = new HashSet<>(Arrays.asList(
            WHATSAPP,
            GOOGLE_MAPS,
            CLASSROOM,
            MAPMYWALK,
            SAMSUNG_DIALER,
            "com.google.android.dialer",
            "com.android.dialer",
            SAMSUNG_CLOCK,
            "com.google.android.deskclock",
            "com.android.deskclock",
            SAMSUNG_CAMERA,
            "com.android.camera2",
            "com.google.android.GoogleCamera"));

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
                || ALLOWED_APPS.contains(packageName)
                || CORE_SYSTEM_PACKAGES.contains(packageName);
    }

    boolean shouldShowBlockScreen(boolean blocked, String packageName) {
        return blocked && !isAllowed(true, packageName);
    }

    /**
     * Uninstall and device-admin screens stay guarded even while the phone is
     * allowed. Package-installer UI is left alone while an update we started
     * is on screen, so the updater can finish.
     */
    boolean isUninstallSurface(String packageName, String className) {
        if (UpdateGate.installInProgress) {
            return false;
        }
        String pkg = packageName == null ? "" : packageName;
        String cls = className == null ? "" : className.toLowerCase(java.util.Locale.ROOT);
        if (pkg.equals("com.google.android.packageinstaller")
                || pkg.equals("com.android.packageinstaller")
                || pkg.equals("com.samsung.android.packageinstaller")) {
            return cls.contains("uninstall");
        }
        return cls.contains("deviceadmin") || cls.contains("uninstaller");
    }
}
