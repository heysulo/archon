package dev.heysulo.archon.portal.tls;

import dev.heysulo.archon.dictionary.tls.Certificates;
import dev.heysulo.archon.dictionary.tls.JoinTokens;
import dev.heysulo.archon.dictionary.tls.TlsMode;

import javax.sql.DataSource;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;

/**
 * What applications need to join: whether TLS is on, and the Archon CA's fingerprint and certificate. Read from the
 * database, where the registries keep the CA; nothing secret.
 */
public final class TlsStatus {
    private final DataSource dataSource;
    private final TlsMode mode;

    public TlsStatus(DataSource dataSource, TlsMode mode) {
        this.dataSource = dataSource;
        this.mode = mode;
    }

    /**
     * @param mode        this portal's {@code ARCHON_TLS}, which should match the cluster's
     * @param fingerprint the CA's SHA-256 fingerprint, or null if no registry has created the CA yet
     */
    public record View(String mode, String fingerprint, String certificate, String expiresAt) {
    }

    public View view() {
        Optional<X509Certificate> ca = ca();
        return new View(mode.name().toLowerCase(java.util.Locale.ROOT),
                ca.map(Certificates::fingerprint).orElse(null), ca.map(Certificates::pem).orElse(null),
                ca.map(certificate -> certificate.getNotAfter().toInstant().toString()).orElse(null));
    }

    private Optional<X509Certificate> ca() {
        try (Connection connection = dataSource.getConnection()) {
            Optional<String> encoded = JoinTokens.readCaCertificate(connection);
            return encoded.isEmpty() ? Optional.empty() : Optional.of(Certificates.decode(encoded.get()));
        } catch (SQLException e) {
            // No table yet: no registry has started with TLS
            return Optional.empty();
        } catch (CertificateException e) {
            throw new IllegalStateException("The stored CA certificate is unreadable", e);
        }
    }
}
