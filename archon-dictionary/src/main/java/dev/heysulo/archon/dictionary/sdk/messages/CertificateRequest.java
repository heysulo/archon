package dev.heysulo.archon.dictionary.sdk.messages;

import dev.heysulo.databridge.core.common.Message;

/**
 * Asks a registry, primary or mirror, for a certificate naming this instance's application definition, for the key
 * pair the instance generated. The private key never leaves the instance.
 * <p>
 * The first request carries a join token for the definition. Renewals carry none: they are sent over a connection
 * already secured with the instance's current certificate, which proves the identity.
 */
public class CertificateRequest implements Message {
    private static final long serialVersionUID = 1L;

    private final long requestId;
    private final String group;
    private final String name;
    private final String joinToken;
    private final String publicKey;

    /**
     * @param joinToken a join token for the definition, or null to renew over a connection secured as it
     * @param publicKey the public key to certify, base64 of its X.509 encoding
     */
    public CertificateRequest(long requestId, String group, String name, String joinToken, String publicKey) {
        this.requestId = requestId;
        this.group = group;
        this.name = name;
        this.joinToken = joinToken;
        this.publicKey = publicKey;
    }

    public long getRequestId() {
        return requestId;
    }

    public String getGroup() {
        return group;
    }

    public String getName() {
        return name;
    }

    public String getJoinToken() {
        return joinToken;
    }

    public String getPublicKey() {
        return publicKey;
    }

    @Override
    public String toString() {
        // Never the token
        return "CertificateRequest{" + group + ":" + name + (joinToken == null ? ", renewal" : ", with token") + "}";
    }
}
