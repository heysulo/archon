package dev.heysulo.archon.registry.auth;

import java.security.SecureRandom;
import java.util.Base64;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AuthenticationManager {
    private static final int DEFAULT_TOKEN_LENGTH = 32;
    private static AuthenticationManager instance;
    private static final Logger logger = LoggerFactory.getLogger(AuthenticationManager.class);
    private final SecureRandom secureRandom = new SecureRandom();

    public static AuthenticationManager getInstance() {
        if (instance == null) {
            instance = new AuthenticationManager();
        }
        return instance;
    }

    private AuthenticationManager() {}

    public String generateSecureToken() {
        byte[] tokenBytes = new byte[DEFAULT_TOKEN_LENGTH];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }
}
