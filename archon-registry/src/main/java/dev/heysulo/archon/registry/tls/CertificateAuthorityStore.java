package dev.heysulo.archon.registry.tls;

import dev.heysulo.archon.dictionary.tls.Certificates;
import dev.heysulo.archon.registry.db.Database;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;

/**
 * Keeps the CA in the {@code certificate_authority} table, its private key sealed with the master key, so every
 * registry signs with the same CA and a failover does not lose it. The first registry to start with TLS creates it.
 */
final class CertificateAuthorityStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(CertificateAuthorityStore.class);

    private CertificateAuthorityStore() {
    }

    static void prepare(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS certificate_authority ("
                    + "id TINYINT NOT NULL PRIMARY KEY, "
                    + "certificate TEXT NOT NULL, "
                    + "sealed_key TEXT NOT NULL, "
                    + "created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
        }
    }

    /**
     * Loads the CA, creating it if there is none yet.
     *
     * @throws GeneralSecurityException if the stored key cannot be opened, as with the wrong master key
     */
    static CertificateAuthority loadOrCreate(Database database, MasterKey masterKey)
            throws SQLException, GeneralSecurityException {
        try (Connection connection = database.connect()) {
            prepare(connection);
            CertificateAuthority existing = load(connection, masterKey);
            if (existing != null) {
                return existing;
            }
            CertificateAuthority created = CertificateAuthority.create();
            // IGNORE: another registry starting at the same moment may create one too; the stored one wins
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT IGNORE INTO certificate_authority (id, certificate, sealed_key) VALUES (1, ?, ?)")) {
                insert.setString(1, Certificates.encode(created.certificate()));
                insert.setString(2, masterKey.seal(created.key().getEncoded()));
                if (insert.executeUpdate() == 1) {
                    LOGGER.info("Created the Archon CA. Fingerprint {}", Certificates.fingerprint(created.certificate()));
                }
            }
            return load(connection, masterKey);
        }
    }

    private static CertificateAuthority load(Connection connection, MasterKey masterKey)
            throws SQLException, GeneralSecurityException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT certificate, sealed_key FROM certificate_authority WHERE id = 1");
             ResultSet result = select.executeQuery()) {
            if (!result.next()) {
                return null;
            }
            X509Certificate certificate = Certificates.decode(result.getString("certificate"));
            byte[] encoded;
            try {
                encoded = masterKey.open(result.getString("sealed_key"));
            } catch (GeneralSecurityException e) {
                throw new GeneralSecurityException("Cannot open the CA's key. Is " + MasterKey.ENV
                        + " the one it was created with?", e);
            }
            try {
                PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(encoded));
                return new CertificateAuthority(key, certificate);
            } finally {
                Arrays.fill(encoded, (byte) 0);
            }
        }
    }
}
