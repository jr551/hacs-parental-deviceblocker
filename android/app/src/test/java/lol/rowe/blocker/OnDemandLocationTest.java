package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class OnDemandLocationTest {
    @Test
    public void aNewRequestIdIsFetched() {
        assertTrue(OnDemandLocation.shouldFetch("abc123", ""));
        assertTrue(OnDemandLocation.shouldFetch("abc123", "older"));
        assertTrue(OnDemandLocation.shouldFetch("  abc123  ", "older"));
    }

    @Test
    public void theSameRequestIdIsNotFetchedAgain() {
        assertFalse(OnDemandLocation.shouldFetch("abc123", "abc123"));
        assertFalse(OnDemandLocation.shouldFetch("  abc123  ", "abc123"));
        assertFalse(OnDemandLocation.shouldFetch("", ""));
        assertFalse(OnDemandLocation.shouldFetch(null, "abc123"));
    }

    @Test
    public void aTimeoutPostsNothing() {
        // fetch() returns null when no fix arrives inside the timeout, and a
        // null fix is never sendable.
        assertFalse(OnDemandLocation.isSendable(null, null, 0));
        assertFalse(OnDemandLocation.isSendable(null, -0.28, 3));
    }

    @Test
    public void outOfRangeOrMissingCoordinatesAreNotSent() {
        assertFalse(OnDemandLocation.isSendable(51.37, null, 3));
        assertFalse(OnDemandLocation.isSendable(91.0, -0.28, 3));
        assertFalse(OnDemandLocation.isSendable(-91.0, -0.28, 3));
        assertFalse(OnDemandLocation.isSendable(51.37, 181.0, 3));
        assertFalse(OnDemandLocation.isSendable(51.37, -181.0, 3));
    }

    @Test
    public void aStaleFixIsNotSentAsIfItWereCurrent() {
        assertFalse(OnDemandLocation.isSendable(51.37, -0.28, -1));
        assertFalse(OnDemandLocation.isSendable(
                51.37, -0.28, OnDemandLocation.MAX_FIX_AGE_SECONDS + 1));
        assertTrue(OnDemandLocation.isSendable(51.37, -0.28, 3));
        assertTrue(OnDemandLocation.isSendable(
                51.37, -0.28, OnDemandLocation.MAX_FIX_AGE_SECONDS));
    }
}
