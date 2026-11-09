package dev.heysulo.archon.registry.tls;

import dev.heysulo.archon.dictionary.sdk.messages.CertificateRequest;
import dev.heysulo.archon.dictionary.sdk.messages.CertificateResponse;
import dev.heysulo.archon.dictionary.tls.ArchonIdentity;
import dev.heysulo.archon.dictionary.tls.ArchonTrustManager;
import dev.heysulo.archon.dictionary.tls.Certificates;
import dev.heysulo.archon.dictionary.tls.TlsMaterial;
import dev.heysulo.archon.dictionary.tls.TlsMode;
import dev.heysulo.archon.registry.applications.ApplicationDefinitions;
import dev.heysulo.archon.registry.db.Database;
import dev.heysulo.databridge.core.common.Connection;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The registry's TLS: the mode from {@code ARCHON_TLS}, the Archon CA it issues certificates with, and its own
 * certificate as {@code Archon:Registry}, renewed while it runs.
 *
 * <p>With TLS on, the registry port speaks TLS. In {@link TlsMode#OPTIONAL} mode it also accepts plain connections and
 * registries connect to one another in the clear, so a cluster can be moved across one component at a time. In
 * {@link TlsMode#REQUIRED} mode every connection is TLS, and every caller but one joining or looking for the primary
 * must present a certificate naming who it is.
 */
public final class RegistryTls {
    static final String LIFETIME_ENV = "ARCHON_CERT_LIFETIME_HOURS";
    static final Duration DEFAULT_LIFETIME = Duration.ofHours(24);
    static final Duration RENEWAL_RETRY = Duration.ofMinutes(1);
    private static final Logger LOGGER = LoggerFactory.getLogger(RegistryTls.class);
    private static volatile RegistryTls current = off();

    private final TlsMode mode;
    private final CertificateAuthority ca;
    private final JoinTokenStore tokens;
    private final Duration lifetime;
    // Spending a token reads and writes the database, which must not hold up a network thread
    private final ExecutorService issuing;
    private final ScheduledExecutorService renewal;
    private volatile TlsMaterial own;
    private volatile SslContext serverContext;
    private volatile SslContext peerContext;

    RegistryTls(TlsMode mode, CertificateAuthority ca, JoinTokenStore tokens, Duration lifetime) {
        this.mode = mode;
        this.ca = ca;
        this.tokens = tokens;
        this.lifetime = lifetime;
        boolean on = mode.enabled();
        this.issuing = on ? Executors.newFixedThreadPool(2, new DefaultThreadFactory("registry-certificates", true))
                : null;
        this.renewal = on ? Executors.newSingleThreadScheduledExecutor(
                new DefaultThreadFactory("registry-certificate-renewal", true)) : null;
    }

    /** TLS off: plain connections, and no certificates issued. */
    public static RegistryTls off() {
        return new RegistryTls(TlsMode.OFF, null, null, DEFAULT_LIFETIME);
    }

    public static RegistryTls current() {
        return current;
    }

    /** For tests. */
    public static void setCurrent(RegistryTls tls) {
        current = tls;
    }

    /**
     * Sets up TLS as the environment says.
     *
     * @throws IllegalStateException if TLS is on without a database or master key, or the CA cannot be loaded
     */
    public static RegistryTls fromEnvironment(Optional<Database> database) {
        TlsMode mode = TlsMode.fromEnvironment();
        if (!mode.enabled()) {
            return current;
        }
        if (database.isEmpty()) {
            throw new IllegalStateException(TlsMode.ENV + " is " + mode + ", but no database holds the CA. Set "
                    + Database.URL_ENV);
        }
        MasterKey masterKey = new MasterKey(System.getenv(MasterKey.ENV));
        CertificateAuthority ca;
        try {
            ca = CertificateAuthorityStore.loadOrCreate(database.get(), masterKey);
        } catch (SQLException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot load the Archon CA: " + e.getMessage(), e);
        }
        RegistryTls tls = start(mode, ca, new JoinTokenStore(database.get()), lifetimeFromEnvironment());
        LOGGER.info("TLS is {}. Archon CA fingerprint {}", mode, Certificates.fingerprint(ca.certificate()));
        current = tls;
        return tls;
    }

    /** Starts TLS with a CA: issues this registry's own certificate and schedules its renewal. */
    public static RegistryTls start(TlsMode mode, CertificateAuthority ca, JoinTokenStore tokens, Duration lifetime) {
        RegistryTls tls = new RegistryTls(mode, ca, tokens, lifetime);
        tls.renewOwn();
        return tls;
    }

    static Duration lifetimeFromEnvironment() {
        String hours = System.getenv(LIFETIME_ENV);
        if (hours == null || hours.isBlank()) {
            return DEFAULT_LIFETIME;
        }
        try {
            long value = Long.parseLong(hours.trim());
            if (value < 1) {
                throw new NumberFormatException();
            }
            return Duration.ofHours(value);
        } catch (NumberFormatException e) {
            throw new IllegalStateException(LIFETIME_ENV + " must be a whole number of hours, at least 1");
        }
    }

    public TlsMode mode() {
        return mode;
    }

    public boolean enabled() {
        return mode.enabled();
    }

    public boolean required() {
        return mode == TlsMode.REQUIRED;
    }

    public CertificateAuthority ca() {
        return ca;
    }

    /** For each new connection to the registry port. Client certificates are checked if presented. */
    public SslContext serverContext() {
        return serverContext;
    }

    /**
     * For connecting to another registry, which must prove it is {@code Archon:Registry}; null while registries still
     * talk in the clear, as in optional mode.
     */
    public SslContext peerContext() {
        return required() ? peerContext : null;
    }

    private void renewOwn() {
        try {
            KeyPair keys = CertificateAuthority.newKeyPair();
            TlsMaterial material = new TlsMaterial(keys.getPrivate(),
                    ca.issue(keys.getPublic(), ArchonIdentity.REGISTRY, lifetime), ca.trust());
            serverContext = material.serverContext(ClientAuth.OPTIONAL, ArchonTrustManager.anyIdentity(ca.trust()));
            peerContext = material.clientContext(ArchonTrustManager.expecting(ca.trust(), ArchonIdentity.REGISTRY));
            own = material;
            Duration wait = Duration.between(Instant.now(), material.renewAt());
            LOGGER.info("This registry's certificate is valid until {}. Renewing in {} minutes", material.notAfter(),
                    wait.toMinutes());
            schedule(wait);
        } catch (GeneralSecurityException | RuntimeException e) {
            if (own == null) {
                throw new IllegalStateException("Cannot issue this registry's certificate: " + e.getMessage(), e);
            }
            LOGGER.error("Cannot renew this registry's certificate, valid until {}. Retrying", own.notAfter(), e);
            schedule(RENEWAL_RETRY);
        }
    }

    private void schedule(Duration wait) {
        renewal.schedule(this::renewOwn, Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS);
    }

    /**
     * Answers a certificate request off the network thread. Any registry answers, primary or mirror, since every
     * one holds the CA.
     */
    public void handle(Connection connection, CertificateRequest request) {
        if (!enabled()) {
            connection.send(CertificateResponse.refused(request.getRequestId(), "TLS is off on this registry"));
            return;
        }
        issuing.execute(() -> {
            CertificateResponse response = answer(connection, request);
            try {
                connection.send(response);
            } catch (RuntimeException e) {
                LOGGER.warn("Cannot answer a certificate request from {}: {}", connection.getRemoteAddress(),
                        e.getMessage());
            }
        });
    }

    CertificateResponse answer(Connection connection, CertificateRequest request) {
        long id = request.getRequestId();
        ArchonIdentity identity;
        try {
            identity = new ArchonIdentity(request.getGroup(), request.getName());
        } catch (IllegalArgumentException e) {
            return CertificateResponse.refused(id, e.getMessage());
        }
        if (identity.equals(ArchonIdentity.REGISTRY)) {
            return refuse(connection, id, identity, "Registries issue their own certificates");
        }
        if (!ApplicationDefinitions.current().isDefined(identity.group(), identity.name())) {
            return refuse(connection, id, identity, identity + " is not a defined application");
        }
        if (request.getJoinToken() == null) {
            // A renewal: the connection must already be secured as this identity
            Optional<ArchonIdentity> peer = ArchonIdentity.of(connection);
            if (peer.isEmpty() || !peer.get().equals(identity)) {
                return refuse(connection, id, identity, "Renewing needs a connection secured as " + identity
                        + "; join with a join token instead");
            }
        } else {
            try {
                Optional<String> refusal = tokens.redeem(request.getJoinToken(), identity);
                if (refusal.isPresent()) {
                    return refuse(connection, id, identity, refusal.get());
                }
            } catch (SQLException e) {
                LOGGER.warn("Cannot check a join token for {}: {}", identity, e.getMessage());
                return CertificateResponse.refused(id, "Cannot check the join token right now");
            }
        }
        try {
            PublicKey key = Certificates.decodePublicKey(request.getPublicKey());
            var certificate = ca.issue(key, identity, lifetime);
            LOGGER.info("Issued a certificate for {} to {}, valid until {}{}", identity, connection.getRemoteAddress(),
                    certificate.getNotAfter().toInstant(), request.getJoinToken() == null ? " (renewal)" : "");
            return CertificateResponse.issued(id, Certificates.encode(certificate), Certificates.encode(ca.certificate()));
        } catch (InvalidKeySpecException e) {
            return refuse(connection, id, identity, "The public key is not valid");
        } catch (GeneralSecurityException e) {
            LOGGER.error("Cannot issue a certificate for {}", identity, e);
            return CertificateResponse.refused(id, "Cannot issue the certificate");
        }
    }

    private static CertificateResponse refuse(Connection connection, long id, ArchonIdentity identity, String why) {
        LOGGER.warn("Refused a certificate for {} to {}: {}", identity, connection.getRemoteAddress(), why);
        return CertificateResponse.refused(id, why);
    }
}
