package lol.rowe.blocker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

public final class AesGcmSealerTest {
    @Test
    public void sealRoundTripsWithoutEmbeddingTheSecret() throws Exception {
        SecretKey key = aesKey();
        String secret = "device-key-not-stored-in-clear";
        String sealed = AesGcmSealer.seal(key, secret);

        assertEquals(secret, AesGcmSealer.open(key, sealed));
        assertFalse(new String(Base64.getDecoder().decode(sealed), StandardCharsets.ISO_8859_1).contains(secret));
        assertNotEquals(sealed, AesGcmSealer.seal(key, secret));
    }

    @Test
    public void aDifferentKeyCannotOpenTheSeal() throws Exception {
        String sealed = AesGcmSealer.seal(aesKey(), "household-device-key");
        try {
            AesGcmSealer.open(aesKey(), sealed);
        } catch (Exception expected) {
            return;
        }
        throw new AssertionError("a different key opened the sealed device key");
    }

    private static SecretKey aesKey() throws Exception {
        KeyGenerator generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        return generator.generateKey();
    }
}
