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
    public void aNewChallengeIdNeedsAnAnswerAndAnAcceptedOneDoesNot() {
        assertTrue(Challenge.needsAnswer("abc123", ""));
        assertTrue(Challenge.needsAnswer("abc123", "older"));
        assertFalse(Challenge.needsAnswer("abc123", "abc123"));
        assertFalse(Challenge.needsAnswer("", ""));
        assertFalse(Challenge.needsAnswer(null, "abc123"));
    }

    @Test
    public void theHighlightedStepFollowsTheChildsProgress() {
        assertEquals(Challenge.STEP_PHRASE, Challenge.step(false, false));
        assertEquals(Challenge.STEP_PHRASE, Challenge.step(false, true));
        assertEquals(Challenge.STEP_PHOTO, Challenge.step(true, false));
        assertEquals(Challenge.STEP_SEND, Challenge.step(true, true));
    }

    @Test
    public void acceptNeedsBothThePhraseAndThePhoto() {
        assertFalse(Challenge.canAccept(false, false));
        assertFalse(Challenge.canAccept(true, false));
        assertFalse(Challenge.canAccept(false, true));
        assertTrue(Challenge.canAccept(true, true));
    }
}
