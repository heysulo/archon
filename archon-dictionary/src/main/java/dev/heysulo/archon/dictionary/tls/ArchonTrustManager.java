package dev.heysulo.archon.dictionary.tls;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Trusts a peer whose certificate the Archon CA issued and whose identity is acceptable. Addresses play no part:
 * Archon trusts {@code archon://Group/Name}, wherever the peer runs.
 */
public final class ArchonTrustManager extends X509ExtendedTrustManager {
    private final CaTrust ca;
    private final Predicate<ArchonIdentity> accepted;
    private final String expected;

    /**
     * @param ca       the CA that must have issued the peer's certificate
     * @param accepted which identities to trust
     * @param expected describes the acceptable identities, for errors
     */
    public ArchonTrustManager(CaTrust ca, Predicate<ArchonIdentity> accepted, String expected) {
        this.ca = Objects.requireNonNull(ca, "ca");
        this.accepted = Objects.requireNonNull(accepted, "accepted");
        this.expected = expected;
    }

    /** Trusts exactly one identity. */
    public static ArchonTrustManager expecting(CaTrust ca, ArchonIdentity identity) {
        return new ArchonTrustManager(ca, identity::equals, identity.toString());
    }

    /** Trusts any identity the CA issued. */
    public static ArchonTrustManager anyIdentity(CaTrust ca) {
        return new ArchonTrustManager(ca, identity -> true, "any Archon identity");
    }

    /**
     * Checks a peer's chain, its own certificate first.
     *
     * @return the peer's identity
     * @throws CertificateException if the CA did not issue it, it is out of date, or its identity is not accepted
     */
    public ArchonIdentity verify(X509Certificate[] chain) throws CertificateException {
        if (chain == null || chain.length == 0) {
            throw new CertificateException("The peer presented no certificate");
        }
        X509Certificate leaf = chain[0];
        X509Certificate issuer = ca.certificate() != null ? ca.certificate()
                : Arrays.stream(chain).skip(1).filter(ca::matches).findFirst()
                        .orElseThrow(() -> new CertificateException("The peer's certificate is not from the Archon "
                                + "CA with fingerprint " + ca.fingerprint()));
        try {
            issuer.checkValidity();
            leaf.checkValidity();
            leaf.verify(issuer.getPublicKey());
        } catch (GeneralSecurityException e) {
            throw new CertificateException("The peer's certificate is not valid: " + e.getMessage(), e);
        }
        if (leaf.getBasicConstraints() >= 0) {
            throw new CertificateException("The peer presented a CA certificate as its own");
        }
        ArchonIdentity identity = ArchonIdentity.of(leaf)
                .orElseThrow(() -> new CertificateException("The peer's certificate names no Archon identity"));
        if (!accepted.test(identity)) {
            throw new CertificateException("The peer is " + identity + ", not " + expected);
        }
        return identity;
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        verify(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        verify(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        verify(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
            throws CertificateException {
        verify(chain);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        verify(chain);
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
            throws CertificateException {
        verify(chain);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return ca.certificate() == null ? new X509Certificate[0] : new X509Certificate[]{ca.certificate()};
    }
}
