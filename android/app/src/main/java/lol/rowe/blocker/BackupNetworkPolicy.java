package lol.rowe.blocker;

final class BackupNetworkPolicy {
    private BackupNetworkPolicy() {}

    static boolean mayUpload(boolean initialSyncComplete, boolean wifiConnected) {
        return initialSyncComplete || wifiConnected;
    }
}
