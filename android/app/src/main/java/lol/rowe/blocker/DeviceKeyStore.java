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
    private static final String ALIAS = "lol.rowe.blocker.device_key";
    private static final String KEYSTORE_UNAVAILABLE = "device_key_keystore_unavailable";

    private DeviceKeyStore() {
    }

    static String read(Context context) {
        SharedPreferences preferences = preferences(context);
        String sealed = preferences.getString(SEALED, "");
        if (sealed != null && !sealed.isEmpty()) {
            try {
                return AesGcmSealer.open(keystoreKey(), sealed);
            } catch (Exception exception) {
                return "";
            }
        }
        String legacy = preferences.getString(PLAINTEXT, "");
        if (legacy == null || legacy.isEmpty()) {
            return "";
        }
        write(context, legacy);
        return legacy;
    }

    static void write(Context context, String deviceKey) {
        SharedPreferences preferences = preferences(context);
        String value = deviceKey == null ? "" : deviceKey;
        if (value.isEmpty()) {
            preferences.edit()
                    .remove(PLAINTEXT)
                    .remove(SEALED)
                    .putBoolean(KEYSTORE_UNAVAILABLE, false)
                    .apply();
            return;
        }
        try {
            String sealed = AesGcmSealer.seal(keystoreKey(), value);
            preferences.edit()
                    .putString(SEALED, sealed)
                    .remove(PLAINTEXT)
                    .putBoolean(KEYSTORE_UNAVAILABLE, false)
                    .apply();
        } catch (Exception exception) {
            preferences.edit()
                    .putString(PLAINTEXT, value)
                    .remove(SEALED)
                    .putBoolean(KEYSTORE_UNAVAILABLE, true)
                    .apply();
        }
    }

    static boolean isSealed(Context context) {
        String sealed = preferences(context).getString(SEALED, "");
        return sealed != null && !sealed.isEmpty();
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
