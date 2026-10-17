package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LocalOverrideTest {
    @Test
    public void anOverrideIsActiveUntilItsDeadline() {
        long now = 1_700_000_000_000L;
        assertTrue(LocalOverride.isActive(now + 1, now));
        assertTrue(LocalOverride.isActive(now + LocalOverride.DURATION_MS, now));
        assertFalse(LocalOverride.isActive(now, now));
        assertFalse(LocalOverride.isActive(now - 1, now));
        assertFalse(LocalOverride.isActive(0L, now));
    }
}
