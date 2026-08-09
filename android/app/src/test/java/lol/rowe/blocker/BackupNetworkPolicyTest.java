package lol.rowe.blocker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BackupNetworkPolicyTest {
    @Test
    public void initialSyncRequiresWifi() {
        assertFalse(BackupNetworkPolicy.mayUpload(false, false));
        assertTrue(BackupNetworkPolicy.mayUpload(false, true));
    }

    @Test
    public void incrementalSyncAllowsAnyConnectedJobNetwork() {
        assertTrue(BackupNetworkPolicy.mayUpload(true, false));
        assertTrue(BackupNetworkPolicy.mayUpload(true, true));
    }

    @Test
    public void onlyInitialJobTransitionsToPeriodicSchedule() {
        assertTrue(BackupNetworkPolicy.shouldInstallPeriodicSchedule(false));
        assertFalse(BackupNetworkPolicy.shouldInstallPeriodicSchedule(true));
    }
}
