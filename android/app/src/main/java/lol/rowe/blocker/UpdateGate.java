package lol.rowe.blocker;

/** Lets the uninstall guard stand down while a package-installer prompt we started is open. */
final class UpdateGate {
    static volatile boolean installInProgress;

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

    private static int number(String part) {
        try {
            return Integer.parseInt(part.replaceAll("[^0-9].*", ""));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }
}
