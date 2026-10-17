package lol.rowe.blocker;

import android.app.Notification;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class NotificationLogTest {
    private static final String DATE = "2026-10-17";

    @Test
    public void itemCapDropsTheFiveHundredAndFirstButStillCountsIt() {
        NotificationLog.Day day = day(DATE);
        for (int index = 0; index < NotificationLog.MAX_ITEMS; index++) {
            day = NotificationLog.record(day, item("short " + index));
        }
        assertEquals(NotificationLog.MAX_ITEMS, day.items.size());
        assertEquals(NotificationLog.MAX_ITEMS, day.captured);
        assertFalse(day.truncated);

        day = NotificationLog.record(day, item("one too many"));
        assertEquals(NotificationLog.MAX_ITEMS, day.items.size());
        assertEquals(NotificationLog.MAX_ITEMS + 1, day.captured);
        assertTrue(day.truncated);
    }

    @Test
    public void byteCapStopsTheBufferBeforeTheItemCap() {
        NotificationLog.Day day = day(DATE);
        for (int index = 0; index < NotificationLog.MAX_ITEMS; index++) {
            day = NotificationLog.record(day, fatItem());
        }
        assertTrue(day.truncated);
        assertTrue(day.items.size() < NotificationLog.MAX_ITEMS);
        assertEquals(NotificationLog.MAX_ITEMS, day.captured);
        assertTrue(payloadBytes(day) <= NotificationLog.MAX_BYTES);

        int size = day.items.size();
        day = NotificationLog.record(day, fatItem());
        assertEquals(size, day.items.size());
        assertEquals(NotificationLog.MAX_ITEMS + 1, day.captured);
        assertTrue(payloadBytes(day) <= NotificationLog.MAX_BYTES);
    }

    @Test
    public void everyTextFieldIsTrimmedAndCappedAtTwoHundredCharacters() {
        NotificationLog.Item item = NotificationLog.item(
                "  com.example.app  ",
                "  Example  ",
                "  Title  ",
                "  " + repeat('y', 300) + "  ",
                "  2026-10-17T09:00:00Z  ");
        assertEquals("com.example.app", item.packageName);
        assertEquals("Example", item.app);
        assertEquals("Title", item.title);
        assertEquals(NotificationLog.MAX_TEXT, item.text.length());
        assertEquals(repeat('y', NotificationLog.MAX_TEXT), item.text);
        assertEquals("2026-10-17T09:00:00Z", item.postedAt);

        NotificationLog.Item empty = NotificationLog.item(null, null, null, null, null);
        assertEquals("", empty.packageName);
        assertEquals("", empty.text);
    }

    @Test
    public void flushIsDueOnlyForAnOlderDay() {
        assertFalse(NotificationLog.flushDue(DATE, DATE));
        assertTrue(NotificationLog.flushDue(DATE, "2026-10-18"));
        assertTrue(NotificationLog.flushDue("2026-10-16", "2026-10-18"));
        assertFalse(NotificationLog.flushDue("", "2026-10-18"));
        assertFalse(NotificationLog.flushDue(null, "2026-10-18"));
        assertFalse(NotificationLog.flushDue("2026-10-18", "2026-10-17"));
    }

    @Test
    public void payloadCarriesTheDayAndEveryItemField() {
        NotificationLog.Day day = NotificationLog.record(day(DATE), item("hello"));
        day = NotificationLog.record(day, item("world"));
        String payload = NotificationLog.payload(day);
        assertTrue(payload.startsWith(
                "{\"date\":\"" + DATE + "\",\"count\":2,\"truncated\":false,\"items\":["));
        assertTrue(payload.endsWith("]}"));
        assertEquals(2, occurrences(payload, "\"package\":\"com.example.app\""));
        assertEquals(2, occurrences(payload, "\"app\":\"Example\""));
        assertEquals(2, occurrences(payload, "\"title\":\"Title\""));
        assertEquals(2, occurrences(payload, "\"posted_at\":\"2026-10-17T09:00:00Z\""));
        assertTrue(payload.contains("\"text\":\"hello\""));
        assertTrue(payload.contains("\"text\":\"world\""));
    }

    @Test
    public void payloadCountsTheItemsItDropped() {
        NotificationLog.Day day = day(DATE);
        for (int index = 0; index <= NotificationLog.MAX_ITEMS; index++) {
            day = NotificationLog.record(day, item("short"));
        }
        assertEquals(NotificationLog.MAX_ITEMS, day.items.size());
        assertEquals(NotificationLog.MAX_ITEMS + 1, day.captured);
        String payload = NotificationLog.payload(day);
        assertTrue(payload.contains("\"count\":" + (NotificationLog.MAX_ITEMS + 1)));
        assertTrue(payload.contains("\"truncated\":true"));
    }

    @Test
    public void storeAndParseRoundTripTheDay() {
        NotificationLog.Day day = NotificationLog.record(
                day(DATE), item("quote \" backslash \\ newline \n tab \t"));
        day = NotificationLog.record(day, item("plain"));
        NotificationLog.Day parsed = NotificationLog.parse(NotificationLog.store(day));
        assertNotNull(parsed);
        assertEquals(day.date, parsed.date);
        assertEquals(day.captured, parsed.captured);
        assertEquals(day.truncated, parsed.truncated);
        assertEquals(day.items.size(), parsed.items.size());
        for (int index = 0; index < day.items.size(); index++) {
            assertEquals(day.items.get(index).packageName, parsed.items.get(index).packageName);
            assertEquals(day.items.get(index).app, parsed.items.get(index).app);
            assertEquals(day.items.get(index).title, parsed.items.get(index).title);
            assertEquals(day.items.get(index).text, parsed.items.get(index).text);
            assertEquals(day.items.get(index).postedAt, parsed.items.get(index).postedAt);
        }
    }

    @Test
    public void parseRejectsAnythingThatIsNotABuffer() {
        assertNull(NotificationLog.parse(null));
        assertNull(NotificationLog.parse(""));
        assertNull(NotificationLog.parse("not json"));
        assertNull(NotificationLog.parse("{\"date\":\"" + DATE + "\"}"));
        assertNull(NotificationLog.parse("{\"date\":1,\"count\":1,\"truncated\":false,\"items\":[]}"));
        assertNull(NotificationLog.parse(
                "{\"date\":\"" + DATE + "\",\"count\":1,\"truncated\":false,\"items\":[1]}"));
        assertNull(NotificationLog.parse(
                "{\"date\":\"" + DATE + "\",\"count\":1,\"truncated\":false,\"items\":[]} trailing"));
    }

    @Test
    public void groupSummariesAndOngoingNotificationsAreNoise() {
        assertTrue(NotificationLog.isNoise(Notification.FLAG_GROUP_SUMMARY));
        assertTrue(NotificationLog.isNoise(Notification.FLAG_ONGOING_EVENT));
        assertTrue(NotificationLog.isNoise(
                Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT));
        assertFalse(NotificationLog.isNoise(0));
        assertFalse(NotificationLog.isNoise(Notification.FLAG_AUTO_CANCEL));
    }

    private static NotificationLog.Day day(String date) {
        return new NotificationLog.Day(date, Collections.emptyList(), 0, false);
    }

    private static NotificationLog.Item item(String text) {
        return NotificationLog.item(
                "com.example.app", "Example", "Title", text, "2026-10-17T09:00:00Z");
    }

    /** Every field at its cap, so the byte cap bites before the item cap. */
    private static NotificationLog.Item fatItem() {
        String filler = repeat('x', NotificationLog.MAX_TEXT);
        return NotificationLog.item(filler, filler, filler, filler, filler);
    }

    private static int payloadBytes(NotificationLog.Day day) {
        return NotificationLog.payload(day).getBytes(StandardCharsets.UTF_8).length;
    }

    private static String repeat(char character, int count) {
        StringBuilder value = new StringBuilder(count);
        for (int index = 0; index < count; index++) {
            value.append(character);
        }
        return value.toString();
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
