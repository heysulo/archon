package dev.heysulo.archon.registry.auth;

import java.security.SecureRandom;
import java.util.Base64;

public class AuthenticationManager {
    private static final int DEFAULT_TOKEN_LENGTH = 32;
    private static AuthenticationManager instance;
    private final SecureRandom secureRandom = new SecureRandom();

    public static synchronized AuthenticationManager getInstance() {
        if (instance == null) {
            instance = new AuthenticationManager();
        }
        return instance;
    }

    private AuthenticationManager() {
    }

    public String generateSecureToken() {
        byte[] tokenBytes = new byte[DEFAULT_TOKEN_LENGTH];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
