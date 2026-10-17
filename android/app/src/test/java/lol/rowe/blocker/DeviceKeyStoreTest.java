package lol.rowe.blocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

/**
 * The upgrade path from a build that sealed the device key with the Android
 * Keystore. A phone that cannot read its key decides it is not configured and
 * silently stops enforcing, so this migration is load-bearing.
 */
public final class DeviceKeyStoreTest {
    /** In-memory preferences; edits only land on apply(), like SharedPreferences. */
    private static final class FakePrefs implements DeviceKeyStore.Prefs {
        private final Map<String, Object> values = new HashMap<>();

        FakePrefs put(String name, String value) {
            values.put(name, value);
            return this;
        }

        FakePrefs put(String name, boolean value) {
            values.put(name, value);
            return this;
        }

        @Override
        public String getString(String name, String fallback) {
            Object value = values.get(name);
            return value instanceof String ? (String) value : fallback;
        }

        @Override
        public boolean getBoolean(String name, boolean fallback) {
            Object value = values.get(name);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }

        @Override
        public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>();
                private final Set<String> removals = new HashSet<>();

                @Override
                public Editor putString(String name, String value) {
                    pending.put(name, value);
                    removals.remove(name);
                    return this;
                }

                @Override
                public Editor putBoolean(String name, boolean value) {
                    pending.put(name, value);
                    removals.remove(name);
                    return this;
                }

                @Override
                public Editor remove(String name) {
                    pending.remove(name);
                    removals.add(name);
                    return this;
                }

                @Override
                public void apply() {
                    for (String name : removals) {
                        values.remove(name);
                    }
                    values.putAll(pending);
                }
            };
        }

        boolean has(String name) {
            return values.containsKey(name);
        }
    }

    private static final String DEVICE_KEY = "device-key-from-an-older-build";

    private static SecretKey aesKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        return generator.generateKey();
    }

    @Test
    public void aSealedKeyFromAnOlderBuildIsMigratedToPlaintext() throws Exception {
        SecretKey key = aesKey();
        FakePrefs prefs = new FakePrefs()
                .put(DeviceKeyStore.LEGACY_SEALED, AesGcmSealer.seal(key, DEVICE_KEY));

        assertEquals(DEVICE_KEY, DeviceKeyStore.read(prefs, () -> key));

        assertEquals(DEVICE_KEY, prefs.getString(DeviceKeyStore.PLAINTEXT, ""));
        assertFalse(prefs.has(DeviceKeyStore.LEGACY_SEALED));
        assertFalse(prefs.getBoolean(DeviceKeyStore.LEGACY_UNREADABLE, false));
    }

    @Test
    public void aMigratedKeyIsReadBackWithoutTheKeystore() throws Exception {
        SecretKey key = aesKey();
        FakePrefs prefs = new FakePrefs()
                .put(DeviceKeyStore.LEGACY_SEALED, AesGcmSealer.seal(key, DEVICE_KEY));

        assertEquals(DEVICE_KEY, DeviceKeyStore.read(prefs, () -> key));
        // The migration persisted the key, so a later read needs no keystore at all.
        assertEquals(DEVICE_KEY, DeviceKeyStore.read(prefs, () -> {
            throw new IllegalStateException("Android Keystore is unavailable");
        }));
    }

    @Test
    public void aSealedKeyAnotherKeyWroteIsDroppedAndFlagged() throws Exception {
        SecretKey writer = aesKey();
        SecretKey reader = aesKey();
        FakePrefs prefs = new FakePrefs()
                .put(DeviceKeyStore.LEGACY_SEALED, AesGcmSealer.seal(writer, DEVICE_KEY));

        assertEquals("", DeviceKeyStore.read(prefs, () -> reader));

        assertFalse(prefs.has(DeviceKeyStore.LEGACY_SEALED));
        assertTrue(prefs.getBoolean(DeviceKeyStore.LEGACY_UNREADABLE, true));
        assertFalse(prefs.has(DeviceKeyStore.PLAINTEXT));
    }

    @Test
    public void aSealedValueThatIsNotReadableAtAllIsDroppedAndFlagged() {
        FakePrefs prefs = new FakePrefs()
                .put(DeviceKeyStore.LEGACY_SEALED, "not-base64!!");

        assertEquals("", DeviceKeyStore.read(prefs, DeviceKeyStoreTest::aesKey));

        assertFalse(prefs.has(DeviceKeyStore.LEGACY_SEALED));
        assertTrue(prefs.getBoolean(DeviceKeyStore.LEGACY_UNREADABLE, true));
    }

    @Test
    public void plaintextIsReturnedWithoutTouchingTheSealedValue() {
        FakePrefs prefs = new FakePrefs()
                .put(DeviceKeyStore.PLAINTEXT, "plain-key")
                .put(DeviceKeyStore.LEGACY_SEALED, "stale-sealed-value");

        assertEquals("plain-key", DeviceKeyStore.read(prefs, DeviceKeyStoreTest::aesKey));

        assertEquals("plain-key", prefs.getString(DeviceKeyStore.PLAINTEXT, ""));
        assertTrue(prefs.has(DeviceKeyStore.LEGACY_SEALED));
    }

    @Test
    public void aPhoneWithNoStoredKeyReadsEmpty() {
        assertEquals("", DeviceKeyStore.read(new FakePrefs(), DeviceKeyStoreTest::aesKey));
    }

    @Test
    public void writeStoresPlaintextAndClearsTheLegacyState() {
        FakePrefs prefs = new FakePrefs()
                .put(DeviceKeyStore.LEGACY_SEALED, "stale-sealed-value")
                .put(DeviceKeyStore.LEGACY_UNREADABLE, true)
                .put(DeviceKeyStore.LEGACY_UNAVAILABLE, true);

        DeviceKeyStore.write(prefs, "fresh-key");

        assertEquals("fresh-key", prefs.getString(DeviceKeyStore.PLAINTEXT, ""));
        assertFalse(prefs.has(DeviceKeyStore.LEGACY_SEALED));
        assertFalse(prefs.getBoolean(DeviceKeyStore.LEGACY_UNREADABLE, false));
        assertFalse(prefs.getBoolean(DeviceKeyStore.LEGACY_UNAVAILABLE, false));
    }

    @Test
    public void writeOfAnEmptyKeyClearsEverything() {
        FakePrefs prefs = new FakePrefs()
                .put(DeviceKeyStore.PLAINTEXT, "old-key")
                .put(DeviceKeyStore.LEGACY_SEALED, "stale-sealed-value");

        DeviceKeyStore.write(prefs, "");

        assertFalse(prefs.has(DeviceKeyStore.PLAINTEXT));
        assertFalse(prefs.has(DeviceKeyStore.LEGACY_SEALED));
    }
}
