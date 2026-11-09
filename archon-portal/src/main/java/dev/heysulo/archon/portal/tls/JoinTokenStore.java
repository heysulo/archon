package dev.heysulo.archon.portal.tls;

import dev.heysulo.archon.dictionary.tls.JoinTokens;
import dev.heysulo.archon.portal.auth.AuthException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@code join_tokens} table. A registry certifies an instance of an application definition that presents one of
 * its tokens, so creating one is as powerful as starting an instance of that application.
 */
public final class JoinTokenStore {
    static final int MAX_DESCRIPTION = 255;
    static final Duration MAX_LIFETIME = Duration.ofDays(365);
    private static final String COLUMNS = "id, app_group, app_name, description, reusable, expires_at, used_at, "
            + "use_count, created_by, created_at";

    private final DataSource dataSource;

    public JoinTokenStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** A token just created: the token itself, shown this once, and its record. */
    public record Created(String token, JoinTokenRecord record) {
    }

    /** Every token of an application definition, newest first. */
    public List<JoinTokenRecord> list(String group, String name) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement select = connection.prepareStatement("SELECT " + COLUMNS + " FROM join_tokens "
                     + "WHERE app_group = ? AND app_name = ? ORDER BY created_at DESC, id DESC")) {
            select.setString(1, group);
            select.setString(2, name);
            try (ResultSet result = select.executeQuery()) {
                List<JoinTokenRecord> tokens = new ArrayList<>();
                while (result.next()) {
                    tokens.add(read(result));
                }
                return tokens;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot read join tokens: " + e.getMessage(), e);
        }
    }

    /**
     * @param lifetime how long it works, or null for no expiry
     */
    public Created create(String group, String name, String description, boolean reusable, Duration lifetime,
            String createdBy) {
        String text = description == null ? "" : description.trim();
        if (text.length() > MAX_DESCRIPTION) {
            throw new AuthException(400, "Keep the description to " + MAX_DESCRIPTION + " characters");
        }
        if (lifetime != null && (lifetime.isNegative() || lifetime.isZero() || lifetime.compareTo(MAX_LIFETIME) > 0)) {
            throw new AuthException(400, "A token lasts from an hour up to a year, or never expires");
        }
        String token = JoinTokens.newToken();
        try (Connection connection = dataSource.getConnection();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO join_tokens (app_group, app_name, "
                     + "token_hash, description, reusable, expires_at, created_by) VALUES (?, ?, ?, ?, ?, ?, ?)",
                     Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, group);
            insert.setString(2, name);
            insert.setString(3, JoinTokens.hash(token));
            insert.setString(4, text);
            insert.setBoolean(5, reusable);
            insert.setTimestamp(6, lifetime == null ? null : Timestamp.from(Instant.now().plus(lifetime)));
            insert.setString(7, createdBy);
            insert.executeUpdate();
            long id;
            try (ResultSet keys = insert.getGeneratedKeys()) {
                keys.next();
                id = keys.getLong(1);
            }
            return new Created(token, find(connection, id));
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot create a join token: " + e.getMessage(), e);
        }
    }

    /** Revokes a token: instances can no longer join with it. Those that joined keep their certificates. */
    public void delete(String group, String name, long id) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement delete = connection.prepareStatement(
                     "DELETE FROM join_tokens WHERE id = ? AND app_group = ? AND app_name = ?")) {
            delete.setLong(1, id);
            delete.setString(2, group);
            delete.setString(3, name);
            if (delete.executeUpdate() == 0) {
                throw new AuthException(404, "No such join token");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Cannot revoke a join token: " + e.getMessage(), e);
        }
    }

    private static JoinTokenRecord find(Connection connection, long id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT " + COLUMNS + " FROM join_tokens WHERE id = ?")) {
            select.setLong(1, id);
            try (ResultSet result = select.executeQuery()) {
                result.next();
                return read(result);
            }
        }
    }

    private static JoinTokenRecord read(ResultSet result) throws SQLException {
        return new JoinTokenRecord(result.getLong("id"), result.getString("app_group"), result.getString("app_name"),
                result.getString("description"), result.getBoolean("reusable"), time(result, "expires_at"),
                time(result, "used_at"), result.getInt("use_count"), result.getString("created_by"),
                time(result, "created_at"));
    }

    private static String time(ResultSet result, String column) throws SQLException {
        Timestamp timestamp = result.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
