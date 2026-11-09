package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/** A registry's answer to a {@link CertificateRequest}: the certificate and the CA's, or why it refused. */
public class CertificateResponse implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final String certificate;
    private final String caCertificate;
    private final String error;

    private CertificateResponse(long requestId, String certificate, String caCertificate, String error) {
        this.requestId = requestId;
        this.certificate = certificate;
        this.caCertificate = caCertificate;
        this.error = error;
    }

    /** @param certificate the issued certificate and @param caCertificate the CA's, each base64 DER */
    public static CertificateResponse issued(long requestId, String certificate, String caCertificate) {
        return new CertificateResponse(requestId, certificate, caCertificate, null);
    }

    public static CertificateResponse refused(long requestId, String error) {
        return new CertificateResponse(requestId, null, null, error);
    }

    public long getRequestId() {
        return requestId;
    }

    public boolean isIssued() {
        return certificate != null;
    }

    public String getCertificate() {
        return certificate;
    }

    public String getCaCertificate() {
        return caCertificate;
    }

    public String getError() {
        return error;
    }
}
