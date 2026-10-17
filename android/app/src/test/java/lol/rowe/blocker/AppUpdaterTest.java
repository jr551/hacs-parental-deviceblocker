package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AppUpdaterTest {
    @Test
    public void aNormalVersionIsAccepted() {
        assertTrue(AppUpdater.isValidVersion("0.5.8"));
        assertTrue(AppUpdater.isValidVersion("1"));
        assertTrue(AppUpdater.isValidVersion("0.5.8.1"));
        assertTrue(AppUpdater.isValidVersion("10.20.30"));
    }

    @Test
    public void aVersionWithAPathSeparatorOrOtherJunkIsRejected() {
        assertFalse(AppUpdater.isValidVersion("../../etc/passwd"));
        assertFalse(AppUpdater.isValidVersion("0.5.8/../0.5.9"));
        assertFalse(AppUpdater.isValidVersion("0.5.8\\..\\0.5.9"));
        assertFalse(AppUpdater.isValidVersion("/0.5.8"));
        assertFalse(AppUpdater.isValidVersion("0.5.8-beta"));
        assertFalse(AppUpdater.isValidVersion("0.5.8 "));
        assertFalse(AppUpdater.isValidVersion(" 0.5.8"));
        assertFalse(AppUpdater.isValidVersion("0.5.8\n"));
        assertFalse(AppUpdater.isValidVersion("0.5.8.apk"));
        assertFalse(AppUpdater.isValidVersion(""));
        assertFalse(AppUpdater.isValidVersion(null));
    }

    @Test
    public void theVersionIsReadFromAReleaseAssetName() {
        assertEquals("0.5.8", AppUpdater.versionFromAssetName("ParentalDeviceBlocker-0.5.8.apk"));
        assertEquals("1.0", AppUpdater.versionFromAssetName("ParentalDeviceBlocker-1.0.apk"));
    }

    @Test
    public void anAssetNameThatIsNotOursIsRejected() {
        assertEquals("", AppUpdater.versionFromAssetName("ParentalDeviceBlocker-0.5.8.apk.sha256"));
        assertEquals("", AppUpdater.versionFromAssetName("ParentalDeviceBlocker-0.5.8-debug.apk"));
        assertEquals("", AppUpdater.versionFromAssetName("ParentalDeviceBlocker-0.5.8-beta.apk"));
        assertEquals("", AppUpdater.versionFromAssetName("evil.apk"));
        assertEquals("", AppUpdater.versionFromAssetName(""));
        assertEquals("", AppUpdater.versionFromAssetName(null));
    }

    @Test
    public void anAssetNameThatTriesToEscapeTheUpdateDirectoryIsRejected() {
        assertEquals(
                "",
                AppUpdater.versionFromAssetName(
                        "ParentalDeviceBlocker-../../files/evil.apk"));
        assertEquals(
                "",
                AppUpdater.versionFromAssetName(
                        "ParentalDeviceBlocker-0.5.8/../../../evil.apk"));
        assertEquals(
                "",
                AppUpdater.versionFromAssetName(
                        "ParentalDeviceBlocker-0.5.8\u0000.apk"));
    }
}
