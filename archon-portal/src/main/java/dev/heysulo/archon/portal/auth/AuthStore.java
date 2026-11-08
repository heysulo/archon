package dev.heysulo.archon.portal.auth;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Users, roles, sessions and setup tokens in the portal's tables. Times are stored as UTC. */
public final class AuthStore {
    private final DataSource dataSource;

    public AuthStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    // ---------------- users ----------------

    public int countUsers() {
        return query("SELECT COUNT(*) FROM portal_users", statement -> { }, result -> {
            result.next();
            return result.getInt(1);
        });
    }

    public Optional<UserRecord> findUserByUsername(String username) {
        return users("WHERE u.username = ?", statement -> statement.setString(1, username)).stream().findFirst();
    }

    public Optional<UserRecord> findUserById(long id) {
        return users("WHERE u.id = ?", statement -> statement.setLong(1, id)).stream().findFirst();
    }

    public List<UserRecord> listUsers() {
        return users("", statement -> { });
    }

    public long createUser(String username, String displayName, String passwordHash, Collection<String> roles,
            Instant now) {
        return transaction(connection -> {
            long id;
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO portal_users (username, "
                    + "display_name, password_hash, enabled, created_at, updated_at, password_changed_at) "
                    + "VALUES (?, ?, ?, TRUE, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
                insert.setString(1, username);
                insert.setString(2, displayName);
                insert.setString(3, passwordHash);
                setTime(insert, 4, now);
                setTime(insert, 5, now);
                setTime(insert, 6, now);
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
            }
            replaceRoles(connection, id, roles);
            return id;
        });
    }

