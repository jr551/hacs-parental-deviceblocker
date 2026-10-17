package lol.rowe.blocker;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class UpdateGateTest {
    /** The SHA-256 of the three bytes "abc". */
    private static final String ABC_SHA256 =
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @Test
    public void newerReleaseVersionsCompareByNumber() {
        assertTrue(UpdateGate.isNewer("0.4.8", "0.4.9"));
        assertFalse(UpdateGate.isNewer("0.4.9", "0.4.9"));
        assertFalse(UpdateGate.isNewer("0.4.9", "0.4.8"));
        assertTrue(UpdateGate.isNewer("0.4.9", "0.5.0"));
    }

    @Test
    public void shorterAndLongerVersionTagsCompareByPart() {
        assertTrue(UpdateGate.isNewer("0.5", "0.5.1"));
        assertFalse(UpdateGate.isNewer("0.5.1", "0.5"));
        assertTrue(UpdateGate.isNewer("0.5.5", "0.6"));
        assertTrue(UpdateGate.isNewer("0.5.5", "0.5.5.1"));
        assertFalse(UpdateGate.isNewer("0.5.5", "0.5.5.0"));
    }

    @Test
    public void malformedVersionTagsNeverCountAsNewer() {
        assertFalse(UpdateGate.isNewer("0.5.5", "abc"));
        assertFalse(UpdateGate.isNewer("0.5.5", "0.5.x"));
        assertFalse(UpdateGate.isNewer("0.5.5", ""));
        assertFalse(UpdateGate.isNewer("abc", "abc"));
        assertFalse(UpdateGate.isNewer("", ""));
        assertFalse(UpdateGate.isNewer("", "abc"));
    }

    @Test
    public void checksumMatchesTheDigestTheReleaseNames() {
        assertTrue(UpdateGate.checksumMatches(ABC_SHA256, ABC_SHA256));
        assertTrue(UpdateGate.checksumMatches(ABC_SHA256.toUpperCase(Locale.ROOT), ABC_SHA256));
        assertTrue(UpdateGate.checksumMatches(ABC_SHA256, ABC_SHA256.toUpperCase(Locale.ROOT)));
        assertTrue(UpdateGate.checksumMatches(ABC_SHA256 + "\n", ABC_SHA256));
        assertTrue(UpdateGate.checksumMatches("  " + ABC_SHA256 + "  \n", ABC_SHA256));
    }

    @Test
    public void checksumMatchesARealSha256sumLine() {
        assertTrue(UpdateGate.checksumMatches(
                ABC_SHA256 + "  ParentalDeviceBlocker-0.5.5.apk\n", ABC_SHA256));
        assertTrue(UpdateGate.checksumMatches(
                ABC_SHA256 + " *ParentalDeviceBlocker-0.5.5.apk\n", ABC_SHA256));
    }

    @Test
    public void checksumRejectsEverythingElse() {
        assertFalse(UpdateGate.checksumMatches(ABC_SHA256,
                "0000000000000000000000000000000000000000000000000000000000000000"));
        assertFalse(UpdateGate.checksumMatches(ABC_SHA256.substring(0, 32), ABC_SHA256));
        assertFalse(UpdateGate.checksumMatches("", ABC_SHA256));
        assertFalse(UpdateGate.checksumMatches("   \n", ABC_SHA256));
        assertFalse(UpdateGate.checksumMatches("not a checksum", ABC_SHA256));
        assertFalse(UpdateGate.checksumMatches("ParentalDeviceBlocker-0.5.5.apk", ABC_SHA256));
        assertFalse(UpdateGate.checksumMatches(ABC_SHA256, ""));
        assertFalse(UpdateGate.checksumMatches(ABC_SHA256, "not a digest"));
        assertFalse(UpdateGate.checksumMatches(null, ABC_SHA256));
        assertFalse(UpdateGate.checksumMatches(ABC_SHA256, null));
    }

    @Test
    public void sha256HexHashesTheFileBytes() throws Exception {
        File file = File.createTempFile("pdb-update-test", ".apk");
        try {
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write("abc".getBytes(StandardCharsets.UTF_8));
            }
            assertEquals(ABC_SHA256, UpdateGate.sha256Hex(file));
            assertTrue(UpdateGate.checksumMatches(
                    ABC_SHA256 + "  ParentalDeviceBlocker-0.5.5.apk\n", UpdateGate.sha256Hex(file)));
        } finally {
            file.delete();
        }
    }

    @Test
    public void updateScreenShowsOnlyForAPendingUpdateWithNothingInTheWay() {
        assertTrue(UpdateGate.shouldShowUpdateScreen(true, false, false, false));
        assertFalse(UpdateGate.shouldShowUpdateScreen(false, false, false, false));
        assertFalse(UpdateGate.shouldShowUpdateScreen(true, true, false, false));
        assertFalse(UpdateGate.shouldShowUpdateScreen(true, false, true, false));
        assertFalse(UpdateGate.shouldShowUpdateScreen(true, false, false, true));
        assertFalse(UpdateGate.shouldShowUpdateScreen(false, true, true, true));
    }
}
