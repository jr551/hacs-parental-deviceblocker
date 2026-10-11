package lol.rowe.blocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Stores the Home Assistant device API key in app-private preferences sealed
 * with an Android Keystore AES key. A legacy plaintext value is migrated on
 * the next read and then removed.
 */
final class DeviceKeyStore {
    static final String PREFS = "rowe_blocker";
    static final String PLAINTEXT = "device_key";
    static final String SEALED = "device_key_enc";
    static final String SEALED_UNREADABLE = "device_key_sealed_unreadable";
    private static final String ALIAS = "lol.rowe.blocker.device_key";
    private static final String KEYSTORE_UNAVAILABLE = "device_key_keystore_unavailable";

    private DeviceKeyStore() {
    }

    static String read(Context context) {
        SharedPreferences preferences = preferences(context);
        if (preferences.getBoolean(SEALED_UNREADABLE, false)) {
            return "";
        }
        String sealed = preferences.getString(SEALED, "");
        if (sealed != null && !sealed.isEmpty()) {
            try {
                return AesGcmSealer.open(keystoreKey(), sealed);
            } catch (Exception exception) {
                preferences.edit()
                        .putBoolean(SEALED_UNREADABLE, true)
                        .remove(SEALED)
                        .apply();
                return "";
            }
        }
        String legacy = preferences.getString(PLAINTEXT, "");
        if (legacy == null || legacy.isEmpty()) {
            return "";
        }
        if (write(context, legacy)) {
            return legacy;
        }
        return "";
    }

    static boolean write(Context context, String deviceKey) {
        SharedPreferences preferences = preferences(context);
        String value = deviceKey == null ? "" : deviceKey;
        if (value.isEmpty()) {
            preferences.edit()
                    .remove(PLAINTEXT)
                    .remove(SEALED)
                    .putBoolean(SEALED_UNREADABLE, false)
                    .putBoolean(KEYSTORE_UNAVAILABLE, false)
                    .apply();
            return true;
        }
        try {
            String sealed = AesGcmSealer.seal(keystoreKey(), value);
            preferences.edit()
                    .putString(SEALED, sealed)
                    .remove(PLAINTEXT)
                    .putBoolean(SEALED_UNREADABLE, false)
                    .putBoolean(KEYSTORE_UNAVAILABLE, false)
                    .apply();
            return true;
        } catch (Exception exception) {
            preferences.edit()
                    .remove(PLAINTEXT)
                    .remove(SEALED)
                    .putBoolean(SEALED_UNREADABLE, false)
                    .putBoolean(KEYSTORE_UNAVAILABLE, true)
                    .apply();
            return false;
        }
    }

    static boolean isSealed(Context context) {
        SharedPreferences preferences = preferences(context);
        String sealed = preferences.getString(SEALED, "");
        return sealed != null && !sealed.isEmpty();
    }

    static boolean needsKeyReentry(Context context) {
        return preferences(context).getBoolean(SEALED_UNREADABLE, false);
    }

    static boolean keystoreUnavailable(Context context) {
        return preferences(context).getBoolean(KEYSTORE_UNAVAILABLE, false);
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static SecretKey keystoreKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS)) {
            KeyGenerator generator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build());
            generator.generateKey();
        }
        return (SecretKey) store.getKey(ALIAS, null);
    }
}
