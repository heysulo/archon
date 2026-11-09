package dev.heysulo.archon.dictionary.tls;

import java.security.cert.X509Certificate;
import java.util.Objects;

/**
 * How a client recognises the Archon CA: by its certificate, once known, or before then by the certificate's
 * fingerprint, which is short enough to pass in configuration. A pinned fingerprint is matched against the CA
 * certificate the server sends with its own.
 */
public final class CaTrust {
    private final X509Certificate certificate;
    private final String fingerprint;

    private CaTrust(X509Certificate certificate, String fingerprint) {
        this.certificate = certificate;
        this.fingerprint = fingerprint;
    }

    public static CaTrust of(X509Certificate certificate) {
        Objects.requireNonNull(certificate, "certificate");
        return new CaTrust(certificate, Certificates.fingerprint(certificate));
    }

    /** @throws IllegalArgumentException if it is not a SHA-256 fingerprint */
    public static CaTrust pinned(String fingerprint) {
        return new CaTrust(null, Certificates.normalizeFingerprint(fingerprint));
    }

    /** The CA certificate, or null if only its fingerprint is known. */
    public X509Certificate certificate() {
        return certificate;
    }

    public String fingerprint() {
        return fingerprint;
    }

    /** Whether this is the CA, by fingerprint. */
    public boolean matches(X509Certificate candidate) {
        return fingerprint.equals(Certificates.fingerprint(candidate));
    }
}
