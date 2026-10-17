package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
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

    @Test
    public void aBlockRequestedAfterTheUnlockSupersedesIt() {
        long granted = 1_700_000_000_000L;
        assertTrue(LocalOverride.blockSupersedes(granted + 30_000L, granted));
        assertFalse(LocalOverride.blockSupersedes(granted, granted));
        assertFalse(LocalOverride.blockSupersedes(granted - 30_000L, granted));
    }

    @Test
    public void aBlockNeverSupersedesWhenNoUnlockWasGranted() {
        long granted = 0L;
        assertFalse(LocalOverride.blockSupersedes(1_700_000_000_000L, granted));
    }

    @Test
    public void anOverrideWrittenWithoutAGrantTimestampUsesItsDeadline() {
        long until = 1_700_003_600_000L;
        assertEquals(until - LocalOverride.DURATION_MS, LocalOverride.grantMillis(until, 0L));
        assertEquals(until, LocalOverride.grantMillis(until, until));
        assertEquals(0L, LocalOverride.grantMillis(0L, 0L));
    }
}
