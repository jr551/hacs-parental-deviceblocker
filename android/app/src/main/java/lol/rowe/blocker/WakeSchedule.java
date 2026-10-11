package lol.rowe.blocker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/** Weekday wake alarm. Bit 0 is Monday through bit 6 Sunday. */
final class WakeSchedule {
    static final String[] DAY_LABELS = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
    /** Monday–Friday on, Saturday and Sunday off. */
    static final int WEEKDAYS = 0b0011111;

    private WakeSchedule() {
    }

    static boolean isDayEnabled(int dayBits, int mondayBasedIndex) {
        return mondayBasedIndex >= 0
                && mondayBasedIndex < 7
                && (dayBits & (1 << mondayBasedIndex)) != 0;
    }

    static int withDay(int dayBits, int mondayBasedIndex, boolean enabled) {
        int bit = 1 << mondayBasedIndex;
        return enabled ? (dayBits | bit) : (dayBits & ~bit);
    }

    static Long nextTriggerMillis(long nowMillis, int hour, int minute, int dayBits, ZoneId zone) {
        if (dayBits == 0 || hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            return null;
        }
        ZonedDateTime now = Instant.ofEpochMilli(nowMillis).atZone(zone);
        for (int add = 0; add < 8; add++) {
            ZonedDateTime candidate = now.toLocalDate()
                    .plusDays(add)
                    .atTime(hour, minute)
                    .atZone(zone);
            int mondayIndex = candidate.getDayOfWeek().getValue() - 1;
            if (!isDayEnabled(dayBits, mondayIndex)) {
                continue;
            }
            long trigger = candidate.toInstant().toEpochMilli();
            if (trigger > nowMillis) {
                return trigger;
            }
        }
        return null;
    }

    /** 0 is silent. 100 maps to the alarm stream's maximum index. */
    static int alarmStreamIndex(int volumePercent, int streamMax) {
        if (streamMax <= 0) {
            return 0;
        }
        int percent = Math.max(0, Math.min(100, volumePercent));
        return (percent * streamMax + 50) / 100;
    }

    static String dayList(int dayBits) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < DAY_LABELS.length; index++) {
            if (!isDayEnabled(dayBits, index)) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(DAY_LABELS[index]);
        }
        return builder.length() == 0 ? "no days" : builder.toString();
    }

    /** A wake alarm received from this phone's Home Assistant device entry. */
    static final class Update {
        final boolean enabled;
        final int hour;
        final int minute;
        final int days;
        final int volume;

        private Update(boolean enabled, int hour, int minute, int days, int volume) {
            this.enabled = enabled;
            this.hour = hour;
            this.minute = minute;
            this.days = days & 0x7f;
            this.volume = volume;
        }

        static Update of(boolean enabled, int hour, int minute, int days, int volume) {
            if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                return null;
            }
            if (volume < 0 || volume > 100) {
                return null;
            }
            return new Update(enabled, hour, minute, days, volume);
        }
    }
}
