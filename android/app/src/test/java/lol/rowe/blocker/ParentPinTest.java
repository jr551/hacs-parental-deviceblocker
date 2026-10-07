package lol.rowe.blocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ParentPinTest {

    @Test
    public void digestIsDeterministicForTheSameSalt() {
        assertEquals(ParentPin.digest("1234", "abcd"), ParentPin.digest("1234", "abcd"));
    }

    @Test
    public void digestDependsOnBothPinAndSalt() {
        assertNotEquals(ParentPin.digest("1234", "abcd"), ParentPin.digest("4321", "abcd"));
        assertNotEquals(ParentPin.digest("1234", "abcd"), ParentPin.digest("1234", "dcba"));
    }

    @Test
    public void digestDoesNotLeakThePin() {
        String digest = ParentPin.digest("13571357", "abcd");
        assertFalse(digest.contains("13571357"));
        assertEquals(64, digest.length());
    }

    @Test
    public void constantTimeEqualsMatchesOnlyIdenticalValues() {
        assertTrue(ParentPin.constantTimeEquals("abc123", "abc123"));
        assertFalse(ParentPin.constantTimeEquals("abc123", "abc124"));
        assertFalse(ParentPin.constantTimeEquals("abc123", "abc1234"));
    }

    @Test
    public void unsetPinHasNoUniversalFallback() {
        assertFalse(ParentPin.verifyStored("", "", "0000"));
        assertFalse(ParentPin.verifyStored("salt", "", "1234"));
    }

    @Test
    public void lockoutStartsAfterTwoFailuresAndLastsFifteenMinutes() {
        assertEquals(0L, ParentPin.lockoutDeadline(1, 1_000L));
        long deadline = ParentPin.lockoutDeadline(ParentPin.MAX_ATTEMPTS, 1_000L);
        assertEquals(1_000L + ParentPin.LOCKOUT_MILLIS, deadline);
        assertEquals(ParentPin.LOCKOUT_MILLIS, ParentPin.remainingMillis(deadline, 1_000L));
        assertEquals(0L, ParentPin.remainingMillis(deadline, deadline));
        assertEquals(15, ParentPin.wholeMinutesRemaining(ParentPin.LOCKOUT_MILLIS));
        assertEquals(1, ParentPin.wholeMinutesRemaining(1L));
        assertEquals(0, ParentPin.wholeMinutesRemaining(0L));
    }

    @Test
    public void storedPinMustMatchItsSaltedDigest() {
        String salt = "abcd";
        String expected = ParentPin.digest("6789", salt);
        assertTrue(ParentPin.verifyStored(salt, expected, "6789"));
        assertFalse(ParentPin.verifyStored(salt, expected, "9876"));
    }
}
