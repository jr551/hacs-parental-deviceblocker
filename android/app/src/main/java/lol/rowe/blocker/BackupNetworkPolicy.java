package lol.rowe.blocker;

final class BackupNetworkPolicy {
    private BackupNetworkPolicy() {}

    static boolean mayUpload(
            boolean initialSyncComplete,
            boolean wifiConnected,
            boolean externallyPowered) {
        return externallyPowered && (initialSyncComplete || wifiConnected);
    }

    static boolean shouldInstallPeriodicSchedule(boolean wasInitialSyncComplete) {
        return !wasInitialSyncComplete;
    }

    static boolean shouldMarkInitialComplete(
            boolean wasInitialSyncComplete,
            int pendingCount,
            int batchSize,
            boolean stopped,
            boolean retry) {
        return wasInitialSyncComplete
                || (!stopped && !retry && pendingCount <= batchSize);
    }
}
