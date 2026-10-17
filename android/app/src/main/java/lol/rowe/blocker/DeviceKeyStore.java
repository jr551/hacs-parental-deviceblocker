package lol.rowe.blocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.security.KeyStore;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * Stores the Home Assistant device API key in app-private preferences.
 *
 * <p>Builds up to 0.4.13 sealed the key with an Android Keystore AES key in
 * {@code device_key_enc} and removed the plaintext. This build stores the
 * plaintext only, so {@link #read} unseals a leftover sealed value once and
 * rewrites it as plaintext. Without that migration an upgraded phone reads an
 * empty key, decides it is not configured, and silently stops enforcing and
 * stops checking in.
 */
final class DeviceKeyStore {
    static final String PREFS = "rowe_blocker";
    static final String PLAINTEXT = "device_key";
    static final String LEGACY_SEALED = "device_key_enc";
    static final String LEGACY_UNREADABLE = "device_key_sealed_unreadable";
    static final String LEGACY_UNAVAILABLE = "device_key_keystore_unavailable";
    /** Byte-identical to the alias older builds used, or the seal cannot be opened. */
    private static final String ALIAS = "lol.rowe.blocker.device_key";

    /**
     * The preferences this store needs. The migration is unit-tested through
     * this seam because the module has no Android framework in its tests.
     */
    interface Prefs {
        String getString(String name, String fallback);

        boolean getBoolean(String name, boolean fallback);

        Editor edit();

        interface Editor {
            Editor putString(String name, String value);

            Editor putBoolean(String name, boolean value);

            Editor remove(String name);

            void apply();
        }
    }

    /** Supplies the AES key a legacy sealed value was written with. */
    interface KeySource {
        SecretKey key() throws Exception;
    }

    private DeviceKeyStore() {
    }

    static String read(Context context) {
        return read(androidPrefs(context), DeviceKeyStore::keystoreKey);
    }

    /**
     * The plaintext key, migrating a value sealed by an older build.
     *
     * <p>A sealed value that cannot be opened is dropped and flagged, exactly
     * as 0.4.13 did: the parent has to run setup again rather than the phone
     * quietly running with no key at all.
     */
    static String read(Prefs prefs, KeySource keys) {
        String plaintext = prefs.getString(PLAINTEXT, "");
        if (plaintext != null && !plaintext.isEmpty()) {
            return plaintext;
        }
        String sealed = prefs.getString(LEGACY_SEALED, "");
        if (sealed == null || sealed.isEmpty()) {
            return "";
        }
        String value;
        try {
            value = AesGcmSealer.open(keys.key(), sealed);
        } catch (Exception exception) {
            value = null;
        }
        if (value == null || value.isEmpty()) {
            prefs.edit()
                    .putBoolean(LEGACY_UNREADABLE, true)
                    .remove(LEGACY_SEALED)
                    .apply();
            return "";
        }
        // write() stores the plaintext and drops the sealed value in one edit.
        write(prefs, value);
        return value;
    }

    /**
     * Always stores the key in private preferences and returns true.
     * Leftover keystore flags from older builds are cleared so the setup
     * screen cannot keep reporting a seal failure.
     */
    static boolean write(Context context, String deviceKey) {
        write(androidPrefs(context), deviceKey);
        return true;
    }

    static void write(Prefs prefs, String deviceKey) {
        String value = deviceKey == null ? "" : deviceKey;
        Prefs.Editor editor = prefs.edit();
        if (value.isEmpty()) {
            editor.remove(PLAINTEXT);
        } else {
            editor.putString(PLAINTEXT, value);
        }
        editor.remove(LEGACY_SEALED)
                .putBoolean(LEGACY_UNREADABLE, false)
                .putBoolean(LEGACY_UNAVAILABLE, false)
                .apply();
    }

    private static Prefs androidPrefs(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return new Prefs() {
            @Override
            public String getString(String name, String fallback) {
                return preferences.getString(name, fallback);
            }

            @Override
            public boolean getBoolean(String name, boolean fallback) {
                return preferences.getBoolean(name, fallback);
            }

            @Override
            public Editor edit() {
                SharedPreferences.Editor editor = preferences.edit();
                return new Editor() {
                    @Override
                    public Editor putString(String name, String value) {
                        editor.putString(name, value);
                        return this;
                    }

                    @Override
                    public Editor putBoolean(String name, boolean value) {
                        editor.putBoolean(name, value);
                        return this;
                    }

                    @Override
                    public Editor remove(String name) {
                        editor.remove(name);
                        return this;
                    }

                    @Override
                    public void apply() {
                        editor.apply();
                    }
                };
            }
        };
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
