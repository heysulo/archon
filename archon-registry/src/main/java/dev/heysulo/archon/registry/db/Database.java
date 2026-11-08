package dev.heysulo.archon.registry.db;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Properties;

/**
 * Where the registry's database is, read from the environment. The URL names the archon schema, for example
 * {@code jdbc:mysql://mysql.heysulo.internal:3306/archon}. The user and password come from a secret.
 */
public final class Database {
    public static final String URL_ENV = "ARCHON_DB_URL";
    public static final String USER_ENV = "ARCHON_DB_USER";
    public static final String PASSWORD_ENV = "ARCHON_DB_PASSWORD";
    private static final String CONNECT_TIMEOUT_MILLIS = "5000";
    private static final String QUERY_TIMEOUT_MILLIS = "10000";

    private final String url;
    private final String user;
    private final String password;

    public Database(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** The configured database, or empty when {@value #URL_ENV} is not set. */
    public static Optional<Database> fromEnvironment() {
        String url = System.getenv(URL_ENV);
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new Database(url.trim(), System.getenv(USER_ENV), System.getenv(PASSWORD_ENV)));
    }

    public String getUrl() {
        return url;
    }

    // Bounded, so an unreachable database fails the registry's startup instead of hanging it
    public Connection connect() throws SQLException {
        Properties properties = new Properties();
        if (user != null) {
            properties.setProperty("user", user);
        }
        if (password != null) {
            properties.setProperty("password", password);
        }
        properties.setProperty("connectTimeout", CONNECT_TIMEOUT_MILLIS);
        properties.setProperty("socketTimeout", QUERY_TIMEOUT_MILLIS);
        return DriverManager.getConnection(url, properties);
    }

    @Override
    public String toString() {
        // Never the password
        return url + " as " + user;
    }
}
