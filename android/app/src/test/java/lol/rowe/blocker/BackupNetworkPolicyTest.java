package lol.rowe.blocker;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class BackupNetworkPolicyTest {
    @Test
    public void everyUploadRequiresExternalPower() {
        assertFalse(BackupNetworkPolicy.mayUpload(false, false, false));
        assertFalse(BackupNetworkPolicy.mayUpload(false, true, false));
        assertFalse(BackupNetworkPolicy.mayUpload(true, false, false));
        assertFalse(BackupNetworkPolicy.mayUpload(true, true, false));
    }

    @Test
    public void poweredInitialSyncStillRequiresWifi() {
        assertFalse(BackupNetworkPolicy.mayUpload(false, false, true));
        assertTrue(BackupNetworkPolicy.mayUpload(false, true, true));
    }

    @Test
    public void poweredIncrementalSyncAllowsAnyConnectedJobNetwork() {
        assertTrue(BackupNetworkPolicy.mayUpload(true, false, true));
        assertTrue(BackupNetworkPolicy.mayUpload(true, true, true));
    }

    @Test
    public void onlyInitialJobTransitionsToPeriodicSchedule() {
        assertTrue(BackupNetworkPolicy.shouldInstallPeriodicSchedule(false));
        assertFalse(BackupNetworkPolicy.shouldInstallPeriodicSchedule(true));
    }

    @Test
    public void interruptedInitialBatchNeverCompletesInitialSync() {
        assertFalse(BackupNetworkPolicy.shouldMarkInitialComplete(
                false, 1, 1, false, true));
        assertFalse(BackupNetworkPolicy.shouldMarkInitialComplete(
                false, 1, 1, true, false));
        assertTrue(BackupNetworkPolicy.shouldMarkInitialComplete(
                false, 1, 1, false, false));
        assertTrue(BackupNetworkPolicy.shouldMarkInitialComplete(
                true, 20, 1, true, true));
    }
}
