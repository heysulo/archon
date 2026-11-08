package dev.heysulo.archon.portal.auth;

import dev.heysulo.archon.portal.db.PortalDatabase;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

/** A fresh in-memory database in MySQL mode with the portal's schema, and helpers for tests. */
public final class TestDatabase implements AutoCloseable {
    private final PortalDatabase database;

    public TestDatabase() throws InterruptedException {
        database = new PortalDatabase("jdbc:h2:mem:portal-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1", "sa", "");
        database.migrate();
    }

    public AuthStore store() {
        return new AuthStore(database.dataSource());
    }

    /** Runs a statement, such as creating a table another component owns. */
    public void execute(String sql) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    public javax.sql.DataSource dataSource() {
        return database.dataSource();
    }

    /** Adds a role, as an admin will through a future role editor. */
    public void addRole(String id, String... permissions) throws SQLException {
        try (Connection connection = database.dataSource().getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO portal_roles (id, description, created_at) VALUES ('" + id + "', 'test', "
                    + "CURRENT_TIMESTAMP)");
            for (String permission : permissions) {
                statement.execute("INSERT INTO portal_role_permissions (role_id, permission) VALUES ('" + id + "', '"
                        + permission + "')");
            }
        }
    }

    public void migrateAgain() throws InterruptedException {
        database.migrate();
    }

    @Override
    public void close() {
        database.close();
    }

    /** A clock tests move by hand. */
    public static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-11-08T12:00:00Z");

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
