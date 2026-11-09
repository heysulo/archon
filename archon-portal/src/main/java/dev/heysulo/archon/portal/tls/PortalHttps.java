package dev.heysulo.archon.portal.tls;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * Serves the portal over HTTPS when {@code PORTAL_TLS_CERT} and {@code PORTAL_TLS_KEY} name PEM files: the
 * certificate chain, the portal's own first, and its unencrypted PKCS#8 private key. Browsers must trust the
 * certificate, so it usually comes from your own CA or a public one rather than the Archon CA.
 *
 * <p>The files are read again when they change, checked at most once a minute, so a renewed certificate is used
 * without a restart, as when cert-manager, certbot or similar replaces them.
 */
public final class PortalHttps {
    public static final String CERT_ENV = "PORTAL_TLS_CERT";
    public static final String KEY_ENV = "PORTAL_TLS_KEY";
    static final Duration CHECK_INTERVAL = Duration.ofMinutes(1);
    private static final Logger LOGGER = LoggerFactory.getLogger(PortalHttps.class);

    private PortalHttps() {
    }

    /**
     * @return the context to serve HTTPS with, or empty when neither file is configured
     * @throws IllegalStateException if only one is configured, or they cannot be read
     */
    public static Optional<SSLContext> fromEnvironment() {
        String cert = System.getenv(CERT_ENV);
        String key = System.getenv(KEY_ENV);
        boolean hasCert = cert != null && !cert.isBlank();
        boolean hasKey = key != null && !key.isBlank();
        if (!hasCert && !hasKey) {
            return Optional.empty();
        }
        if (!hasCert || !hasKey) {
            throw new IllegalStateException("Set both " + CERT_ENV + " and " + KEY_ENV + " to serve HTTPS");
        }
        return Optional.of(context(Path.of(cert.trim()), Path.of(key.trim()), Clock.systemUTC()));
    }

    static SSLContext context(Path cert, Path key, Clock clock) {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(new KeyManager[]{new ReloadingKeyManager(cert, key, clock)}, null, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot set up HTTPS: " + e.getMessage(), e);
        }
    }

    record Identity(X509Certificate[] chain, PrivateKey key, Instant certModified, Instant keyModified) {
    }

    static Identity read(Path cert, Path key) {
        try {
            Instant certModified = Files.getLastModifiedTime(cert).toInstant();
            Instant keyModified = Files.getLastModifiedTime(key).toInstant();
            X509Certificate[] chain = CertificateFactory.getInstance("X.509")
                    .generateCertificates(new ByteArrayInputStream(Files.readAllBytes(cert))).stream()
                    .map(X509Certificate.class::cast).toArray(X509Certificate[]::new);
            if (chain.length == 0) {
                throw new IllegalStateException(cert + " holds no certificate");
            }
            return new Identity(chain, privateKey(Files.readString(key, StandardCharsets.US_ASCII)), certModified,
                    keyModified);
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException("Cannot read the portal's certificate or key: " + e.getMessage(), e);
        }
    }

    static PrivateKey privateKey(String pem) throws InvalidKeySpecException {
        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) {
            throw new InvalidKeySpecException("The key must be an unencrypted PKCS#8 PEM (BEGIN PRIVATE KEY). "
                    + "Convert one with: openssl pkcs8 -topk8 -nocrypt -in key.pem -out pkcs8.pem");
        }
        String base64 = pem.substring(pem.indexOf("-----BEGIN PRIVATE KEY-----") + 27,
                pem.indexOf("-----END PRIVATE KEY-----")).replaceAll("\\s", "");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64));
        for (String algorithm : new String[]{"EC", "RSA", "Ed25519"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(spec);
            } catch (InvalidKeySpecException | NoSuchAlgorithmException ignored) {
                // Try the next algorithm
            }
        }
        throw new InvalidKeySpecException("The key is not an EC, RSA or Ed25519 key");
    }

    /** Hands out the current certificate, reading the files again once they change. */
    static final class ReloadingKeyManager extends X509ExtendedKeyManager {
        private static final String ALIAS = "portal";
        private final Path cert;
        private final Path key;
        private final Clock clock;
        private volatile Identity identity;
        private volatile Instant checkedAt;

        ReloadingKeyManager(Path cert, Path key, Clock clock) {
            this.cert = cert;
            this.key = key;
            this.clock = clock;
            this.identity = read(cert, key);
            this.checkedAt = clock.instant();
            LOGGER.info("Serving HTTPS as {}, valid until {}", identity.chain()[0].getSubjectX500Principal(),
                    identity.chain()[0].getNotAfter().toInstant());
        }

        Identity current() {
            Instant now = clock.instant();
            if (Duration.between(checkedAt, now).compareTo(CHECK_INTERVAL) >= 0) {
                checkedAt = now;
                try {
                    Identity known = identity;
                    if (!Files.getLastModifiedTime(cert).toInstant().equals(known.certModified())
                            || !Files.getLastModifiedTime(key).toInstant().equals(known.keyModified())) {
                        identity = read(cert, key);
                        LOGGER.info("Reloaded the portal's certificate, valid until {}",
                                identity.chain()[0].getNotAfter().toInstant());
                    }
                } catch (IOException | IllegalStateException e) {
                    // Mid-replacement, say; the certificate already loaded serves until the files settle
                    LOGGER.warn("Cannot reload the portal's certificate yet: {}", e.getMessage());
                }
            }
            return identity;
        }

        @Override
        public String chooseEngineServerAlias(String keyType, Principal[] issuers, SSLEngine engine) {
            return ALIAS;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return current().chain().clone();
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return current().key();
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[0];
        }

        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return null;
        }
    }
}
