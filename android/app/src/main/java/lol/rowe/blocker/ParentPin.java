package lol.rowe.blocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;

/**
 * Parent PIN gate for the configuration panel.
 *
 * <p>Without this, a child holding an unblocked device can open the app and
 * retarget the Home Assistant URL or wipe the device key, permanently
 * neutralising future blocks. Only a salted SHA-256 digest is stored; the PIN
 * itself is never persisted. Forgetting the PIN means clearing the app's data,
 * which also clears the device configuration.
 */
final class ParentPin {
    private static final String PREFS = "rowe_blocker";
    private static final String SALT = "parent_pin_salt";
    private static final String HASH = "parent_pin_hash";
    static final int MIN_LENGTH = 4;
    static final int MAX_ATTEMPTS = 2;

    private ParentPin() {
    }

    static boolean isSet(Context context) {
        SharedPreferences preferences = preferences(context);
        return !preferences.getString(HASH, "").isEmpty()
                && !preferences.getString(SALT, "").isEmpty();
    }

    static void set(Context context, String pin) {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        String encodedSalt = encode(salt);
        preferences(context)
                .edit()
                .putString(SALT, encodedSalt)
                .putString(HASH, digest(pin, encodedSalt))
                .apply();
    }

    static boolean verify(Context context, String pin) {
        SharedPreferences preferences = preferences(context);
        String salt = preferences.getString(SALT, "");
        String expected = preferences.getString(HASH, "");
        return verifyStored(salt, expected, pin);
    }

    static boolean verifyStored(String salt, String expected, String pin) {
        if (salt.isEmpty() || expected.isEmpty()) {
            return false;
        }
        return constantTimeEquals(expected, digest(pin, salt));
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // Package-private for unit tests; contains no Android dependencies.
    static String digest(String pin, String salt) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(salt.getBytes(StandardCharsets.UTF_8));
            return encode(sha256.digest(pin.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            // SHA-256 is mandated on every Android release this app supports.
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static String encode(byte[] value) {
        StringBuilder builder = new StringBuilder(value.length * 2);
        for (byte item : value) {
            builder.append(Character.forDigit((item >> 4) & 0xF, 16));
            builder.append(Character.forDigit(item & 0xF, 16));
        }
        return builder.toString();
    }

    static boolean constantTimeEquals(String left, String right) {
        if (left.length() != right.length()) {
            return false;
        }
        int difference = 0;
        for (int index = 0; index < left.length(); index++) {
            difference |= left.charAt(index) ^ right.charAt(index);
        }
        return difference == 0;
    }
}
