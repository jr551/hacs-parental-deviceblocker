package lol.rowe.blocker;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FamilyStatusTest {
    private static final long NOW = 1_800_000_000_000L;

    private static Policy.FamilyPeer peer(
            String deviceType,
            boolean blocked,
            boolean agentOnline,
            String lastSeen,
            String activeApplication) {
        return new Policy.FamilyPeer(
                "casey-pc", deviceType, blocked, "", agentOnline, lastSeen, activeApplication);
    }

    private static String iso(long millisAgo) {
        return java.time.Instant.ofEpochMilli(NOW - millisAgo).toString();
    }

    @Test
    public void noPeerMeansNoLine() {
        assertEquals("", FamilyStatus.line(null, NOW));
    }

    @Test
    public void aPausedPcReadsAsPaused() {
        String line = FamilyStatus.line(
                peer("pc", true, true, iso(2 * 60 * 1000L), "RobloxPlayerBeta.exe"), NOW);

        assertEquals("Your PC is paused · seen 2 minutes ago", line);
    }

    @Test
    public void anOpenPcNamesTheRunningApp() {
        String line = FamilyStatus.line(
                peer("pc", false, true, iso(30 * 1000L), "RobloxPlayerBeta.exe"), NOW);

        assertEquals("Your PC is open — RobloxPlayerBeta · seen just now", line);
    }

    @Test
    public void anOpenPcWithoutAnAppStillReadsAsOpen() {
        String line = FamilyStatus.line(peer("pc", false, true, iso(3 * 60 * 60 * 1000L), ""), NOW);

        assertEquals("Your PC is open · seen 3 hours ago", line);
    }

    @Test
    public void anOfflinePeerShowsTheLastSeenAge() {
        String line = FamilyStatus.line(
                peer("pc", false, false, iso(2 * 24 * 60 * 60 * 1000L), ""), NOW);

        assertEquals("Your PC is offline · last seen 2 days ago", line);
    }

    @Test
    public void aPhonePeerIsCalledAPhone() {
        String line = FamilyStatus.line(
                peer("phone", true, true, iso(60 * 1000L), ""), NOW);

        assertEquals("Your phone is paused · seen 1 minute ago", line);
    }

    @Test
    public void aPeerWithoutATimestampStillReadsClearly() {
        assertEquals(
                "Your PC is paused",
                FamilyStatus.line(peer("pc", true, true, null, ""), NOW));
        assertEquals(
                "Your PC is offline",
                FamilyStatus.line(peer("pc", false, false, "not-a-timestamp", ""), NOW));
    }

    @Test
    public void onlyWindowsExecutableSuffixesAreStripped() {
        assertEquals("RobloxPlayerBeta", FamilyStatus.applicationName("RobloxPlayerBeta.exe"));
        assertEquals("RobloxPlayerBeta", FamilyStatus.applicationName("RobloxPlayerBeta.EXE"));
        assertEquals("exe", FamilyStatus.applicationName("exe"));
        assertEquals("", FamilyStatus.applicationName(null));
        assertEquals("Roblox", FamilyStatus.applicationName(" Roblox "));
    }

    @Test
    public void agesReadAsJustNowMinutesHoursAndDays() {
        assertTrue(FamilyStatus.ageText(iso(10 * 1000L), NOW).equals("just now"));
        assertEquals("1 minute ago", FamilyStatus.ageText(iso(60 * 1000L), NOW));
        assertEquals("59 minutes ago", FamilyStatus.ageText(iso(59 * 60 * 1000L), NOW));
        assertEquals("1 hour ago", FamilyStatus.ageText(iso(60 * 60 * 1000L), NOW));
        assertEquals("23 hours ago", FamilyStatus.ageText(iso(23 * 60 * 60 * 1000L), NOW));
        assertEquals("1 day ago", FamilyStatus.ageText(iso(24 * 60 * 60 * 1000L), NOW));
        assertEquals("", FamilyStatus.ageText(null, NOW));
    }
}
