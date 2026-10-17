package lol.rowe.blocker;

import java.util.Locale;

/**
 * Device id for a phone, derived from the child name the parent types.
 * "Casey" and "Alex" become casey-phone and alex-phone.
 */
final class ChildDevice {
    private ChildDevice() {
    }

    static String deviceId(String childName) {
        String slug = slug(childName);
        if (slug.isEmpty()) {
            return "";
        }
        return slug + "-phone";
    }

    /** Display name saved from setup, or a capitalised guess from the device id. */
    static String displayName(String storedName, String deviceId) {
        if (storedName != null && !storedName.trim().isEmpty()) {
            return storedName.trim();
        }
        if (deviceId == null || !deviceId.endsWith("-phone") || deviceId.length() <= "-phone".length()) {
            return "";
        }
        String slug = deviceId.substring(0, deviceId.length() - "-phone".length());
        return slug.substring(0, 1).toUpperCase(Locale.ROOT) + slug.substring(1);
    }

    private static String slug(String childName) {
        if (childName == null) {
            return "";
        }
        StringBuilder slug = new StringBuilder();
        for (int index = 0; index < childName.length(); index++) {
            char character = Character.toLowerCase(childName.charAt(index));
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')) {
                slug.append(character);
            }
        }
        return slug.toString();
    }
}
