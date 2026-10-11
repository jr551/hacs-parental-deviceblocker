package lol.rowe.blocker;

import org.junit.Test;

import java.time.LocalDateTime;
import java.time.ZoneId;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class WakeScheduleTest {
    private final ZoneId london = ZoneId.of("Europe/London");

    @Test
    public void weekdaysAreMondayToFridayByDefault() {
        for (int day = 0; day < 5; day++) {
            assertTrue(WakeSchedule.isDayEnabled(WakeSchedule.WEEKDAYS, day));
        }
        assertFalse(WakeSchedule.isDayEnabled(WakeSchedule.WEEKDAYS, 5));
        assertFalse(WakeSchedule.isDayEnabled(WakeSchedule.WEEKDAYS, 6));
    }

    @Test
    public void nextTriggerSkipsWeekendAndPastTimes() {
        // Saturday 11 Oct 2026 08:00 local. Alarm is 07:00 weekdays only.
        long saturdayMorning = LocalDateTime.of(2026, 10, 11, 8, 0)
                .atZone(london)
                .toInstant()
                .toEpochMilli();
        Long next = WakeSchedule.nextTriggerMillis(
                saturdayMorning, 7, 0, WakeSchedule.WEEKDAYS, london);
        LocalDateTime when = java.time.Instant.ofEpochMilli(next)
                .atZone(london)
                .toLocalDateTime();
        assertEquals(12, when.getDayOfMonth());
        assertEquals(7, when.getHour());
    }

    @Test
    public void volumePercentMapsOntoTheAlarmStream() {
        assertEquals(0, WakeSchedule.alarmStreamIndex(0, 15));
        assertEquals(8, WakeSchedule.alarmStreamIndex(50, 15));
        assertEquals(15, WakeSchedule.alarmStreamIndex(100, 15));
        assertEquals(0, WakeSchedule.alarmStreamIndex(100, 0));
    }

    @Test
    public void dayListFollowsMondayFirstBits() {
        assertEquals("Mon, Tue, Wed, Thu, Fri", WakeSchedule.dayList(WakeSchedule.WEEKDAYS));
        assertEquals("Sat, Sun", WakeSchedule.dayList(0b1100000));
        assertEquals("no days", WakeSchedule.dayList(0));
    }

    @Test
    public void serverUpdateRejectsAnImpossibleClock() {
        assertNull(WakeSchedule.Update.of(true, 24, 0, WakeSchedule.WEEKDAYS, 100));
        assertNull(WakeSchedule.Update.of(true, 7, 0, WakeSchedule.WEEKDAYS, 101));
        WakeSchedule.Update update = WakeSchedule.Update.of(true, 6, 40, 0b0010101, 0);
        assertEquals(6, update.hour);
        assertEquals(40, update.minute);
        assertEquals(0, update.volume);
    }

    @Test
    public void noSelectedDayDoesNotSchedule() {
        long now = LocalDateTime.of(2026, 10, 12, 6, 0).atZone(london).toInstant().toEpochMilli();
        assertNull(WakeSchedule.nextTriggerMillis(now, 7, 0, 0, london));
    }
}
