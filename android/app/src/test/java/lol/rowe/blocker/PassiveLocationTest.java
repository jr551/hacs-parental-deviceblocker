package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class PassiveLocationTest {
    @Test
    public void aFreshCachedFixIsNotRefreshed() {
        assertFalse(PassiveLocation.shouldRequestFreshFix(
                60_000L, PassiveLocation.REQUEST_INTERVAL_MILLIS));
        assertFalse(PassiveLocation.shouldRequestFreshFix(
                PassiveLocation.FRESH_FIX_MILLIS, Long.MAX_VALUE));
    }

    @Test
    public void aStaleCachedFixIsRefreshed() {
        assertTrue(PassiveLocation.shouldRequestFreshFix(
                PassiveLocation.FRESH_FIX_MILLIS + 1, PassiveLocation.REQUEST_INTERVAL_MILLIS));
        // No cached fix at all is the stalest case.
        assertTrue(PassiveLocation.shouldRequestFreshFix(
                Long.MAX_VALUE, PassiveLocation.REQUEST_INTERVAL_MILLIS));
    }

    @Test
    public void refreshesAreThrottledToOnceEveryFifteenMinutes() {
        long stale = PassiveLocation.FRESH_FIX_MILLIS + 1;

        assertFalse(PassiveLocation.shouldRequestFreshFix(stale, 0L));
        assertFalse(PassiveLocation.shouldRequestFreshFix(
                stale, PassiveLocation.REQUEST_INTERVAL_MILLIS - 1));
        assertTrue(PassiveLocation.shouldRequestFreshFix(
                stale, PassiveLocation.REQUEST_INTERVAL_MILLIS));
    }
}
