package dev.heysulo.archon.portal.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * The portal's connection pool to the archon schema, configured like the registry's: {@code ARCHON_DB_URL},
 * {@code ARCHON_DB_USER} and {@code ARCHON_DB_PASSWORD}.
 */
public final class PortalDatabase implements AutoCloseable {
    public static final String URL_ENV = "ARCHON_DB_URL";
    public static final String USER_ENV = "ARCHON_DB_USER";
    public static final String PASSWORD_ENV = "ARCHON_DB_PASSWORD";
    private static final String SCHEMA = "/db/portal-schema.sql";
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalDatabase.class);
    private static final int MIGRATE_ATTEMPTS = 10;
    private static final long MIGRATE_RETRY_MILLIS = 3_000;

    private final HikariDataSource dataSource;
    private final String description;

    public PortalDatabase(String url, String user, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setPoolName("portal-db");
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        // Fail later, on use, rather than in the constructor: startup retries below
        config.setInitializationFailTimeout(-1);
        config.addDataSourceProperty("connectTimeout", "5000");
        config.addDataSourceProperty("socketTimeout", "10000");
        this.dataSource = new HikariDataSource(config);
        // Never the password
        this.description = url + " as " + user;
    }

    /**
     * @throws IllegalStateException if {@value #URL_ENV} is not set. Signing in needs the database.
     */
    public static PortalDatabase fromEnvironment() {
        String url = System.getenv(URL_ENV);
        if (url == null || url.isBlank()) {
            throw new IllegalStateException(URL_ENV + " is not set; the portal stores its users in the archon schema");
        }
        return new PortalDatabase(url.trim(), System.getenv(USER_ENV), System.getenv(PASSWORD_ENV));
    }

    public DataSource dataSource() {
        return dataSource;
    }

    /**
     * Creates the portal's tables if they are missing. Retries for a while, as the database may still be starting.
     *
     * @throws IllegalStateException if the database stays unreachable or the schema cannot be applied
     */
    public void migrate() throws InterruptedException {
        List<String> statements = statements();
        SQLException lastFailure = null;
        for (int attempt = 1; attempt <= MIGRATE_ATTEMPTS; attempt++) {
            try (Connection connection = dataSource.getConnection();
                 Statement statement = connection.createStatement()) {
                for (String sql : statements) {
                    statement.execute(sql);
                }
                LOGGER.info("Portal tables ready in {}", description);
                return;
            } catch (SQLException e) {
                lastFailure = e;
                LOGGER.warn("Cannot prepare the portal tables in {} (attempt {} of {}): {}", description, attempt,
                        MIGRATE_ATTEMPTS, e.getMessage());
                if (attempt < MIGRATE_ATTEMPTS) {
                    Thread.sleep(MIGRATE_RETRY_MILLIS);
                }
            }
        }
        throw new IllegalStateException("Cannot prepare the portal tables in " + description, lastFailure);
    }

    /** The schema's statements: everything between semicolons, without comment lines. */
    static List<String> statements() {
        String script;
        try (InputStream in = PortalDatabase.class.getResourceAsStream(SCHEMA)) {
            if (in == null) {
                throw new IllegalStateException("Missing " + SCHEMA);
            }
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + SCHEMA, e);
        }
        StringBuilder current = new StringBuilder();
        List<String> statements = new ArrayList<>();
        for (String line : script.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            current.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                String sql = current.toString().trim();
                statements.add(sql.substring(0, sql.length() - 1));
                current.setLength(0);
            }
        }
        return statements;
    }

    @Override
    public void close() {
        dataSource.close();
    }

    @Override
    public String toString() {
        return description;
    }
}
