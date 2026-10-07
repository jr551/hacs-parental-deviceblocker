package lol.rowe.blocker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class EnforcementStatusTest {
    @Test
    public void degradedStatesAreTheOnesParentsMustSee() {
        assertTrue(EnforcementStatus.isDegraded(EnforcementStatus.FAIL_OPEN));
        assertTrue(EnforcementStatus.isDegraded(EnforcementStatus.ACCESSIBILITY_DISABLED));
        assertTrue(EnforcementStatus.isDegraded(EnforcementStatus.ACCESSIBILITY_INTERRUPTED));
        assertFalse(EnforcementStatus.isDegraded(EnforcementStatus.MONITORING));
        assertFalse(EnforcementStatus.isDegraded(EnforcementStatus.ENFORCING));
        assertFalse(EnforcementStatus.isDegraded(EnforcementStatus.NOT_CONFIGURED));
    }

    @Test
    public void parentTextNamesFailOpenWithoutAFactoryPin() {
        String text = EnforcementStatus.parentText(EnforcementStatus.FAIL_OPEN, "network");
        assertTrue(text.contains("failed open"));
        assertTrue(text.contains("network"));
        assertFalse(text.contains("1024"));
    }
}
