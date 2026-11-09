package dev.heysulo.archon.dictionary.tls;

import dev.heysulo.databridge.core.common.Connection;

import java.security.cert.Certificate;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The application definition an Archon certificate is issued to, written into the certificate as the URI
 * {@code archon://Group/Name}. Connections are trusted by this identity rather than by address, so an instance is
 * recognised wherever it runs.
 *
 * @param group the application definition's group
 * @param name  the application definition's name
 */
public record ArchonIdentity(String group, String name) {
    public static final String SCHEME = "archon";
    public static final ArchonIdentity REGISTRY = new ArchonIdentity("Archon", "Registry");
    public static final ArchonIdentity CONFIG = new ArchonIdentity("Archon", "Config");
    public static final ArchonIdentity PORTAL = new ArchonIdentity("Archon", "Portal");

    // Group and name are identifiers: letters, digits, dots, dashes and underscores
    private static final Pattern URI = Pattern.compile("archon://([A-Za-z0-9._-]+)/([A-Za-z0-9._-]+)");
    private static final int SAN_URI = 6;

    public ArchonIdentity {
        if (group == null || name == null || group.isBlank() || name.isBlank()) {
            throw new IllegalArgumentException("An identity needs a group and a name");
        }
    }

    public String uri() {
        return SCHEME + "://" + group + "/" + name;
    }

    /** Whether this identity is the given application definition. */
    public boolean is(String otherGroup, String otherName) {
        return group.equals(otherGroup) && name.equals(otherName);
    }

    @Override
    public String toString() {
        return group + ":" + name;
    }

    /** Reads the identity from a certificate's subject alternative names, if it holds exactly one. */
    public static Optional<ArchonIdentity> of(X509Certificate certificate) {
        Collection<List<?>> names;
        try {
            names = certificate.getSubjectAlternativeNames();
        } catch (CertificateParsingException e) {
            return Optional.empty();
        }
        if (names == null) {
            return Optional.empty();
        }
        List<ArchonIdentity> found = names.stream()
                .filter(entry -> entry.size() == 2 && Integer.valueOf(SAN_URI).equals(entry.get(0)))
                .map(entry -> URI.matcher(String.valueOf(entry.get(1))))
                .filter(Matcher::matches)
                .map(matcher -> new ArchonIdentity(matcher.group(1), matcher.group(2)))
                .toList();
        // A certificate naming two identities could pass as either; none is issued that way, so none is trusted
        return found.size() == 1 ? Optional.of(found.get(0)) : Optional.empty();
    }

    /**
     * The identity the peer of a connection proved in the TLS handshake, if it is secured and the peer presented a
     * certificate. The certificate was checked against the Archon CA during the handshake.
     */
    public static Optional<ArchonIdentity> of(Connection connection) {
        List<Certificate> certificates = connection.getPeerCertificates();
        return certificates.isEmpty() || !(certificates.get(0) instanceof X509Certificate leaf)
                ? Optional.empty()
                : of(leaf);
    }
}
