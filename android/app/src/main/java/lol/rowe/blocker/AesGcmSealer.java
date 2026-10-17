package lol.rowe.blocker;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * AES-GCM helper kept for unit tests. The device API key is stored in
 * app-private preferences and is not sealed with the Android Keystore.
 */
final class AesGcmSealer {
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private AesGcmSealer() {
    }

    static String seal(SecretKey key, String plaintext) throws Exception {
        byte[] iv = new byte[IV_LENGTH];
        new SecureRandom().nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        return Base64.getEncoder().encodeToString(payload);
    }

    static String open(SecretKey key, String sealed) throws Exception {
        byte[] payload = Base64.getDecoder().decode(sealed);
        if (payload.length <= IV_LENGTH) {
            throw new IllegalArgumentException("sealed device key is truncated");
        }
        byte[] iv = Arrays.copyOfRange(payload, 0, IV_LENGTH);
        byte[] encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    }
}
