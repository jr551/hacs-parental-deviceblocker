package lol.rowe.blocker;

/** Short window after a parent PIN unlock so Settings and updates are reachable. */
final class ParentMaintenance {
    private static volatile long allowedUntilMillis;

    private ParentMaintenance() {
    }

    static void allowForTenMinutes() {
        allowedUntilMillis = System.currentTimeMillis() + 10 * 60 * 1000L;
    }

    static boolean allows() {
        return System.currentTimeMillis() < allowedUntilMillis;
    }
}
