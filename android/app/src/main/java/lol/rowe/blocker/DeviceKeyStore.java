package lol.rowe.blocker;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Stores the Home Assistant device API key in app-private preferences.
 * Android Keystore is not used. Some phones refuse to generate a keystore
 * key, and a failed seal used to block Save even when the key itself was fine.
 */
final class DeviceKeyStore {
    static final String PREFS = "rowe_blocker";
    static final String PLAINTEXT = "device_key";
    private static final String LEGACY_SEALED = "device_key_enc";
    private static final String LEGACY_UNREADABLE = "device_key_sealed_unreadable";
    private static final String LEGACY_UNAVAILABLE = "device_key_keystore_unavailable";

    private DeviceKeyStore() {
    }

    static String read(Context context) {
        String value = preferences(context).getString(PLAINTEXT, "");
        return value == null ? "" : value;
    }

    /**
     * Always stores the key in private preferences and returns true.
     * Leftover keystore flags from older builds are cleared so the setup
     * screen cannot keep reporting a seal failure.
     */
    static boolean write(Context context, String deviceKey) {
        String value = deviceKey == null ? "" : deviceKey;
        SharedPreferences.Editor editor = preferences(context).edit();
        if (value.isEmpty()) {
            editor.remove(PLAINTEXT);
        } else {
            editor.putString(PLAINTEXT, value);
        }
        editor.remove(LEGACY_SEALED)
                .putBoolean(LEGACY_UNREADABLE, false)
                .putBoolean(LEGACY_UNAVAILABLE, false)
                .apply();
        return true;
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
