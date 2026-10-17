package lol.rowe.blocker;

import org.junit.Test;

import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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

        Policy failed = Policy.enforced(blocked, true, false, null);
        assertFalse(failed.blocked);
        assertFalse(failed.shouldCaptureForeground());
        assertFalse(Policy.enforced(null, false, false, null).blocked);
    }

    @Test
    public void aLocalOverrideWinsOverABlockedPolicy() {
        Policy blocked = new Policy(
                true, true, "Paused", false,
                "2000-01-01T00:00:00Z", null);

        assertFalse(Policy.enforced(blocked, false, true, null).blocked);
        assertTrue(Policy.enforced(blocked, false, false, null).blocked);
    }

    @Test
    public void aHeldChallengeSurvivesAFailedPoll() {
        Challenge held = new Challenge("abc123", "I have brushed my teeth", null);

        Policy failed = Policy.enforced(null, true, false, held);
        assertFalse(failed.blocked);
        assertTrue(failed.challengePending());
        assertTrue(failed.isLocked());

        // A parent override still releases it.
        assertFalse(Policy.enforced(null, true, true, held).isLocked());
    }

    @Test
    public void aHeldSubmittedChallengeSurvivesAFailedPoll() {
        Challenge held = new Challenge(
                "abc123", "I have brushed my teeth", null,
                Challenge.STATUS_SUBMITTED, "2026-10-17T12:00:00Z");

        Policy failed = Policy.enforced(null, true, false, held);

        assertTrue(failed.challengePending());
        assertTrue(failed.isLocked());
        assertSame(held, failed.challenge);
        assertEquals(Challenge.STATUS_SUBMITTED, failed.challenge.status);
    }

    @Test
    public void aSuccessfulPollWithoutAChallengeDropsTheHeldOne() {
        Challenge held = new Challenge("abc123", "I have brushed my teeth", null);
        Policy fromServer = new Policy(false, false, "Available", false, null, null);

        Policy enforced = Policy.enforced(fromServer, false, false, held);

        assertNull(enforced.challenge);
        assertFalse(enforced.isLocked());
    }

    @Test
    public void aSuccessfulPollKeepsTheChallengeItCarries() {
        Challenge fromServer = new Challenge("abc123", "I have brushed my teeth", null);
        Policy policy = new Policy(
                false, false, "Available", false, null, null,
                null, 0, null, Collections.emptyList(), null, null, fromServer);

        Policy enforced = Policy.enforced(policy, false, false, null);

        assertSame(fromServer, enforced.challenge);
        assertTrue(enforced.isLocked());
    }

    @Test
    public void anUnapprovedChallengeLocksThePhoneLikeABlock() {
        Policy policy = challengePolicy(null);

        assertFalse(policy.blocked);
        assertTrue(policy.challengePending());
        assertTrue(policy.isLocked());
    }

    @Test
    public void aSubmittedChallengeKeepsTheLockUntilAParentApproves() {
        // The phone never accepts its own challenge: sending the photo leaves
        // the lock up, and only a payload without the challenge lifts it.
        Policy policy = challengePolicy(null, Challenge.STATUS_SUBMITTED);

        assertTrue(policy.challengePending());
        assertTrue(policy.isLocked());
    }

    @Test
    public void aParentOverrideSuppressesTheChallenge() {
        Policy policy = challengePolicy("2099-01-01T00:05:00Z");

        assertTrue(policy.isExtensionActive());
        assertFalse(policy.challengePending());
        assertFalse(policy.isLocked());
    }

    @Test
    public void aBlockStillLocksWhileTheChallengeWaitsForApproval() {
        Policy policy = new Policy(
                true, true, "Paused", false, null, null,
                null, 0, null, Collections.emptyList(), null, null,
                new Challenge(
                        "abc123", "I have brushed my teeth", null,
                        Challenge.STATUS_SUBMITTED, "2026-10-17T12:00:00Z"));

        assertTrue(policy.challengePending());
        assertTrue(policy.isLocked());
    }

    @Test
    public void aPolicyWithoutAChallengeNeverLocks() {
        Policy policy = new Policy(false, false, "Available", false, null, null);

        assertFalse(policy.challengePending());
        assertFalse(policy.isLocked());
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
