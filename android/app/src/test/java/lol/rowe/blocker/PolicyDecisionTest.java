package lol.rowe.blocker;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
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
    public void allowedForegroundDoesNotShowTheBlockScreen() {
        assertFalse(decision.shouldShowBlockScreen(true, PolicyDecision.WHATSAPP));
        assertFalse(decision.shouldShowBlockScreen(true, PolicyDecision.GOOGLE_MAPS));
        assertFalse(decision.shouldShowBlockScreen(true, PolicyDecision.CLASSROOM));
        assertFalse(decision.shouldShowBlockScreen(true, PolicyDecision.MAPMYWALK));
        assertFalse(decision.shouldShowBlockScreen(true, PolicyDecision.SAMSUNG_DIALER));
        assertFalse(decision.shouldShowBlockScreen(true, "com.google.android.dialer"));
        assertFalse(decision.shouldShowBlockScreen(true, "com.android.dialer"));
        assertFalse(decision.shouldShowBlockScreen(true, PolicyDecision.SAMSUNG_CLOCK));
        assertFalse(decision.shouldShowBlockScreen(true, PolicyDecision.SAMSUNG_CAMERA));
        assertFalse(decision.shouldShowBlockScreen(true, "com.samsung.android.incallui"));
        assertFalse(decision.shouldShowBlockScreen(true, "com.android.incallui"));
        assertFalse(decision.shouldShowBlockScreen(true, "lol.rowe.blocker.debug"));
        assertTrue(decision.shouldShowBlockScreen(true, "com.android.chrome"));
        assertTrue(decision.shouldShowBlockScreen(true, "com.sec.android.app.launcher"));
    }

    @Test
    public void windowListChangesDoNotCoverAnAllowedApp() {
        PolicyDecision.WindowUpdate update = decision.onForegroundEvent(
                true,
                false,
                PolicyDecision.WHATSAPP,
                "com.sec.android.app.launcher",
                null,
                null,
                -1);
        assertEquals(PolicyDecision.WHATSAPP, update.foreground);
        assertFalse(update.raiseBlockScreen);
    }

    @Test
    public void launcherDuringAllowedLaunchDoesNotCoverTheApp() {
        PolicyDecision.WindowUpdate update = decision.onForegroundEvent(
                true,
                true,
                PolicyDecision.CLASSROOM,
                "com.sec.android.app.launcher",
                PolicyDecision.CLASSROOM,
                "com.innersloth.spacemafia",
                490);
        assertEquals(PolicyDecision.CLASSROOM, update.foreground);
        assertFalse(update.raiseBlockScreen);
    }

    @Test
    public void coveredAppClosingDuringLaunchDoesNotRaiseTheBlockScreen() {
        PolicyDecision.WindowUpdate update = decision.onForegroundEvent(
                true,
                true,
                PolicyDecision.GOOGLE_MAPS,
                "com.innersloth.spacemafia",
                PolicyDecision.GOOGLE_MAPS,
                "com.innersloth.spacemafia",
                500);
        assertEquals(PolicyDecision.GOOGLE_MAPS, update.foreground);
        assertFalse(update.raiseBlockScreen);
    }

    @Test
    public void systemChromeDuringLaunchKeepsTheAllowedApp() {
        PolicyDecision.WindowUpdate update = decision.onForegroundEvent(
                true,
                true,
                PolicyDecision.SAMSUNG_DIALER,
                "com.android.systemui",
                PolicyDecision.SAMSUNG_DIALER,
                null,
                200);
        assertEquals(PolicyDecision.SAMSUNG_DIALER, update.foreground);
        assertFalse(update.raiseBlockScreen);
    }

    @Test
    public void playServicesOverlayDoesNotCoverAnAllowedApp() {
        PolicyDecision.WindowUpdate update = decision.onForegroundEvent(
                true,
                true,
                PolicyDecision.CLASSROOM,
                "com.google.android.gms",
                null,
                null,
                -1);
        assertEquals(PolicyDecision.CLASSROOM, update.foreground);
        assertFalse(update.raiseBlockScreen);
    }

    @Test
    public void aDifferentDisallowedAppDuringLaunchShowsTheBlockScreen() {
        PolicyDecision.WindowUpdate update = decision.onForegroundEvent(
                true,
                true,
                PolicyDecision.WHATSAPP,
                "com.android.chrome",
                PolicyDecision.WHATSAPP,
                "com.innersloth.spacemafia",
                400);
        assertEquals("com.android.chrome", update.foreground);
        assertTrue(update.raiseBlockScreen);
    }

    @Test
    public void leavingAnAllowedAppShowsTheBlockScreenAgain() {
        PolicyDecision.WindowUpdate maps = decision.onForegroundEvent(
                true, true, "", PolicyDecision.GOOGLE_MAPS, null, null, -1);
        assertEquals(PolicyDecision.GOOGLE_MAPS, maps.foreground);
        assertFalse(maps.raiseBlockScreen);

        PolicyDecision.WindowUpdate chrome = decision.onForegroundEvent(
                true, true, maps.foreground, "com.android.chrome", null, null, -1);
        assertEquals("com.android.chrome", chrome.foreground);
        assertTrue(chrome.raiseBlockScreen);

        PolicyDecision.WindowUpdate home = decision.onForegroundEvent(
                true,
                true,
                PolicyDecision.MAPMYWALK,
                "com.sec.android.app.launcher",
                PolicyDecision.MAPMYWALK,
                null,
                PolicyDecision.ALLOWED_LAUNCH_GRACE_MS);
        assertEquals("com.sec.android.app.launcher", home.foreground);
        assertTrue(home.raiseBlockScreen);
    }

    @Test
    public void unblockedWindowChangesDoNotRaiseTheBlockScreen() {
        PolicyDecision.WindowUpdate update = decision.onForegroundEvent(
                false, true, "", "com.android.chrome", null, null, -1);
        assertEquals("com.android.chrome", update.foreground);
        assertFalse(update.raiseBlockScreen);
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

    @Test
    public void homeAssistantAllowListReplacesTheBuiltInDefaults() {
        decision.setAllowedApps(Arrays.asList("com.roblox.client", "com.whatsapp"));

        assertTrue(decision.isAllowed(true, "com.roblox.client"));
        assertTrue(decision.isAllowed(true, "com.whatsapp"));
        // The built-in defaults are gone: Maps is no longer allowed.
        assertFalse(decision.isAllowed(true, PolicyDecision.GOOGLE_MAPS));
        assertFalse(decision.isAllowed(true, PolicyDecision.CLASSROOM));
        // The phone's own components and the core system packages always stay.
        assertTrue(decision.isAllowed(true, "lol.rowe.blocker.debug"));
        assertTrue(decision.isAllowed(true, "com.android.systemui"));
        assertTrue(decision.isAllowed(true, "com.samsung.android.incallui"));
    }

    @Test
    public void anEmptyAllowListKeepsTheBuiltInDefaults() {
        decision.setAllowedApps(Arrays.asList("com.roblox.client"));
        decision.setAllowedApps(Collections.emptyList());

        assertTrue(decision.isAllowed(true, PolicyDecision.WHATSAPP));
        assertTrue(decision.isAllowed(true, PolicyDecision.GOOGLE_MAPS));
        assertFalse(decision.isAllowed(true, "com.roblox.client"));
    }

    @Test
    public void aMissingOrBlankAllowListKeepsTheBuiltInDefaults() {
        decision.setAllowedApps(null);
        assertTrue(decision.isAllowed(true, PolicyDecision.WHATSAPP));

        decision.setAllowedApps(Arrays.asList("", "   "));
        assertTrue(decision.isAllowed(true, PolicyDecision.WHATSAPP));
        assertFalse(decision.isAllowed(true, "com.roblox.client"));
    }

    @Test
    public void theAllowListAlsoDrivesTheBlockScreenDecision() {
        decision.setAllowedApps(Arrays.asList("com.roblox.client"));

        assertFalse(decision.shouldShowBlockScreen(true, "com.roblox.client"));
        assertTrue(decision.shouldShowBlockScreen(true, PolicyDecision.WHATSAPP));
    }

    @Test
    public void aChallengeBlocksDisallowedAppsAndLeavesAllowedOnesUsable() {
        Policy policy = challengePolicy(null);
        boolean locked = policy.isLocked();

        assertTrue(locked);
        assertTrue(decision.shouldShowBlockScreen(locked, "com.android.chrome"));
        assertTrue(decision.shouldShowBlockScreen(locked, "com.sec.android.app.launcher"));
        assertFalse(decision.shouldShowBlockScreen(locked, PolicyDecision.WHATSAPP));
        assertFalse(decision.shouldShowBlockScreen(locked, PolicyDecision.SAMSUNG_CAMERA));
    }

    @Test
    public void aSubmittedChallengeStillBlocksUntilAParentApproves() {
        // Sending the photo does not unlock anything: the allowed apps stay
        // usable and everything else stays covered until the challenge is gone.
        Policy policy = challengePolicy(null, Challenge.STATUS_SUBMITTED);
        boolean locked = policy.isLocked();

        assertTrue(locked);
        assertTrue(decision.shouldShowBlockScreen(locked, "com.android.chrome"));
        assertFalse(decision.shouldShowBlockScreen(locked, PolicyDecision.WHATSAPP));
    }

    @Test
    public void aParentOverrideSuppressesTheChallengeBlock() {
        Policy policy = challengePolicy("2099-01-01T00:05:00Z");
        boolean locked = policy.isLocked();

        assertFalse(locked);
        assertFalse(decision.shouldShowBlockScreen(locked, "com.android.chrome"));
    }

    private static Policy challengePolicy(String extensionUntil) {
        return challengePolicy(extensionUntil, Challenge.STATUS_PENDING);
    }

    private static Policy challengePolicy(String extensionUntil, String status) {
        return new Policy(
                false, false, "Paused", false, null, extensionUntil,
                null, 0, null, Collections.emptyList(), null, null,
                new Challenge("abc123", "I have brushed my teeth", null, status, null));
    }
}
