package lol.rowe.blocker;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public final class InstalledAppsTest {
    private static InstalledApps.App app(String packageName, String label) {
        return new InstalledApps.App(packageName, label);
    }

    @Test
    public void theFingerprintIgnoresQueryOrder() {
        List<InstalledApps.App> first = Arrays.asList(
                app("com.whatsapp", "WhatsApp"),
                app("com.roblox.client", "Roblox"),
                app("com.android.chrome", "Chrome"));
        List<InstalledApps.App> second = Arrays.asList(
                app("com.android.chrome", "Chrome"),
                app("com.whatsapp", "WhatsApp"),
                app("com.roblox.client", "Roblox"));

        assertEquals(InstalledApps.fingerprint(first), InstalledApps.fingerprint(second));
    }

    @Test
    public void aNewOrRemovedAppChangesTheFingerprint() {
        String before = InstalledApps.fingerprint(Arrays.asList(
                app("com.whatsapp", "WhatsApp"),
                app("com.roblox.client", "Roblox")));
        String after = InstalledApps.fingerprint(Collections.singletonList(
                app("com.whatsapp", "WhatsApp")));

        assertNotEquals(before, after);
    }

    @Test
    public void aRelabelledAppChangesTheFingerprint() {
        String before = InstalledApps.fingerprint(
                Collections.singletonList(app("com.roblox.client", "Roblox")));
        String after = InstalledApps.fingerprint(
                Collections.singletonList(app("com.roblox.client", "Roblox Beta")));

        assertNotEquals(before, after);
    }

    @Test
    public void onlyAChangedSetIsSent() {
        String current = InstalledApps.fingerprint(
                Collections.singletonList(app("com.whatsapp", "WhatsApp")));

        assertTrue(InstalledApps.shouldSend("", current));
        assertFalse(InstalledApps.shouldSend(current, current));
        assertTrue(InstalledApps.shouldSend("something else", current));
    }

    @Test
    public void aMissingFingerprintOnEitherSideIsNotAChange() {
        assertFalse(InstalledApps.shouldSend(null, ""));
        assertFalse(InstalledApps.shouldSend("", null));
        assertFalse(InstalledApps.shouldSend(null, null));
    }
}
