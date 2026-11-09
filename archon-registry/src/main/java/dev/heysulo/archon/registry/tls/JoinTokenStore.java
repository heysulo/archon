package dev.heysulo.archon.registry.tls;

import dev.heysulo.archon.dictionary.tls.ArchonIdentity;
import dev.heysulo.archon.dictionary.tls.JoinTokens;
import dev.heysulo.archon.registry.db.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;

/**
 * Checks join tokens against the {@code join_tokens} table, which the portal fills. A single-use token is spent by
 * the first request that presents it; a reusable one, for instances that scale out, until it expires or is removed.
 */
public final class JoinTokenStore {
    private final Database database;

    public JoinTokenStore(Database database) {
        this.database = database;
    }

    /** Creates the table if it is missing. */
    public static void prepare(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS join_tokens ("
                    + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY, "
                    + "app_group VARCHAR(128) NOT NULL, "
                    + "app_name VARCHAR(128) NOT NULL, "
                    + "token_hash CHAR(64) NOT NULL, "
                    + "description VARCHAR(255) NOT NULL DEFAULT '', "
                    + "reusable BOOLEAN NOT NULL DEFAULT FALSE, "
                    + "expires_at TIMESTAMP NULL, "
                    + "used_at TIMESTAMP NULL, "
                    + "use_count INT NOT NULL DEFAULT 0, "
                    + "created_by VARCHAR(128) NOT NULL DEFAULT '', "
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, "
                    + "UNIQUE KEY uq_join_tokens_hash (token_hash))");
        }
    }

    /**
     * Spends a token for an identity.
     *
     * @return why it was refused, or empty if it is good for this identity
     */
    public Optional<String> redeem(String token, ArchonIdentity identity) throws SQLException {
        if (token == null || token.isBlank()) {
            return Optional.of("A join token is needed");
        }
        String hash = JoinTokens.hash(token);
        try (Connection connection = database.connect()) {
            Row row = find(connection, hash);
            // One answer for every bad token, so the answer does not reveal which tokens exist
            if (row == null || !identity.is(row.group(), row.name())) {
                return Optional.of("The join token is not valid for " + identity);
            }
            if (row.expiresAt() != null && row.expiresAt().isBefore(Instant.now())) {
                return Optional.of("The join token has expired");
            }
            // Conditional, so two requests racing for a single-use token cannot both have it
            try (PreparedStatement spend = connection.prepareStatement("UPDATE join_tokens SET used_at = ?, "
                    + "use_count = use_count + 1 WHERE id = ? AND (reusable OR used_at IS NULL)")) {
                spend.setTimestamp(1, Timestamp.from(Instant.now()));
                spend.setLong(2, row.id());
                if (spend.executeUpdate() == 0) {
                    return Optional.of("The join token has been used");
                }
            }
            return Optional.empty();
        }
    }

    private record Row(long id, String group, String name, Instant expiresAt) {
    }

    private static Row find(Connection connection, String hash) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT id, app_group, app_name, expires_at FROM join_tokens WHERE token_hash = ?")) {
            select.setString(1, hash);
            try (ResultSet result = select.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                Timestamp expires = result.getTimestamp("expires_at");
                return new Row(result.getLong("id"), result.getString("app_group"), result.getString("app_name"),
                        expires == null ? null : expires.toInstant());
            }
        }
    }
}
