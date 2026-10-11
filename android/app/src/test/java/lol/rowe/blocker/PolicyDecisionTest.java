package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PolicyDecisionTest {
    private final PolicyDecision decision = new PolicyDecision("lol.rowe.blocker.debug");

    @Test
    public void allAppsAreAllowedWhenUnblocked() {
        assertTrue(decision.isAllowed(false, "com.android.chrome"));
        assertTrue(decision.isAllowed(false, "com.sec.android.gallery3d"));
    }

    @Test
    public void onlyRequestedAppsAndInternalComponentsAreAllowedWhenBlocked() {
        assertTrue(decision.isAllowed(true, PolicyDecision.WHATSAPP));
        assertTrue(decision.isAllowed(true, PolicyDecision.GOOGLE_MAPS));
        assertTrue(decision.isAllowed(true, PolicyDecision.SAMSUNG_DIALER));
        assertTrue(decision.isAllowed(true, PolicyDecision.SAMSUNG_CLOCK));
        assertTrue(decision.isAllowed(true, PolicyDecision.SAMSUNG_CAMERA));
        assertTrue(decision.isAllowed(true, PolicyDecision.CLASSROOM));
        assertTrue(decision.isAllowed(true, PolicyDecision.MAPMYWALK));
        assertTrue(decision.isAllowed(true, "lol.rowe.blocker.debug"));
        assertTrue(decision.isAllowed(true, "com.android.systemui"));

        assertFalse(decision.isAllowed(true, "com.android.chrome"));
        assertFalse(decision.isAllowed(true, "com.android.settings"));
        assertFalse(decision.isAllowed(true, "com.sec.android.gallery3d"));
        assertFalse(decision.isAllowed(true, "com.sec.android.app.launcher"));
    }

    @Test
    public void unknownOrBlankPackagesShowTheBlockScreen() {
        assertTrue(decision.shouldShowBlockScreen(true, null));
        assertTrue(decision.shouldShowBlockScreen(true, ""));
        assertFalse(decision.shouldShowBlockScreen(false, null));
    }

    @Test
    public void uninstallAndDeviceAdminScreensStayGuarded() {
        assertTrue(decision.isUninstallSurface(
                "com.google.android.packageinstaller",
                "com.android.packageinstaller.UninstallerActivity"));
        assertTrue(decision.isUninstallSurface(
                "com.android.settings",
                "com.android.settings.DeviceAdminAdd"));
        assertFalse(decision.isUninstallSurface("com.android.settings", "com.android.settings.Settings"));
        UpdateGate.installInProgress = true;
        try {
            assertFalse(decision.isUninstallSurface(
                    "com.google.android.packageinstaller",
                    "com.android.packageinstaller.UninstallerActivity"));
        } finally {
            UpdateGate.installInProgress = false;
        }
    }
}
