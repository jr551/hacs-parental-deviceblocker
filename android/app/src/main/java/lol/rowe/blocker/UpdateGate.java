package lol.rowe.blocker;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;

/** Lets the uninstall guard stand down while a package-installer prompt we started is open. */
final class UpdateGate {
    static volatile boolean installInProgress;

    /** A SHA-256 digest is always this many hex digits. */
    private static final int SHA256_HEX_LENGTH = 64;

    private UpdateGate() {
    }

    static boolean isNewer(String current, String remote) {
        String[] left = current.split("\\.");
        String[] right = remote.split("\\.");
        int length = Math.max(left.length, right.length);
        for (int index = 0; index < length; index++) {
            int currentPart = index < left.length ? number(left[index]) : 0;
            int remotePart = index < right.length ? number(right[index]) : 0;
            if (remotePart != currentPart) {
                return remotePart > currentPart;
            }
        }
        return false;
    }

    /** True when the release's .sha256 text names the digest of the downloaded bytes. */
    static boolean checksumMatches(String sha256FileText, String actualHex) {
        if (sha256FileText == null || actualHex == null) {
            return false;
        }
        String named = namedDigest(sha256FileText);
        return named != null && named.equalsIgnoreCase(actualHex.trim());
    }

    /** The SHA-256 of a file as lowercase hex. */
    static String sha256Hex(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return hex(digest.digest());
    }

    /** True when the update screen must be shown. */
    static boolean shouldShowUpdateScreen(boolean updatePending, boolean challengePending,
                                          boolean extensionActive, boolean localOverrideActive) {
        return updatePending && !challengePending && !extensionActive && !localOverrideActive;
    }

    /**
     * The digest named by a {@code sha256sum} line, which is the hex word followed by the
     * file name. Anything that is not a SHA-256 digest is refused rather than guessed at.
     */
    private static String namedDigest(String text) {
        for (String token : text.trim().split("\\s+")) {
            if (token.length() == SHA256_HEX_LENGTH && isHex(token)) {
                return token;
            }
        }
        return null;
    }

    private static boolean isHex(String token) {
        for (int index = 0; index < token.length(); index++) {
            if (Character.digit(token.charAt(index), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static String hex(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            text.append(Character.forDigit((value >> 4) & 0xf, 16));
            text.append(Character.forDigit(value & 0xf, 16));
        }
        return text.toString();
    }

    private static int number(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9].*", ""));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
