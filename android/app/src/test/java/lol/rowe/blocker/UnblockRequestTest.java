package lol.rowe.blocker;

import org.junit.Test;

import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UnblockRequestTest {
    @Test
    public void showsTheAppLabelWhenThePackageManagerHasOne() {
        assertEquals("Roblox", UnblockRequest.label("com.roblox.client", "Roblox"));
        assertEquals("Roblox", UnblockRequest.label("com.roblox.client", "  Roblox  "));
    }

    @Test
    public void fallsBackToThePackageNameWhenThereIsNoLabel() {
        assertEquals("com.roblox.client", UnblockRequest.label("com.roblox.client", null));
        assertEquals("com.roblox.client", UnblockRequest.label("com.roblox.client", ""));
        assertEquals("com.roblox.client", UnblockRequest.label("com.roblox.client", "   "));
    }

    @Test
    public void allowsOneRequestPerDevicePerLocalDay() {
        LocalDate today = LocalDate.now(ZoneId.systemDefault());
        long morning = at(today, 9, 0);
        long evening = at(today, 21, 0);
        long late = at(today, 23, 59);
        long afterMidnight = at(today.plusDays(1), 0, 1);

        assertTrue(UnblockRequest.mayRequest(0L, morning));
        assertFalse(UnblockRequest.mayRequest(morning, evening));
        assertFalse(UnblockRequest.mayRequest(late, late));
        // The boundary is the calendar day, not a rolling twenty-four hours:
        // a request at 23:59 is followed by a fresh one two minutes later.
        assertTrue(UnblockRequest.mayRequest(late, afterMidnight));
        assertFalse(UnblockRequest.mayRequest(
                afterMidnight, at(today.plusDays(1), 23, 59)));
    }

    @Test
    public void treatsAnAcceptedRequestAsWithTheParent() {
        UnblockRequest.Outcome outcome = UnblockRequest.fromResponse(200, "", "Roblox");
        assertTrue(outcome.accepted);
        assertEquals("Asked a parent to allow Roblox.", outcome.message);
    }

    @Test
    public void relaysHomeAssistantsReasonForAConflict() {
        UnblockRequest.Outcome outcome = UnblockRequest.fromResponse(
                409, "That app is already allowed.", "Roblox");
        assertTrue(outcome.accepted);
        assertEquals("That app is already allowed.", outcome.message);
    }

    @Test
    public void fallsBackToItsOwnMessageWhenAConflictHasNoReason() {
        UnblockRequest.Outcome outcome = UnblockRequest.fromResponse(409, "", "Roblox");
        assertTrue(outcome.accepted);
        assertEquals("A parent has already been asked to allow Roblox.", outcome.message);
    }

    @Test
    public void relaysHomeAssistantsReasonForTheDailyLimit() {
        UnblockRequest.Outcome outcome = UnblockRequest.fromResponse(
                429, "One unblock request a day is enough.", "Roblox");
        assertFalse(outcome.accepted);
        assertEquals("One unblock request a day is enough.", outcome.message);
    }

    @Test
    public void explainsTheDailyLimitWhenA429HasNoReason() {
        UnblockRequest.Outcome outcome = UnblockRequest.fromResponse(429, "", "Roblox");
        assertFalse(outcome.accepted);
        assertEquals(
                "A parent has already been asked today. You can ask again tomorrow.",
                outcome.message);
    }

    @Test
    public void keepsTheButtonForARequestHomeAssistantRefused() {
        UnblockRequest.Outcome outcome = UnblockRequest.fromResponse(500, "", "Roblox");
        assertFalse(outcome.accepted);
        assertEquals("Home Assistant refused the request (500).", outcome.message);
    }

    private static long at(LocalDate date, int hour, int minute) {
        return date.atTime(hour, minute)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }
}
