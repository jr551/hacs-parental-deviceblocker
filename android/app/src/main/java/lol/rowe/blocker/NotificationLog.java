package lol.rowe.blocker;

import android.app.Notification;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One day of posted notifications, kept as a bounded list and written out as
 * JSON by hand.
 *
 * <p>{@code org.json} ships inside {@code android.jar}, whose methods throw in
 * a plain JUnit test, so this class reads and writes its own JSON. That keeps
 * the whole buffer — caps, rollover and payload — testable without an
 * emulator.
 */
final class NotificationLog {
    /** More items than this in one day is noise; the parent never reads them. */
    static final int MAX_ITEMS = 500;
    /** The buffer and the POST body both stay under this. */
    static final int MAX_BYTES = 256 * 1024;
    /** Longest text kept from any one field. */
    static final int MAX_TEXT = 200;

    private NotificationLog() {
    }

    /** One posted notification, with every text field already trimmed and capped. */
    static final class Item {
        final String packageName;
        final String app;
        final String title;
        final String text;
        final String postedAt;

        Item(String packageName, String app, String title, String text, String postedAt) {
            this.packageName = packageName;
            this.app = app;
            this.title = title;
            this.text = text;
            this.postedAt = postedAt;
        }
    }

    /** One local day's buffer. */
    static final class Day {
        final String date;
        final List<Item> items;
        /** Every item offered to {@link #record}, including the dropped ones. */
        final int captured;
        final boolean truncated;

        Day(String date, List<Item> items, int captured, boolean truncated) {
            this.date = date;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.captured = captured;
            this.truncated = truncated;
        }
    }

    /**
     * True for the two kinds of notification a parent does not want: the
     * "3 new messages" group summary and the ongoing ones that never go away.
     */
    static boolean isNoise(int flags) {
        return (flags & Notification.FLAG_GROUP_SUMMARY) != 0
                || (flags & Notification.FLAG_ONGOING_EVENT) != 0;
    }

    /** Build an item, trimming and capping every field. */
    static Item item(String packageName, String app, String title, String text, String postedAt) {
        return new Item(
                cap(packageName),
                cap(app),
                cap(title),
                cap(text),
                cap(postedAt));
    }

    /**
     * Append one item, enforcing the caps. An item that does not fit is
     * dropped, but it still counts in {@code captured} and marks the day
     * truncated, so the parent can tell the day was busier than the buffer.
     */
    static Day record(Day day, Item item) {
        if (day == null || item == null) {
            return day;
        }
        int captured = day.captured + 1;
        // A truncated day can only grow, so nothing offered later fits either.
        if (!day.truncated && day.items.size() < MAX_ITEMS) {
            List<Item> items = new ArrayList<>(day.items);
            items.add(item);
            Day grown = new Day(day.date, items, captured, false);
            if (payload(grown).getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES) {
                return grown;
            }
        }
        return new Day(day.date, day.items, captured, true);
    }

