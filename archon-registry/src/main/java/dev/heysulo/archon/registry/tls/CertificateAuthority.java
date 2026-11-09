package dev.heysulo.archon.registry.tls;

import dev.heysulo.archon.dictionary.tls.ArchonIdentity;
import dev.heysulo.archon.dictionary.tls.CaTrust;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;

/**
 * The Archon CA: its key signs every certificate Archon issues. Certificates name an application definition as
 * {@code archon://Group/Name} and nothing else, since peers are trusted by identity rather than address.
 */
public final class CertificateAuthority {
    static final Duration CA_LIFETIME = Duration.ofDays(3650);
    // Tolerates clocks a little behind the registry's
    static final Duration BACKDATE = Duration.ofMinutes(5);
    private static final String SIGNATURE = "SHA256withECDSA";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PrivateKey key;
    private final X509Certificate certificate;

    CertificateAuthority(PrivateKey key, X509Certificate certificate) {
        this.key = Objects.requireNonNull(key, "key");
        this.certificate = Objects.requireNonNull(certificate, "certificate");
    }

    /** A new CA with a self-signed certificate. */
    public static CertificateAuthority create() throws GeneralSecurityException {
        KeyPair keys = newKeyPair();
        X500Name name = new X500Name("CN=Archon CA");
        Instant now = Instant.now();
        try {
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(name, serial(),
                    Date.from(now.minus(BACKDATE)), Date.from(now.plus(CA_LIFETIME)), name, keys.getPublic());
            JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(0));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                    extensions.createSubjectKeyIdentifier(keys.getPublic()));
            return new CertificateAuthority(keys.getPrivate(), sign(builder, keys.getPrivate()));
        } catch (CertIOException e) {
            throw new GeneralSecurityException("Cannot build the CA certificate", e);
        }
    }

    /** A P-256 key pair, as every Archon identity uses. */
    public static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"), RANDOM);
        return generator.generateKeyPair();
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public CaTrust trust() {
        return CaTrust.of(certificate);
    }

    PrivateKey key() {
        return key;
    }

    /**
     * Certifies a public key as an identity, for both serving and connecting.
     *
     * @param lifetime how long the certificate lasts, capped at the CA's own expiry
     */
    public X509Certificate issue(PublicKey publicKey, ArchonIdentity identity, Duration lifetime)
            throws GeneralSecurityException {
        Instant now = Instant.now();
        Instant notAfter = now.plus(lifetime);
        if (notAfter.isAfter(certificate.getNotAfter().toInstant())) {
            notAfter = certificate.getNotAfter().toInstant();
        }
        try {
            X509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(certificate, serial(),
                    Date.from(now.minus(BACKDATE)), Date.from(notAfter),
                    new X500Name("CN=" + identity.group() + "." + identity.name()), publicKey);
            JcaX509ExtensionUtils extensions = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(
                    new KeyPurposeId[]{KeyPurposeId.id_kp_serverAuth, KeyPurposeId.id_kp_clientAuth}));
            builder.addExtension(Extension.subjectAlternativeName, true,
                    new GeneralNames(new GeneralName(GeneralName.uniformResourceIdentifier, identity.uri())));
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                    extensions.createAuthorityKeyIdentifier(certificate));
            return sign(builder, key);
        } catch (CertIOException e) {
            throw new GeneralSecurityException("Cannot build a certificate for " + identity, e);
        }
    }

    private static X509Certificate sign(X509v3CertificateBuilder builder, PrivateKey signer)
            throws GeneralSecurityException {
        try {
            return new JcaX509CertificateConverter().getCertificate(
                    builder.build(new JcaContentSignerBuilder(SIGNATURE).build(signer)));
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException("Cannot sign a certificate", e);
        }
    }

    // Positive and unpredictable, as serials should be
    private static BigInteger serial() {
        return new BigInteger(127, RANDOM);
    }
}
