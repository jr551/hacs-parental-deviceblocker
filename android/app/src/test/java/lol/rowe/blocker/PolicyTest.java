package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class PolicyTest {
    @Test
    public void initialGraceWarnsWithoutCapturingTheForeground() {
        // The grace window shows the warning once ("save what you are doing") but must
        // not pin the screen — otherwise the advice is impossible to follow.
        Policy policy = new Policy(
                false, true, "Pause soon", true,
                "2099-01-01T00:00:00Z", null);

        assertFalse(policy.blocked);
        assertFalse(policy.shouldCaptureForeground());
        assertTrue(policy.shouldWarnGrace());
        assertTrue(policy.extensionAvailable);
    }

    @Test
    public void staleGraceDeadlineFailsOpenWithoutAFreshBlockedResponse() {
        Policy policy = new Policy(
                false, true, "Paused", false,
                "2000-01-01T00:00:00Z", null);

        assertFalse(policy.shouldCaptureForeground());
        assertFalse(policy.shouldWarnGrace());
    }

    @Test
    public void incompleteRequestedBlockFailsOpen() {
        Policy policy = new Policy(
                false, true, "Paused", false,
                null, null);

        assertFalse(policy.shouldCaptureForeground());
        assertFalse(policy.shouldWarnGrace());
    }

    @Test
    public void activeExtensionReleasesTheForeground() {
        Policy policy = new Policy(
                false, true, "Pause later", false,
                "2099-01-01T00:05:00Z", "2099-01-01T00:05:00Z");

        assertTrue(policy.isExtensionActive());
        assertFalse(policy.shouldCaptureForeground());
    }

    @Test
    public void effectiveBlockOverridesAStaleExtension() {
        // Clock skew can leave extension_until in the future locally after HA has
        // already flipped to an effective block; the block must still capture.
        Policy policy = new Policy(
                true, true, "Paused", false,
                null, "2099-01-01T00:00:00Z");

        assertTrue(policy.isExtensionActive());
        assertTrue(policy.shouldCaptureForeground());
    }

    @Test
    public void effectiveBlockCapturesTheForeground() {
        Policy policy = new Policy(
                true, true, "Paused", false,
                "2000-01-01T00:00:00Z", null);

        assertTrue(policy.blocked);
        assertTrue(policy.shouldCaptureForeground());
    }
}
