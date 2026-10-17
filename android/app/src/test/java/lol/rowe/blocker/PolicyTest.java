package lol.rowe.blocker;

import org.junit.Test;

import java.util.Collections;

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

    @Test
    public void aFailedPollLeavesTheDeviceUnblocked() {
        Policy blocked = new Policy(
                true, true, "Paused", false,
                "2000-01-01T00:00:00Z", null);

        Policy failed = Policy.enforced(blocked, true, false);
        assertFalse(failed.blocked);
        assertFalse(failed.shouldCaptureForeground());
        assertFalse(Policy.enforced(null, false, false).blocked);
    }

    @Test
    public void aLocalOverrideWinsOverABlockedPolicy() {
        Policy blocked = new Policy(
                true, true, "Paused", false,
                "2000-01-01T00:00:00Z", null);

        assertFalse(Policy.enforced(blocked, false, true).blocked);
        assertTrue(Policy.enforced(blocked, false, false).blocked);
    }

    @Test
    public void anUnansweredChallengeLocksThePhoneLikeABlock() {
        Policy policy = challengePolicy(null);

        assertFalse(policy.blocked);
        assertTrue(policy.challengePending(""));
        assertTrue(policy.isLocked(""));
    }

    @Test
    public void anAcceptedChallengeDoesNotLock() {
        Policy policy = challengePolicy(null);

        assertFalse(policy.challengePending("abc123"));
        assertFalse(policy.isLocked("abc123"));
    }

    @Test
    public void aParentOverrideSuppressesTheChallenge() {
        Policy policy = challengePolicy("2099-01-01T00:05:00Z");

        assertTrue(policy.isExtensionActive());
        assertFalse(policy.challengePending(""));
        assertFalse(policy.isLocked(""));
    }

    @Test
    public void aBlockStillLocksWhileTheChallengeIsAnswered() {
        Policy policy = new Policy(
                true, true, "Paused", false, null, null,
                null, 0, null, Collections.emptyList(), null, null,
                new Challenge("abc123", "I have brushed my teeth", null));

        assertFalse(policy.challengePending("abc123"));
        assertTrue(policy.isLocked("abc123"));
    }

    @Test
    public void aPolicyWithoutAChallengeNeverLocks() {
        Policy policy = new Policy(false, false, "Available", false, null, null);

        assertFalse(policy.challengePending(""));
        assertFalse(policy.isLocked(""));
    }

    private static Policy challengePolicy(String extensionUntil) {
        return new Policy(
                false, false, "Paused", false, null, extensionUntil,
                null, 0, null, Collections.emptyList(), null, null,
                new Challenge("abc123", "I have brushed my teeth", null));
    }
}