    public void updateUser(long id, String displayName, boolean enabled, Collection<String> roles, Instant now) {
        transaction(connection -> {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE portal_users SET display_name = ?, enabled = ?, updated_at = ? WHERE id = ?")) {
                update.setString(1, displayName);
                update.setBoolean(2, enabled);
                setTime(update, 3, now);
                update.setLong(4, id);
                update.executeUpdate();
            }
            replaceRoles(connection, id, roles);
            return null;
        });
    }

    public void setPasswordHash(long id, String passwordHash, Instant now) {
        update("UPDATE portal_users SET password_hash = ?, password_changed_at = ?, updated_at = ? WHERE id = ?",
                statement -> {
                    statement.setString(1, passwordHash);
                    setTime(statement, 2, now);
                    setTime(statement, 3, now);
                    statement.setLong(4, id);
                });
    }

    public void recordLogin(long id, Instant now) {
        update("UPDATE portal_users SET last_login_at = ? WHERE id = ?", statement -> {
            setTime(statement, 1, now);
            statement.setLong(2, id);
        });
    }

    public void deleteUser(long id) {
        update("DELETE FROM portal_users WHERE id = ?", statement -> statement.setLong(1, id));
    }

    private void replaceRoles(Connection connection, long userId, Collection<String> roles) throws SQLException {
        try (PreparedStatement delete =
                connection.prepareStatement("DELETE FROM portal_user_roles WHERE user_id = ?")) {
            delete.setLong(1, userId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO portal_user_roles (user_id, role_id) VALUES (?, ?)")) {
            for (String role : new HashSet<>(roles)) {
                insert.setLong(1, userId);
                insert.setString(2, role);
                insert.addBatch();
            }
            insert.executeBatch();
        }
    }

    private List<UserRecord> users(String where, Binder binder) {
        String sql = "SELECT u.id, u.username, u.display_name, u.password_hash, u.enabled, u.created_at, "
                + "u.last_login_at, r.role_id FROM portal_users u "
                + "LEFT JOIN portal_user_roles r ON r.user_id = u.id " + where + " ORDER BY u.username, r.role_id";
        return query(sql, binder, result -> {
            Map<Long, UserRecord> users = new LinkedHashMap<>();
            Map<Long, Set<String>> roles = new HashMap<>();
            while (result.next()) {
                long id = result.getLong("id");
                if (!users.containsKey(id)) {
                    users.put(id, new UserRecord(id, result.getString("username"), result.getString("display_name"),
                            result.getString("password_hash"), result.getBoolean("enabled"), Set.of(),
                            getTime(result, "created_at"), getTime(result, "last_login_at")));
                    roles.put(id, new HashSet<>());
                }
                String role = result.getString("role_id");
                if (role != null) {
                    roles.get(id).add(role);
                }
            }
            List<UserRecord> list = new ArrayList<>();
            users.forEach((id, user) -> list.add(new UserRecord(user.id(), user.username(), user.displayName(),
                    user.passwordHash(), user.enabled(), roles.get(id), user.createdAt(), user.lastLoginAt())));
            return list;
        });
    }

    // ---------------- roles ----------------

    public List<RoleRecord> listRoles() {
        return query("SELECT r.id, r.description, p.permission FROM portal_roles r "
                + "LEFT JOIN portal_role_permissions p ON p.role_id = r.id ORDER BY r.id, p.permission",
                statement -> { }, result -> {
                    Map<String, String> descriptions = new LinkedHashMap<>();
                    Map<String, Set<String>> permissions = new HashMap<>();
                    while (result.next()) {
                        String id = result.getString("id");
                        descriptions.putIfAbsent(id, result.getString("description"));
                        Set<String> granted = permissions.computeIfAbsent(id, key -> new HashSet<>());
                        String permission = result.getString("permission");
                        if (permission != null) {
                            granted.add(permission);
                        }
                    }
                    List<RoleRecord> roles = new ArrayList<>();
                    descriptions.forEach((id, description) ->
                            roles.add(new RoleRecord(id, description, permissions.get(id))));
                    return roles;
                });
    }

    // ---------------- sessions ----------------

    public void createSession(String tokenHash, long userId, Instant now, Instant expiresAt) {
        update("INSERT INTO portal_sessions (token_hash, user_id, created_at, last_seen_at, expires_at) "
                + "VALUES (?, ?, ?, ?, ?)", statement -> {
                    statement.setString(1, tokenHash);
                    statement.setLong(2, userId);
                    setTime(statement, 3, now);
                    setTime(statement, 4, now);
                    setTime(statement, 5, expiresAt);
                });
    }

    public Optional<SessionRecord> findSession(String tokenHash) {
        return query("SELECT token_hash, user_id, created_at, last_seen_at, expires_at FROM portal_sessions "
                + "WHERE token_hash = ?", statement -> statement.setString(1, tokenHash), result -> result.next()
                ? Optional.of(new SessionRecord(result.getString("token_hash"), result.getLong("user_id"),
                        getTime(result, "created_at"), getTime(result, "last_seen_at"), getTime(result, "expires_at")))
                : Optional.empty());
    }

    public void touchSession(String tokenHash, Instant now, Instant expiresAt) {
        update("UPDATE portal_sessions SET last_seen_at = ?, expires_at = ? WHERE token_hash = ?", statement -> {
            setTime(statement, 1, now);
            setTime(statement, 2, expiresAt);
            statement.setString(3, tokenHash);
        });
    }

    public void deleteSession(String tokenHash) {
        update("DELETE FROM portal_sessions WHERE token_hash = ?", statement -> statement.setString(1, tokenHash));
    }

    /** Signs a user out everywhere, except, if given, the session they are using. */
    public void deleteSessionsOf(long userId, String keepTokenHash) {
        update("DELETE FROM portal_sessions WHERE user_id = ? AND token_hash <> ?", statement -> {
            statement.setLong(1, userId);
            statement.setString(2, keepTokenHash == null ? "" : keepTokenHash);
        });
    }

    public int deleteExpired(Instant now) {
        int sessions = update("DELETE FROM portal_sessions WHERE expires_at <= ?",
                statement -> setTime(statement, 1, now));
        int tokens = update("DELETE FROM portal_setup_tokens WHERE expires_at <= ?",
                statement -> setTime(statement, 1, now));
        return sessions + tokens;
    }

    // ---------------- setup tokens ----------------

    public void addSetupToken(String tokenHash, Instant now, Instant expiresAt) {
        update("INSERT INTO portal_setup_tokens (token_hash, created_at, expires_at) VALUES (?, ?, ?)", statement -> {
            statement.setString(1, tokenHash);
            setTime(statement, 2, now);
            setTime(statement, 3, expiresAt);
        });
    }

    public boolean isSetupTokenValid(String tokenHash, Instant now) {
        return query("SELECT COUNT(*) FROM portal_setup_tokens WHERE token_hash = ? AND expires_at > ?", statement -> {
            statement.setString(1, tokenHash);
            setTime(statement, 2, now);
        }, result -> {
            result.next();
            return result.getInt(1) > 0;
        });
    }

    public void clearSetupTokens() {
        update("DELETE FROM portal_setup_tokens", statement -> { });
    }

    // ---------------- plumbing ----------------

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement statement) throws SQLException;
    }

    @FunctionalInterface
    private interface Reader<T> {
        T read(ResultSet result) throws SQLException;
    }

    @FunctionalInterface
    private interface Work<T> {
        T run(Connection connection) throws SQLException;
    }

    private <T> T query(String sql, Binder binder, Reader<T> reader) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            try (ResultSet result = statement.executeQuery()) {
                return reader.read(result);
            }
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private int update(String sql, Binder binder) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            binder.bind(statement);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private <T> T transaction(Work<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException e) {
                connection.rollback();
                throw e;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new StoreException(e);
        }
    }

    private static void setTime(PreparedStatement statement, int index, Instant time) throws SQLException {
        statement.setObject(index, LocalDateTime.ofInstant(time, ZoneOffset.UTC));
    }

    private static Instant getTime(ResultSet result, String column) throws SQLException {
        LocalDateTime time = result.getObject(column, LocalDateTime.class);
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }

    /** The database failed. Callers answer 503; the message stays in the logs. */
    public static final class StoreException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        StoreException(SQLException cause) {
            super("Database error: " + cause.getMessage(), cause);
        }

        /** A unique key was violated, such as a username already taken. */
        public boolean isDuplicate() {
            SQLException cause = (SQLException) getCause();
            return cause.getSQLState() != null && cause.getSQLState().startsWith("23");
        }
    }
}
