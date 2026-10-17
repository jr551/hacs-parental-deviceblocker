package lol.rowe.blocker;

import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * The block screen's line about the child's other device, e.g.
 * "Your PC is open — Roblox · seen 2 minutes ago".
 */
final class FamilyStatus {
    private FamilyStatus() {
    }

    /** Empty when there is no peer to show. */
    static String line(Policy.FamilyPeer peer, long nowMillis) {
        if (peer == null) {
            return "";
        }
        String device = "pc".equalsIgnoreCase(peer.deviceType) ? "PC" : "phone";
        String age = ageText(peer.lastSeen, nowMillis);
        if (!peer.agentOnline) {
            return "Your " + device + " is offline"
                    + (age.isEmpty() ? "" : " · last seen " + age);
        }
        if (peer.blocked) {
            return "Your " + device + " is paused"
                    + (age.isEmpty() ? "" : " · seen " + age);
        }
        String application = applicationName(peer.activeApplication);
        return "Your " + device + " is open"
                + (application.isEmpty() ? "" : " — " + application)
                + (age.isEmpty() ? "" : " · seen " + age);
    }

    /** "RobloxPlayerBeta.exe" reads better as "RobloxPlayerBeta". */
    static String applicationName(String activeApplication) {
        String name = activeApplication == null ? "" : activeApplication.trim();
        if (name.length() > 4 && name.regionMatches(true, name.length() - 4, ".exe", 0, 4)) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    /** "just now", "5 minutes ago", "3 hours ago", "2 days ago", or empty. */
    static String ageText(String lastSeen, long nowMillis) {
        Instant instant = parse(lastSeen);
        if (instant == null) {
            return "";
        }
        long seconds = Math.max(0, (nowMillis - instant.toEpochMilli()) / 1000);
        if (seconds < 60) {
            return "just now";
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + (minutes == 1 ? " minute ago" : " minutes ago");
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + (hours == 1 ? " hour ago" : " hours ago");
        }
        long days = hours / 24;
        return days + (days == 1 ? " day ago" : " days ago");
    }

    private static Instant parse(String value) {
        if (value == null || value.trim().isEmpty() || "null".equals(value)) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }
}
