package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ChallengeTest {
    @Test
    public void theAnswerMatchesAfterTrimmingAndCaseFolding() {
        assertTrue(Challenge.matches("I will do my chores", "I will do my chores"));
        assertTrue(Challenge.matches("I will do my chores", "  i WILL do my CHORES  "));
        assertTrue(Challenge.matches("  I will do my chores  ", "I will do my chores"));
    }

    @Test
    public void theLiveChallengePhraseMatchesWhateverCaseTheChildTypes() {
        String phrase = "I have brushed my teeth";

        assertTrue(Challenge.matches(phrase, "I have brushed my teeth"));
        assertTrue(Challenge.matches(phrase, "i have brushed my teeth"));
        assertTrue(Challenge.matches(phrase, "I HAVE BRUSHED MY TEETH"));
        assertTrue(Challenge.matches(phrase, "  i HaVe BrUsHeD mY tEeTh  "));
        assertTrue(Challenge.matches(phrase, "\tI have brushed my teeth\n"));

        assertFalse(Challenge.matches(phrase, "I have brushed my teef"));
        assertFalse(Challenge.matches(phrase, "I have brushed my teeth already"));
        assertFalse(Challenge.matches(phrase, "have brushed my teeth"));
    }

    @Test
    public void nearMissesAreRejected() {
        assertFalse(Challenge.matches("I will do my chores", "I will do my chore"));
        assertFalse(Challenge.matches("I will do my chores", "I will do my chores!"));
        assertFalse(Challenge.matches("I will do my chores", "I will do my chores now"));
        assertFalse(Challenge.matches("I will do my chores", ""));
        assertFalse(Challenge.matches("I will do my chores", null));
        assertFalse(Challenge.matches("", ""));
        assertFalse(Challenge.matches(null, "I will do my chores"));
    }

    @Test
    public void theHighlightedStepFollowsTheChildsProgress() {
        assertEquals(Challenge.STEP_PHRASE, Challenge.step(false, false));
        assertEquals(Challenge.STEP_PHRASE, Challenge.step(false, true));
        assertEquals(Challenge.STEP_PHOTO, Challenge.step(true, false));
        assertEquals(Challenge.STEP_SEND, Challenge.step(true, true));
    }

    @Test
    public void sendingNeedsBothThePhraseAndThePhoto() {
        assertFalse(Challenge.canSubmit(false, false));
        assertFalse(Challenge.canSubmit(true, false));
        assertFalse(Challenge.canSubmit(false, true));
        assertTrue(Challenge.canSubmit(true, true));
    }

    @Test
    public void aPendingChallengeShowsTheForm() {
        assertEquals(Challenge.PANEL_FORM, Challenge.panelState(Challenge.STATUS_PENDING, true));
        // An older Home Assistant sends no status at all; it still gets the form.
        assertEquals(Challenge.PANEL_FORM, Challenge.panelState(null, true));
        assertEquals(Challenge.PANEL_FORM, Challenge.panelState("", true));
        assertEquals(Challenge.PANEL_FORM, Challenge.panelState("something else", true));
    }

    @Test
    public void aSubmittedChallengeShowsTheWait() {
        assertEquals(Challenge.PANEL_WAITING, Challenge.panelState(Challenge.STATUS_SUBMITTED, true));
        assertEquals(Challenge.PANEL_WAITING, Challenge.panelState(" submitted ", true));
    }

    @Test
    public void noChallengeAtAllMeansThePhoneIsUnlocked() {
        assertEquals(Challenge.PANEL_UNLOCKED, Challenge.panelState(null, false));
        assertEquals(Challenge.PANEL_UNLOCKED, Challenge.panelState("", false));
        assertEquals(Challenge.PANEL_UNLOCKED, Challenge.panelState(Challenge.STATUS_PENDING, false));
        assertEquals(Challenge.PANEL_UNLOCKED, Challenge.panelState(Challenge.STATUS_SUBMITTED, false));
    }

    @Test
    public void aChallengeSentBackReturnsToTheForm() {
        // A parent rejected the photo: the status goes back to pending with the
        // evidence gone, so the panel must show the form again, not the wait.
        assertEquals(Challenge.PANEL_FORM, Challenge.panelState(Challenge.STATUS_PENDING, true));
        assertEquals(
                Challenge.TRANSITION_REJECTED,
                Challenge.statusTransition(Challenge.STATUS_SUBMITTED, Challenge.STATUS_PENDING));
    }

    @Test
    public void everyOtherStatusChangeIsNotARejection() {
        assertEquals(
                Challenge.TRANSITION_NONE,
                Challenge.statusTransition(Challenge.STATUS_PENDING, Challenge.STATUS_PENDING));
        assertEquals(
                Challenge.TRANSITION_NONE,
                Challenge.statusTransition(Challenge.STATUS_PENDING, Challenge.STATUS_SUBMITTED));
        assertEquals(
                Challenge.TRANSITION_NONE,
                Challenge.statusTransition(Challenge.STATUS_SUBMITTED, Challenge.STATUS_SUBMITTED));
        assertEquals(
                Challenge.TRANSITION_NONE,
                Challenge.statusTransition(null, Challenge.STATUS_PENDING));
    }

    @Test
    public void theWaitingViewCountsTheHourDown() {
        assertEquals("60:00 left", Challenge.countdownText(0));
        assertEquals("59:59 left", Challenge.countdownText(1));
        assertEquals("1:00 left", Challenge.countdownText(3540));
        assertEquals("0:01 left", Challenge.countdownText(3599));
        assertEquals("A parent still needs to approve this.", Challenge.countdownText(3600));
        assertEquals("A parent still needs to approve this.", Challenge.countdownText(7200));
        assertEquals("60:00 left", Challenge.countdownText(-5));
    }

    @Test
    public void theSubmissionTimeIsReadFromAnIsoTimestamp() {
        assertEquals(0L, Challenge.epochMillis(null));
        assertEquals(0L, Challenge.epochMillis(""));
        assertEquals(0L, Challenge.epochMillis("not a time"));
        assertEquals(
                java.time.Instant.parse("2026-10-17T12:00:00Z").toEpochMilli(),
                Challenge.epochMillis("2026-10-17T12:00:00Z"));
    }

    @Test
    public void aMissingStatusReadsAsPending() {
        assertEquals(Challenge.STATUS_PENDING, Challenge.normalizeStatus(null));
        assertEquals(Challenge.STATUS_PENDING, Challenge.normalizeStatus(""));
        assertEquals(Challenge.STATUS_PENDING, Challenge.normalizeStatus("approved"));
        assertEquals(Challenge.STATUS_SUBMITTED, Challenge.normalizeStatus(" submitted "));
    }
}
