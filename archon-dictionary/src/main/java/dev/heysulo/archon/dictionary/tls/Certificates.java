package dev.heysulo.archon.dictionary.tls;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;

/** Encoding certificates and keys for the wire and for people. */
public final class Certificates {
    private static final Base64.Encoder MIME = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII));

    private Certificates() {
    }

    /** The SHA-256 fingerprint of a certificate's encoding, as lowercase hex without separators. */
    public static String fingerprint(X509Certificate certificate) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded()));
        } catch (NoSuchAlgorithmException | CertificateEncodingException e) {
            throw new IllegalStateException("Cannot fingerprint a certificate", e);
        }
    }

    /**
     * Normalises a fingerprint as people write it, with or without colons or spaces, in either case.
     *
     * @throws IllegalArgumentException if it is not a SHA-256 fingerprint
     */
    public static String normalizeFingerprint(String fingerprint) {
        String hex = fingerprint == null ? "" : fingerprint.replaceAll("[:\\s]", "").toLowerCase(Locale.ROOT);
        if (!hex.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Not a SHA-256 fingerprint: expected 64 hex digits");
        }
        return hex;
    }

    public static String encode(X509Certificate certificate) {
        try {
            return Base64.getEncoder().encodeToString(certificate.getEncoded());
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Cannot encode a certificate", e);
        }
    }

    /** @throws CertificateException if the text is not a base64 DER X.509 certificate */
    public static X509Certificate decode(String base64) throws CertificateException {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(Base64.getDecoder().decode(base64)));
        } catch (IllegalArgumentException e) {
            throw new CertificateException("Not base64", e);
        }
    }

    public static String encode(PublicKey key) {
        return Base64.getEncoder().encodeToString(key.getEncoded());
    }

    /** Decodes an elliptic curve or RSA public key from its base64 X.509 encoding. */
    public static PublicKey decodePublicKey(String base64) throws InvalidKeySpecException {
        byte[] encoded;
        try {
            encoded = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new InvalidKeySpecException("Not base64", e);
        }
        for (String algorithm : new String[]{"EC", "RSA"}) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(new X509EncodedKeySpec(encoded));
            } catch (InvalidKeySpecException | NoSuchAlgorithmException ignored) {
                // Try the next algorithm
            }
        }
        throw new InvalidKeySpecException("Not an EC or RSA public key");
    }

    /** The certificate as PEM text, for people to save or paste. */
    public static String pem(X509Certificate certificate) {
        try {
            return "-----BEGIN CERTIFICATE-----\n" + MIME.encodeToString(certificate.getEncoded())
                    + "\n-----END CERTIFICATE-----\n";
        } catch (CertificateEncodingException e) {
            throw new IllegalStateException("Cannot encode a certificate", e);
        }
    }
}
