package dev.heysulo.archon.dictionary.tls;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

import javax.net.ssl.SSLException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * An identity's private key and the certificate the Archon CA issued for it, held in memory. Builds the TLS
 * contexts for connecting and serving as that identity.
 */
public final class TlsMaterial {
    static final String[] PROTOCOLS = {"TLSv1.3", "TLSv1.2"};

    private final PrivateKey key;
    private final X509Certificate certificate;
    private final CaTrust ca;
    private final ArchonIdentity identity;

    /**
     * @throws IllegalArgumentException if the certificate names no Archon identity, or no CA certificate is known
     */
    public TlsMaterial(PrivateKey key, X509Certificate certificate, CaTrust ca) {
        this.key = Objects.requireNonNull(key, "key");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
        this.ca = Objects.requireNonNull(ca, "ca");
        if (ca.certificate() == null) {
            throw new IllegalArgumentException("The CA certificate itself is needed, not only its fingerprint");
        }
        this.identity = ArchonIdentity.of(certificate)
                .orElseThrow(() -> new IllegalArgumentException("The certificate names no Archon identity"));
    }

    public ArchonIdentity identity() {
        return identity;
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public CaTrust ca() {
        return ca;
    }

    public Instant notAfter() {
        return certificate.getNotAfter().toInstant();
    }

    /** When to renew: two thirds of the way through the certificate's life, leaving a third to retry in. */
    public Instant renewAt() {
        Instant from = certificate.getNotBefore().toInstant();
        Duration life = Duration.between(from, notAfter());
        return from.plus(life.multipliedBy(2).dividedBy(3));
    }

    /** Connects as this identity to a peer whose identity {@code server} accepts. */
    public SslContext clientContext(ArchonTrustManager server) {
        try {
            return SslContextBuilder.forClient()
                    .keyManager(key, certificate, ca.certificate())
                    .trustManager(server)
                    .protocols(PROTOCOLS)
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("Cannot build a TLS client context: " + e.getMessage(), e);
        }
    }

    /**
     * Serves as this identity. The CA certificate is sent along with the server's own, so clients that only know
     * its fingerprint can check it.
     *
     * @param clientAuth whether clients must, may or need not present a certificate
     * @param clients    the client identities to trust when they present one
     */
    public SslContext serverContext(ClientAuth clientAuth, ArchonTrustManager clients) {
        try {
            return SslContextBuilder.forServer(key, certificate, ca.certificate())
                    .trustManager(clients)
                    .clientAuth(clientAuth)
                    .protocols(PROTOCOLS)
                    .build();
        } catch (SSLException e) {
            throw new IllegalStateException("Cannot build a TLS server context: " + e.getMessage(), e);
        }
    }

    /** Connects without a certificate of its own, as before joining, to a peer {@code server} accepts. */
    public static SslContext anonymousClientContext(ArchonTrustManager server) {
        try {
            return SslContextBuilder.forClient().trustManager(server).protocols(PROTOCOLS).build();
        } catch (SSLException e) {
            throw new IllegalStateException("Cannot build a TLS client context: " + e.getMessage(), e);
        }
    }
}
