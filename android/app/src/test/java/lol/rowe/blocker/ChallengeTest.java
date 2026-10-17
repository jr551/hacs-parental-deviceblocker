package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class ChallengeTest {
    @Test
    public void theAnswerMatchesAfterTrimmingAndCaseFolding() {
        assertTrue(Challenge.matches("I will do my chores", "I will do my chores"));
        assertTrue(Challenge.matches("I will do my chores", "  i WILL do my CHORES  "));
        assertTrue(Challenge.matches("  I will do my chores  ", "I will do my chores"));
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
    public void acceptNeedsBothThePhraseAndThePhoto() {
        assertFalse(Challenge.canAccept(false, false));
        assertFalse(Challenge.canAccept(true, false));
        assertFalse(Challenge.canAccept(false, true));
        assertTrue(Challenge.canAccept(true, true));
    }

    @Test
    public void aLargePhotoIsScaledDownToTheUploadEdge() {
        assertArrayEquals(
                new int[] {1600, 1200},
                Challenge.uploadSize(4000, 3000, Challenge.MAX_UPLOAD_EDGE));
        assertArrayEquals(
                new int[] {1200, 1600},
                Challenge.uploadSize(3000, 4000, Challenge.MAX_UPLOAD_EDGE));
    }

    @Test
    public void aSmallEnoughPhotoIsUploadedAsItIs() {
        assertNull(Challenge.uploadSize(1600, 1200, Challenge.MAX_UPLOAD_EDGE));
        assertNull(Challenge.uploadSize(800, 600, Challenge.MAX_UPLOAD_EDGE));
        assertNull(Challenge.uploadSize(0, 600, Challenge.MAX_UPLOAD_EDGE));
        assertNull(Challenge.uploadSize(4000, 3000, 0));
    }

    @Test
    public void theScaledPhotoKeepsItsAspectRatio() {
        int[] size = Challenge.uploadSize(4032, 3024, Challenge.MAX_UPLOAD_EDGE);
        assertArrayEquals(new int[] {1600, 1200}, size);
        assertTrue(Math.abs((double) size[0] / size[1] - (double) 4032 / 3024) < 0.01);
    }
}
