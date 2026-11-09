package dev.heysulo.archon.dictionary.tls;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Join tokens let an instance of an application definition get its first certificate. Only their SHA-256 hash is
 * stored, in the {@code join_tokens} table, so the table does not hold anything an instance could join with.
 */
public final class JoinTokens {
    public static final String PREFIX = "ajt_";
    private static final int TOKEN_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private JoinTokens() {
    }

    public static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.trim().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Creates a single-use token for one of Archon's own components that can already reach the archon schema, such
     * as the portal. Holding the database already trusts them with everything a token gives.
     *
     * @return the token, valid for {@code lifetime}
     */
    public static String mintForComponent(Connection connection, ArchonIdentity identity, Duration lifetime)
            throws SQLException {
        String token = newToken();
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO join_tokens (app_group, app_name, "
                + "token_hash, description, reusable, expires_at, created_by) VALUES (?, ?, ?, ?, FALSE, ?, ?)")) {
            insert.setString(1, identity.group());
            insert.setString(2, identity.name());
            insert.setString(3, hash(token));
            insert.setString(4, "Minted at startup by " + identity);
            insert.setTimestamp(5, Timestamp.from(Instant.now().plus(lifetime)));
            insert.setString(6, identity.toString());
            insert.executeUpdate();
        }
        return token;
    }

    /** Reads the Archon CA's certificate, the public half only, if a registry has created the CA. */
    public static Optional<String> readCaCertificate(Connection connection) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT certificate FROM certificate_authority WHERE id = 1");
             ResultSet result = select.executeQuery()) {
            return result.next() ? Optional.of(result.getString("certificate")) : Optional.empty();
        }
    }
}
