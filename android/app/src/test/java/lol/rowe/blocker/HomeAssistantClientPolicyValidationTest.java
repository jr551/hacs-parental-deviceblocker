package lol.rowe.blocker;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.assertThrows;

public class HomeAssistantClientPolicyValidationTest {
    @Test
    public void acceptsCompleteUnblockedGraceAndBlockedPolicies() throws Exception {
        HomeAssistantClient.validatePolicy(false, false, null, null);
        HomeAssistantClient.validatePolicy(
                false, true, "2099-01-01T00:00:00Z", null);
        HomeAssistantClient.validatePolicy(
                true, true, "2000-01-01T00:00:00Z", null);
    }

    @Test
    public void rejectsContradictoryOrIncompletePolicies() {
        assertThrows(
                IOException.class,
                () -> HomeAssistantClient.validatePolicy(true, false, null, null));
        assertThrows(
                IOException.class,
                () -> HomeAssistantClient.validatePolicy(false, true, null, null));
        assertThrows(
                IOException.class,
                () -> HomeAssistantClient.validatePolicy(
                        false, false, null, "2099-01-01T00:00:00Z"));
    }

    @Test
    public void rejectsStaleGraceAndBlockDuringActiveExtension() {
        assertThrows(
                IOException.class,
                () -> HomeAssistantClient.validatePolicy(
                        false, true, "2000-01-01T00:00:00Z", null));
        assertThrows(
                IOException.class,
                () -> HomeAssistantClient.validatePolicy(
                        true,
                        true,
                        "2000-01-01T00:00:00Z",
                        "2099-01-01T00:00:00Z"));
    }
}
