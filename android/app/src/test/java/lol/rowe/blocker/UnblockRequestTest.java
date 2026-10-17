package lol.rowe.blocker;

import org.junit.Test;

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
    public void allowsOneRequestPerAppPerTenMinutes() {
        long now = 1_700_000_000_000L;
        assertTrue(UnblockRequest.mayRequest(0L, now));
        assertFalse(UnblockRequest.mayRequest(now, now));
        assertFalse(UnblockRequest.mayRequest(now, now + UnblockRequest.THROTTLE_MS - 1));
        assertTrue(UnblockRequest.mayRequest(now, now + UnblockRequest.THROTTLE_MS));
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
    public void keepsTheButtonForARequestHomeAssistantRefused() {
        UnblockRequest.Outcome outcome = UnblockRequest.fromResponse(500, "", "Roblox");
        assertFalse(outcome.accepted);
        assertEquals("Home Assistant refused the request (500).", outcome.message);
    }
}
