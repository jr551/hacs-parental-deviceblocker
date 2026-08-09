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
}
