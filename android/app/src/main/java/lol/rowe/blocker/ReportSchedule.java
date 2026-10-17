package lol.rowe.blocker;

/** Policy stays near once a minute. Everything else waits for the hourly batch. */
final class ReportSchedule {
    static final long POLICY_POLL_SECONDS = 60;
    static final long HOURLY_REPORT_MS = 60 * 60 * 1000L;

    private ReportSchedule() {
    }

    static boolean due(long lastElapsedMs, long nowElapsedMs) {
        return lastElapsedMs == 0L || nowElapsedMs - lastElapsedMs >= HOURLY_REPORT_MS;
    }
}
