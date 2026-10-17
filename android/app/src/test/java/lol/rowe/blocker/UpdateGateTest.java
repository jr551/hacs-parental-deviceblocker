package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateGateTest {
    @Test
    public void newerReleaseVersionsCompareByNumber() {
        assertTrue(UpdateGate.isNewer("0.4.8", "0.4.9"));
        assertFalse(UpdateGate.isNewer("0.4.9", "0.4.9"));
        assertFalse(UpdateGate.isNewer("0.4.9", "0.4.8"));
        assertTrue(UpdateGate.isNewer("0.4.9", "0.5.0"));
    }
}