    /** The stored buffer back to a day, or null when it is unreadable. */
    static Day parse(String stored) {
        if (stored == null) {
            return null;
        }
        try {
            Object root = new Reader(stored).read();
            if (!(root instanceof Map)) {
                return null;
            }
            Map<?, ?> body = (Map<?, ?>) root;
            Object date = body.get("date");
            Object count = body.get("count");
            Object truncated = body.get("truncated");
            Object items = body.get("items");
            if (!(date instanceof String)
                    || !(count instanceof Number)
                    || !(truncated instanceof Boolean)
                    || !(items instanceof List)) {
                return null;
            }
            List<Item> parsed = new ArrayList<>();
            for (Object entry : (List<?>) items) {
                if (!(entry instanceof Map)) {
                    return null;
                }
                Map<?, ?> fields = (Map<?, ?>) entry;
                parsed.add(new Item(
                        text(fields.get("package")),
                        text(fields.get("app")),
                        text(fields.get("title")),
                        text(fields.get("text")),
                        text(fields.get("posted_at"))));
            }
            return new Day((String) date, parsed, ((Number) count).intValue(), (Boolean) truncated);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * The on-disk buffer. It is the POST body verbatim, so a flush can send
     * what it read without a second serialization.
     */
    static String store(Day day) {
        return payload(day);
    }

    /** The POST body. */
    static String payload(Day day) {
        StringBuilder json = new StringBuilder();
        json.append("{\"date\":").append(quote(day.date))
                .append(",\"count\":").append(day.captured)
                .append(",\"truncated\":").append(day.truncated)
                .append(",\"items\":[");
        for (int index = 0; index < day.items.size(); index++) {
            if (index > 0) {
                json.append(',');
            }
            Item item = day.items.get(index);
            json.append("{\"package\":").append(quote(item.packageName))
                    .append(",\"app\":").append(quote(item.app))
                    .append(",\"title\":").append(quote(item.title))
                    .append(",\"text\":").append(quote(item.text))
                    .append(",\"posted_at\":").append(quote(item.postedAt))
                    .append('}');
        }
        return json.append("]}").toString();
    }

    /**
     * True when a buffer for {@code bufferDate} must be flushed on
     * {@code today}. ISO dates compare as text, so a phone that was off at
     * midnight still flushes the older day when it comes back.
     */
    static boolean flushDue(String bufferDate, String today) {
        if (bufferDate == null || bufferDate.isEmpty() || today == null || today.isEmpty()) {
            return false;
        }
        return bufferDate.compareTo(today) < 0;
    }

    private static String cap(String value) {
        String trimmed = value == null ? "" : value.trim();
        return trimmed.length() <= MAX_TEXT ? trimmed : trimmed.substring(0, MAX_TEXT);
    }

    private static String text(Object value) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof String)) {
            throw new IllegalArgumentException("not a string");
        }
        return (String) value;
    }

    private static String quote(String value) {
        StringBuilder json = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"':
                    json.append("\\\"");
                    break;
                case '\\':
                    json.append("\\\\");
                    break;
                case '\n':
                    json.append("\\n");
                    break;
                case '\r':
                    json.append("\\r");
                    break;
                case '\t':
                    json.append("\\t");
                    break;
                case '\b':
                    json.append("\\b");
                    break;
                case '\f':
                    json.append("\\f");
                    break;
                default:
                    if (character < 0x20) {
                        json.append(String.format("\\u%04x", (int) character));
                    } else {
                        json.append(character);
                    }
            }
        }
        return json.append('"').toString();
    }

    /**
     * A minimal JSON reader for this class's own output. It accepts any key
     * order and rejects anything that is not the shape {@link #payload} writes.
     */
    private static final class Reader {
        private final String text;
        private int index;

        Reader(String text) {
            this.text = text;
        }

        Object read() {
            skipSpace();
            Object value = readValue();
            skipSpace();
            if (index != text.length()) {
                throw new IllegalArgumentException("trailing text");
            }
            return value;
        }

        private Object readValue() {
            switch (peek()) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            index++;
            skipSpace();
            if (peek() == '}') {
                index++;
                return object;
            }
            while (true) {
                skipSpace();
                String name = readString();
                skipSpace();
                if (peek() != ':') {
                    throw new IllegalArgumentException("expected a colon");
                }
                index++;
                skipSpace();
                object.put(name, readValue());
                skipSpace();
                char next = peek();
                index++;
                if (next == '}') {
                    return object;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("expected a comma");
                }
            }
        }

        private List<Object> readArray() {
            List<Object> array = new ArrayList<>();
            index++;
            skipSpace();
            if (peek() == ']') {
                index++;
                return array;
            }
            while (true) {
                skipSpace();
                array.add(readValue());
                skipSpace();
                char next = peek();
                index++;
                if (next == ']') {
                    return array;
                }
                if (next != ',') {
                    throw new IllegalArgumentException("expected a comma");
                }
            }
        }

        private String readString() {
            if (peek() != '"') {
                throw new IllegalArgumentException("expected a string");
            }
            index++;
            StringBuilder value = new StringBuilder();
            while (true) {
                char character = next();
                if (character == '"') {
                    return value.toString();
                }
                if (character != '\\') {
                    value.append(character);
                    continue;
                }
                char escape = next();
                switch (escape) {
                    case '"':
                    case '\\':
                    case '/':
                        value.append(escape);
                        break;
                    case 'b':
                        value.append('\b');
                        break;
                    case 'f':
                        value.append('\f');
                        break;
                    case 'n':
                        value.append('\n');
                        break;
                    case 'r':
                        value.append('\r');
                        break;
                    case 't':
                        value.append('\t');
                        break;
                    case 'u':
                        value.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        throw new IllegalArgumentException("bad escape");
                }
            }
        }

        private Object readNumber() {
            int start = index;
            while (index < text.length() && "+-.eE0123456789".indexOf(text.charAt(index)) >= 0) {
                index++;
            }
            String number = text.substring(start, index);
            if (number.isEmpty()) {
                throw new IllegalArgumentException("expected a value");
            }
            if (number.indexOf('.') < 0 && number.indexOf('e') < 0 && number.indexOf('E') < 0) {
                return Long.valueOf(number);
            }
            return Double.valueOf(number);
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException("expected " + literal);
            }
            index += literal.length();
        }

        private char peek() {
            if (index >= text.length()) {
                throw new IllegalArgumentException("unexpected end of input");
            }
            return text.charAt(index);
        }

        private char next() {
            char character = peek();
            index++;
            return character;
        }

        private void skipSpace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }
    }
}
