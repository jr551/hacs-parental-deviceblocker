package lol.rowe.blocker;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
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

    private static final Set<String> ALLOWED_APPS = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
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
                    "com.google.android.GoogleCamera")));

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
    /** Home Assistant's allow-list, or the built-in defaults when it sends none. */
    private volatile Set<String> allowedApps = ALLOWED_APPS;
    /** Allowed while the challenge photo is being captured, or "". */
    private volatile String capturePackage = "";

    PolicyDecision(String ownPackage) {
        this.ownPackage = ownPackage;
    }

    /** The built-in allow-list used when Home Assistant sends none. */
    static Set<String> defaultAllowedApps() {
        return ALLOWED_APPS;
    }

    /**
     * Adopt Home Assistant's allow-list. An empty list means the parent has not
     * chosen one, so the built-in defaults stay in force.
     */
    void setAllowedApps(Collection<String> fromServer) {
        if (fromServer == null || fromServer.isEmpty()) {
            allowedApps = ALLOWED_APPS;
            return;
        }
        Set<String> replacement = new HashSet<>();
        for (String packageName : fromServer) {
            if (packageName != null && !packageName.trim().isEmpty()) {
                replacement.add(packageName.trim());
            }
        }
        allowedApps = replacement.isEmpty()
                ? ALLOWED_APPS
                : Collections.unmodifiableSet(replacement);
    }

    boolean isAllowed(boolean blocked, String packageName) {
        if (!blocked) {
            return true;
        }
        if (packageName == null || packageName.trim().isEmpty()) {
            return false;
        }
        return packageName.equals(ownPackage)
                || allowedApps.contains(packageName)
                || CORE_SYSTEM_PACKAGES.contains(packageName)
                || packageName.equals(capturePackage);
    }

    /**
     * Allow one package while the challenge photo is being captured. The system
     * camera is not on the allow-list, and the lock screen must not cover it
     * mid-shot; the allowance is dropped as soon as the shot comes back.
     */
    void setCapturePackage(String packageName) {
        capturePackage = packageName == null ? "" : packageName.trim();
    }

    boolean shouldShowBlockScreen(boolean blocked, String packageName) {
        return blocked && !isAllowed(true, packageName);
    }

    /**
     * How long launcher and covered-app windows may keep firing after an
     * allow-listed app is opened. Those windows are not the holder leaving.
     */
    static final long ALLOWED_LAUNCH_GRACE_MS = 1500;

    static final class WindowUpdate {
        final String foreground;
        final boolean raiseBlockScreen;

        WindowUpdate(String foreground, boolean raiseBlockScreen) {
            this.foreground = foreground == null ? "" : foreground;
            this.raiseBlockScreen = raiseBlockScreen;
        }
    }

    /**
     * Decide the enforcement foreground after one accessibility event.
     * Window-list churn must not cover an allow-listed app. A window-state
     * event for a disallowed app must.
     *
     * @param millisSinceAllowedLaunch negative when no allow-listed launch is pending
     */
    WindowUpdate onForegroundEvent(
            boolean blocked,
            boolean windowStateChanged,
            String currentForeground,
            String eventPackage,
            String pendingAllowedPackage,
            String packageBeingCovered,
            long millisSinceAllowedLaunch) {
        String current = currentForeground == null ? "" : currentForeground;
        if (!windowStateChanged) {
            return new WindowUpdate(current, false);
        }
        String event = eventPackage == null ? "" : eventPackage.trim();
        boolean inGrace = pendingAllowedPackage != null
                && !pendingAllowedPackage.isEmpty()
                && millisSinceAllowedLaunch >= 0
                && millisSinceAllowedLaunch < ALLOWED_LAUNCH_GRACE_MS;
        String launched = inGrace ? pendingAllowedPackage : current;
        if (event.isEmpty()) {
            if (inGrace) {
                return new WindowUpdate(launched, false);
            }
            return new WindowUpdate(current, shouldShowBlockScreen(blocked, current));
        }
        if (isAllowed(true, event)) {
            // System chrome (our own activity, system UI, the keyboard) flickers
            // while the allow-listed app is opening. Keep that app in front.
            if (inGrace && !event.equals(pendingAllowedPackage) && isCoreOrSelf(event)) {
                return new WindowUpdate(launched, false);
            }
            return new WindowUpdate(event, false);
        }
        if (inGrace && (isLaunchTransition(event) || event.equals(packageBeingCovered))) {
            return new WindowUpdate(launched, false);
        }
        // An allow-listed app can stay up across a Google Play services overlay.
        // Home and any other app still replace it.
        if (isSystemOverlay(event) && isAllowedThirdParty(current)) {
            return new WindowUpdate(current, false);
        }
        return new WindowUpdate(event, blocked);
    }

    private boolean isCoreOrSelf(String packageName) {
        return packageName.equals(ownPackage) || CORE_SYSTEM_PACKAGES.contains(packageName);
    }

    private boolean isAllowedThirdParty(String packageName) {
        return isAllowed(true, packageName) && !isCoreOrSelf(packageName);
    }

    private static boolean isLaunchTransition(String packageName) {
        return "com.sec.android.app.launcher".equals(packageName)
                || "com.android.launcher".equals(packageName)
                || "com.android.launcher3".equals(packageName)
                || "com.google.android.apps.nexuslauncher".equals(packageName)
                || "com.sec.android.emergencylauncher".equals(packageName)
                || "com.samsung.android.app.cocktailbarservice".equals(packageName);
    }

    private static boolean isSystemOverlay(String packageName) {
        return "com.google.android.gms".equals(packageName)
                || "com.google.android.gsf".equals(packageName)
                || "com.google.android.partnersetup".equals(packageName);
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
