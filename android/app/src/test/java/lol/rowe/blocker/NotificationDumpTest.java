package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * The on-demand half of {@link NotificationDump}: a parent's request is
 * answered once, and a poll that repeats it is ignored.
 */
public class NotificationDumpTest {
    @Test
    public void aRequestNotYetAnsweredIsAnswered() {
        assertTrue(NotificationDump.shouldAnswer("abc123", ""));
        assertTrue(NotificationDump.shouldAnswer("abc123", "older"));
        assertTrue(NotificationDump.shouldAnswer("  abc123  ", "older"));
    }

    @Test
    public void theSameRequestIdIsNotAnsweredTwice() {
        assertFalse(NotificationDump.shouldAnswer("abc123", "abc123"));
        assertFalse(NotificationDump.shouldAnswer("  abc123  ", "abc123"));
        assertFalse(NotificationDump.shouldAnswer("", ""));
        assertFalse(NotificationDump.shouldAnswer(null, "abc123"));
    }
}
