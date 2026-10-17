package lol.rowe.blocker;

import org.junit.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnblockPickerTest {
    private static final String OWN = "lol.rowe.blocker.debug";
    /** A fixed instant, so the clock-dependent assertions do not drift. */
    private static final long NOW = 1_700_000_000_000L;

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
        assertFalse(UnblockPicker.canRequest(policy(new Policy.RequestBudget(3, 3)), 0L, NOW));
        assertFalse(UnblockPicker.canRequest(policy(new Policy.RequestBudget(4, 3)), 0L, NOW));
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(2, 3)), 0L, NOW));
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(0, 3)), 0L, NOW));
    }

    @Test
    public void aMissingCountLeavesTheButtonEnabled() {
        // An older Home Assistant sends neither half of the budget.
        assertTrue(UnblockPicker.canRequest(policy(Policy.RequestBudget.NONE), 0L, NOW));
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(null, 3)), 0L, NOW));
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(3, null)), 0L, NOW));
    }

    @Test
    public void aCapOfZeroIsNotACap() {
        assertTrue(UnblockPicker.canRequest(policy(new Policy.RequestBudget(0, 0)), 0L, NOW));
    }

    @Test
    public void homeAssistantsDailyFlagClosesThePicker() {
        Policy policy = policy(
                Policy.RequestBudget.NONE,
                Boolean.FALSE,
                Instant.ofEpochMilli(NOW).plus(Duration.ofMinutes(90)).toString());

        // The payload wins even when this phone's own clock says it may ask.
        assertFalse(UnblockPicker.allowedToday(policy, 0L, NOW));
        assertFalse(UnblockPicker.canRequest(policy, 0L, NOW));
        assertTrue(UnblockPicker.closedReason(policy, 0L, NOW).contains("You can ask again"));
    }

    @Test
    public void thePayloadFlagWinsOverTheLocalClock() {
        Policy policy = policy(Policy.RequestBudget.NONE, Boolean.TRUE, null);

        // Asked a minute ago by the phone's own clock, but Home Assistant is
        // the authority and says another request is allowed.
        assertTrue(UnblockPicker.allowedToday(policy, NOW, NOW + 60_000L));
        assertTrue(UnblockPicker.canRequest(policy, NOW, NOW + 60_000L));
        assertEquals("", UnblockPicker.closedReason(policy, NOW, NOW + 60_000L));
    }

    @Test
    public void withoutAFlagTheLocalDayIsTheFallback() {
        Policy policy = policy(Policy.RequestBudget.NONE);
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long late = today.atTime(23, 59).atZone(zone).toInstant().toEpochMilli();
        long afterMidnight = today.plusDays(1).atTime(0, 1).atZone(zone).toInstant().toEpochMilli();

        assertFalse(UnblockPicker.allowedToday(policy, late, late));
        assertFalse(UnblockPicker.canRequest(policy, late, late));
        assertTrue(UnblockPicker.allowedToday(policy, late, afterMidnight));
        assertTrue(UnblockPicker.canRequest(policy, late, afterMidnight));
    }

    @Test
    public void saysWhenTheNextRequestOpens() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate today = LocalDate.now(zone);
        long now = today.atTime(9, 0).atZone(zone).toInstant().toEpochMilli();

        assertEquals("after 23:30", UnblockPicker.nextRequestText(
                policy(
                        Policy.RequestBudget.NONE,
                        Boolean.FALSE,
                        today.atTime(23, 30).atZone(zone).toInstant().toString()),
                now));
        assertEquals("tomorrow", UnblockPicker.nextRequestText(
                policy(
                        Policy.RequestBudget.NONE,
                        Boolean.FALSE,
                        today.plusDays(1).atStartOfDay(zone).toInstant().toString()),
                now));
        // A stale timestamp from a phone or server with a wrong clock cannot be
        // read as "later today"; the next request is simply the next day.
        assertEquals("tomorrow", UnblockPicker.nextRequestText(
                policy(Policy.RequestBudget.NONE, Boolean.FALSE, "2000-01-01T00:00:00Z"),
                now));
    }

    private static Policy policy(Policy.RequestBudget budget) {
        return policy(budget, null, null);
    }

    private static Policy policy(
            Policy.RequestBudget budget, Boolean allowedToday, String nextRequestAt) {
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
                budget,
                null,
                allowedToday,
                nextRequestAt);
    }
}
