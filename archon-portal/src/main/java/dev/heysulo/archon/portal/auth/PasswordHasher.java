package dev.heysulo.archon.portal.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Hashes passwords with PBKDF2-HMAC-SHA256, built into the JDK. A stored hash reads
 * {@code pbkdf2-sha256$<iterations>$<salt>$<hash>}, so the iteration count can rise later without breaking existing
 * accounts.
 */
public final class PasswordHasher {
    /** OWASP's recommendation for PBKDF2-HMAC-SHA256. */
    public static final int DEFAULT_ITERATIONS = 600_000;
    private static final String ALGORITHM = "pbkdf2-sha256";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BITS = 256;

    private final SecureRandom random = new SecureRandom();
    private final int iterations;

    public PasswordHasher() {
        this(DEFAULT_ITERATIONS);
    }

    /** Tests use far fewer iterations to stay fast. */
    public PasswordHasher(int iterations) {
        this.iterations = iterations;
    }

    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] key = derive(password, salt, iterations);
        Base64.Encoder base64 = Base64.getEncoder().withoutPadding();
        return ALGORITHM + "$" + iterations + "$" + base64.encodeToString(salt) + "$" + base64.encodeToString(key);
    }

    /** Whether the password matches, comparing in constant time. A malformed stored hash never matches. */
    public boolean verify(String password, String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 4 || !parts[0].equals(ALGORITHM)) {
            return false;
        }
        try {
            int storedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(derive(password, salt, storedIterations), expected);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("PBKDF2WithHmacSHA256 is unavailable", e);
        } finally {
            spec.clearPassword();
        }
    }
}
