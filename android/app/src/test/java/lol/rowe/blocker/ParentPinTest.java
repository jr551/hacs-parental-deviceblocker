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
    public void storedPinMustMatchItsSaltedDigest() {
        String salt = "abcd";
        String expected = ParentPin.digest("6789", salt);
        assertTrue(ParentPin.verifyStored(salt, expected, "6789"));
        assertFalse(ParentPin.verifyStored(salt, expected, "9876"));
    }
}
