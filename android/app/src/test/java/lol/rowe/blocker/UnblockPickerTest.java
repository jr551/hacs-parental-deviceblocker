package lol.rowe.blocker;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnblockPickerTest {
    private static final String OWN = "lol.rowe.blocker.debug";

    @Test
    public void thePickerLeavesOutThisAppAndSortsByLabel() {
        List<InstalledApps.App> installed = Arrays.asList(
                new InstalledApps.App("com.roblox.client", "Roblox"),
                new InstalledApps.App(OWN, "Android Settings"),
                new InstalledApps.App("com.whatsapp", "WhatsApp"),
                new InstalledApps.App("com.google.android.apps.maps", "Maps"));

        List<InstalledApps.App> choices = UnblockPicker.choices(installed, OWN);

        assertEquals(3, choices.size());
        assertEquals("Maps", choices.get(0).label);
        assertEquals("Roblox", choices.get(1).label);
        assertEquals("WhatsApp", choices.get(2).label);
        for (InstalledApps.App app : choices) {
            assertFalse(OWN.equals(app.packageName));
        }
    }

    @Test
    public void thePickerSortsLabelsCaseInsensitivelyAndKeepsTiesStable() {
        List<InstalledApps.App> installed = Arrays.asList(
                new InstalledApps.App("com.b.app", "beta"),
                new InstalledApps.App("com.a.app", "Alpha"),
                new InstalledApps.App("com.c.app", "beta"));

        List<InstalledApps.App> choices = UnblockPicker.choices(installed, OWN);

        assertEquals("Alpha", choices.get(0).label);
        assertEquals("beta", choices.get(1).label);
        assertEquals("com.b.app", choices.get(1).packageName);
        assertEquals("com.c.app", choices.get(2).packageName);
    }

    @Test
    public void anEmptyOrMissingListGivesNoChoices() {
        assertTrue(UnblockPicker.choices(Collections.emptyList(), OWN).isEmpty());
        assertTrue(UnblockPicker.choices(null, OWN).isEmpty());
        assertTrue(UnblockPicker.choices(
                Collections.singletonList(new InstalledApps.App("", "")), OWN).isEmpty());
    }

    @Test
    public void theButtonIsDisabledAtTheCapAndEnabledBelowIt() {
        assertFalse(UnblockPicker.canRequest(policy(new Policy.RequestBudget(3, 3))));
        assertFalse(UnblockPicker.canRequest(policy(new Policy.RequestBudget(4, 3))));
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(2, 3))));
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(0, 3))));
    }

    @Test
    public void aMissingCountLeavesTheButtonEnabled() {
        // An older Home Assistant sends neither half of the budget.
        assertTrue(UnblockPicker.canRequest(policy(Policy.RequestBudget.NONE)));
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(null, 3))));
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(3, null))));
    }

    @Test
    public void aCapOfZeroIsNotACap() {
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(0, 0))));
    }

    private static Policy policy(Policy.RequestBudget budget) {
        return new Policy(
                false,
                false,
                "Paused",
                false,
                null,
                null,
                null,
                0,
                null,
                new ArrayList<>(),
                null,
                null,
                null,
                null,
                budget);
    }
}
