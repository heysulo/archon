package dev.heysulo.archon.registry.tls;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Encrypts the CA's private key for storage, so the database alone does not give it away. The key is derived from a
 * passphrase every registry is given ({@code ARCHON_CA_MASTER_KEY}) with PBKDF2, and the private key sealed with
 * AES-256-GCM. Each sealing gets a fresh salt and nonce, stored with it as {@code salt.nonce.ciphertext}.
 */
final class MasterKey {
    static final String ENV = "ARCHON_CA_MASTER_KEY";
    static final int MIN_LENGTH = 16;
    private static final int ITERATIONS = 310_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final char[] passphrase;

    MasterKey(String passphrase) {
        if (passphrase == null || passphrase.length() < MIN_LENGTH) {
            throw new IllegalArgumentException(ENV + " must be at least " + MIN_LENGTH + " characters");
        }
        this.passphrase = passphrase.toCharArray();
    }

    String seal(byte[] secret) throws GeneralSecurityException {
        byte[] salt = random(SALT_BYTES);
        byte[] nonce = random(NONCE_BYTES);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, derive(salt), new GCMParameterSpec(TAG_BITS, nonce));
        Base64.Encoder base64 = Base64.getEncoder();
        return base64.encodeToString(salt) + "." + base64.encodeToString(nonce) + "."
                + base64.encodeToString(cipher.doFinal(secret));
    }

    /** @throws GeneralSecurityException if the passphrase is wrong or the sealed text was altered */
    byte[] open(String sealed) throws GeneralSecurityException {
        String[] parts = sealed.split("\\.");
        if (parts.length != 3) {
            throw new GeneralSecurityException("Not a sealed key");
        }
        Base64.Decoder base64 = Base64.getDecoder();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, derive(base64.decode(parts[0])),
                new GCMParameterSpec(TAG_BITS, base64.decode(parts[1])));
        return cipher.doFinal(base64.decode(parts[2]));
    }

    private SecretKeySpec derive(byte[] salt) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase, salt, ITERATIONS, KEY_BITS);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
            try {
                return new SecretKeySpec(key, "AES");
            } finally {
                Arrays.fill(key, (byte) 0);
            }
        } finally {
            spec.clearPassword();
        }
    }

    private static byte[] random(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
