package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ReportScheduleTest {
    @Test
    public void firstReportIsDueAndLaterReportsWaitAnHour() {
        assertTrue(ReportSchedule.due(0L, 1_000L));
        assertFalse(ReportSchedule.due(1_000L, 1_000L + 30 * 60 * 1000L));
        assertTrue(ReportSchedule.due(1_000L, 1_000L + ReportSchedule.HOURLY_REPORT_MS));
    }
}
