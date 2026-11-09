package dev.heysulo.archon.sdk.tls;

import dev.heysulo.archon.dictionary.sdk.messages.CertificateRequest;
import dev.heysulo.archon.dictionary.sdk.messages.CertificateResponse;
import dev.heysulo.archon.dictionary.tls.ArchonIdentity;
import dev.heysulo.archon.dictionary.tls.ArchonTrustManager;
import dev.heysulo.archon.dictionary.tls.CaTrust;
import dev.heysulo.archon.dictionary.tls.Certificates;
import dev.heysulo.archon.dictionary.tls.TlsMaterial;
import dev.heysulo.archon.dictionary.tls.TlsMode;
import dev.heysulo.archon.sdk.constant.RegistryConstants;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Message;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.util.concurrent.DefaultThreadFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * This instance's TLS: whether it is on, the certificate the Archon CA issued it, and the contexts for connecting to
 * registries and services and for serving.
 *
 * <p>Configured from the environment unless {@link #configure} is called first:
 * <ul>
 *     <li>{@code ARCHON_TLS}: {@code off} (the default), {@code optional} or {@code required}</li>
 *     <li>{@code ARCHON_CA_FINGERPRINT}: the SHA-256 fingerprint of the Archon CA certificate, which the portal
 *     shows. Public, but it is what stops anyone else posing as a registry, so take it from a source you trust.</li>
 *     <li>{@code ARCHON_JOIN_TOKEN}: a join token the portal created for this instance's application definition</li>
 * </ul>
 *
 * <p>On joining, the instance generates a key pair, which never leaves it, and asks a registry to certify it with the
 * join token. The certificate is renewed two thirds of the way through its life over a connection secured with it,
 * so the token is needed only once. Nothing is written to disk.
 */
public final class ArchonTls {
    public static final String FINGERPRINT_ENV = "ARCHON_CA_FINGERPRINT";
    public static final String JOIN_TOKEN_ENV = "ARCHON_JOIN_TOKEN";
    static final Duration ANSWER_TIMEOUT = Duration.ofSeconds(10);
    static final Duration JOIN_RETRY = Duration.ofSeconds(3);
    static final Duration RENEWAL_RETRY = Duration.ofMinutes(1);
    static final Duration COMPONENT_TOKEN_LIFETIME = Duration.ofMinutes(10);
    private static final Logger LOGGER = LoggerFactory.getLogger(ArchonTls.class);
    private static ArchonTls instance;

    private final TlsMode mode;
    private final CaTrust pinned;
    private final String joinToken;
    private final Supplier<List<String>> registries;
    private final AtomicLong requestIds = new AtomicLong();
    private final Map<ArchonIdentity, SslContext> serviceContexts = new ConcurrentHashMap<>();
    private final EventLoopGroup eventLoops;
    private final ScheduledExecutorService renewal;
    private volatile TlsMaterial material;
    private volatile SslContext registryContext;
    private volatile SslContext serverContext;
    private volatile ArchonIdentity identity;

    ArchonTls(TlsMode mode, CaTrust pinned, String joinToken, Supplier<List<String>> registries) {
        this.mode = mode;
        this.pinned = pinned;
        this.joinToken = joinToken;
        this.registries = registries;
        boolean on = mode.enabled();
        this.eventLoops = on ? new NioEventLoopGroup(1, new DefaultThreadFactory("archon-tls", true)) : null;
        this.renewal = on ? Executors.newSingleThreadScheduledExecutor(new DefaultThreadFactory("archon-tls-renewal",
                true)) : null;
    }

    /** The process's TLS, configured from the environment on first use unless {@link #configure} came first. */
    public static synchronized ArchonTls get() {
        if (instance == null) {
            instance = fromEnvironment();
        }
        return instance;
    }

    /**
     * Configures TLS in code instead of the environment, as Archon's own components do. Call before initializing.
     *
     * @param mode      whether TLS is on
     * @param ca        the CA to trust, by certificate or fingerprint; may be null when off
     * @param joinToken a join token for this instance's application definition; may be null when off
     * @throws IllegalStateException if TLS is already in use
     */
    public static synchronized void configure(TlsMode mode, CaTrust ca, String joinToken) {
        if (instance != null && instance.material != null) {
            throw new IllegalStateException("TLS is already in use");
        }
        instance = create(mode, ca, joinToken, RegistryConstants::getRegistryLocations);
    }

    /** Opens a connection to the archon schema. */
    @FunctionalInterface
    public interface DatabaseConnector {
        java.sql.Connection connect() throws java.sql.SQLException;
    }

    /**
     * Configures TLS for one of Archon's own components that can reach the archon schema, such as the portal. With
     * {@code ARCHON_TLS} on, it trusts the CA certificate stored there, waiting for a registry to create it, and
     * mints its own single-use join token, so it needs neither a fingerprint nor a token configured. Holding the
     * database already trusts it with everything those give.
     *
     * @throws InterruptedException if interrupted while waiting for the CA
     */
    public static void configureForComponent(ArchonIdentity component, DatabaseConnector database)
            throws InterruptedException {
        TlsMode mode = TlsMode.fromEnvironment();
        if (!mode.enabled()) {
            configure(mode, null, null);
            return;
        }
        while (true) {
            try (java.sql.Connection connection = database.connect()) {
                java.util.Optional<String> ca = dev.heysulo.archon.dictionary.tls.JoinTokens
                        .readCaCertificate(connection);
                if (ca.isPresent()) {
                    String token = dev.heysulo.archon.dictionary.tls.JoinTokens.mintForComponent(connection,
                            component, COMPONENT_TOKEN_LIFETIME);
                    configure(mode, CaTrust.of(Certificates.decode(ca.get())), token);
                    return;
                }
                LOGGER.info("Waiting for a registry to create the Archon CA");
            } catch (java.sql.SQLException | CertificateException e) {
                LOGGER.warn("Cannot read the Archon CA yet: {}", e.getMessage());
            }
            Thread.sleep(JOIN_RETRY.toMillis());
        }
    }

    static ArchonTls fromEnvironment() {
        TlsMode mode = TlsMode.fromEnvironment();
        String fingerprint = System.getenv(FINGERPRINT_ENV);
        CaTrust ca = mode.enabled() && fingerprint != null && !fingerprint.isBlank() ? CaTrust.pinned(fingerprint)
                : null;
        return create(mode, ca, System.getenv(JOIN_TOKEN_ENV), RegistryConstants::getRegistryLocations);
    }

    static ArchonTls create(TlsMode mode, CaTrust ca, String joinToken, Supplier<List<String>> registries) {
        return new ArchonTls(mode, ca, joinToken == null || joinToken.isBlank() ? null : joinToken.trim(), registries);
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

    /** When the current certificate expires, or null before joining. */
    public Instant certificateExpiry() {
        TlsMaterial current = material;
        return current == null ? null : current.notAfter();
    }

    /**
     * Gets this instance's first certificate, asking each registry in turn until one issues it. Blocks until then:
     * registries may still be starting. Does nothing with TLS off, or if already joined.
     *
     * @throws IllegalStateException if there is no join token
     * @throws InterruptedException  if interrupted while waiting
     */
    public void join(String group, String name) throws InterruptedException {
        if (!enabled() || material != null) {
            return;
        }
        if (pinned == null) {
            throw new IllegalStateException(TlsMode.ENV + " is " + mode + ", so set " + FINGERPRINT_ENV
                    + " to the Archon CA's fingerprint, which the portal shows");
        }
        if (joinToken == null) {
            throw new IllegalStateException("TLS is on, so set " + JOIN_TOKEN_ENV + " to a join token for " + group
                    + ":" + name + ", which the portal creates");
        }
        identity = new ArchonIdentity(group, name);
        SslContext anonymous = TlsMaterial.anonymousClientContext(
                ArchonTrustManager.expecting(pinned, ArchonIdentity.REGISTRY));
        while (material == null) {
            for (String registry : registries.get()) {
                try {
                    install(request(registry, anonymous, joinToken));
                    LOGGER.info("Joined as {}. The certificate is valid until {}", identity, material.notAfter());
                    return;
                } catch (CertificateRefusedException e) {
                    LOGGER.error("{} refused a certificate for {}: {}", registry, identity, e.getMessage());
                } catch (Exception e) {
                    LOGGER.warn("Cannot join through {}: {}", registry, describe(e));
                }
            }
            Thread.sleep(JOIN_RETRY.toMillis());
        }
    }

    /**
     * For connecting to registries: proves this instance's identity, and trusts only {@code Archon:Registry}. Null
     * with TLS off, and until joined.
     */
    public SslContext registryContext() {
        return enabled() ? registryContext : null;
    }

    /**
     * For connecting to an instance of a service, which must prove it is the service's owner.
     *
     * @return the context, or null with TLS off
     */
    public SslContext serviceClientContext(ArchonIdentity owner) {
        if (!enabled()) {
            return null;
        }
        TlsMaterial current = requireJoined();
        return serviceContexts.computeIfAbsent(owner, key -> current.clientContext(
                ArchonTrustManager.expecting(current.ca(), key)));
    }

    /**
     * For serving a service. Any instance the Archon CA certified may connect; with TLS required it must present
     * its certificate, and plain connections are refused.
     */
    public SslContext serverContext() {
        requireJoined();
        return serverContext;
    }

    private TlsMaterial requireJoined() {
        TlsMaterial current = material;
        if (current == null) {
            throw new IllegalStateException("This instance has not joined yet");
        }
        return current;
    }

    private void install(TlsMaterial next) {
        registryContext = next.clientContext(ArchonTrustManager.expecting(next.ca(), ArchonIdentity.REGISTRY));
        serverContext = next.serverContext(required() ? ClientAuth.REQUIRE : ClientAuth.OPTIONAL,
                ArchonTrustManager.anyIdentity(next.ca()));
        serviceContexts.clear();
        material = next;
        scheduleRenewal(Duration.between(Instant.now(), next.renewAt()));
    }

    private void scheduleRenewal(Duration wait) {
        renewal.schedule(this::renew, Math.max(0, wait.toMillis()), TimeUnit.MILLISECONDS);
    }

    private void renew() {
        TlsMaterial current = material;
        for (String registry : registries.get()) {
            try {
                install(request(registry, registryContext, null));
                LOGGER.info("Renewed the certificate for {}. Valid until {}", identity, material.notAfter());
                return;
            } catch (Exception e) {
                LOGGER.warn("Cannot renew through {}: {}", registry, describe(e));
            }
        }
        LOGGER.error("Cannot renew the certificate for {}, valid until {}. Retrying in {} s", identity,
                current.notAfter(), RENEWAL_RETRY.toSeconds());
        scheduleRenewal(RENEWAL_RETRY);
    }

    private TlsMaterial request(String registry, SslContext context, String token) throws Exception {
        KeyPair keys = newKeyPair();
        CompletableFuture<CertificateResponse> answer = new CompletableFuture<>();
        Client client = new Client(registry, RegistryConstants.PORT, new ClientCallback() {
            @Override
            public void onConnect(Client connected) {
            }

            @Override
            public void onDisconnect(Client disconnected) {
                answer.completeExceptionally(new IllegalStateException("The registry closed the connection"));
            }

            @Override
            public void onMessage(Client receiver, Message message) {
                if (message instanceof CertificateResponse response) {
                    answer.complete(response);
                }
            }

            @Override
            public void onError(Client failed, Throwable cause) {
                answer.completeExceptionally(cause);
            }
        }, context, eventLoops);
        // Registries are trusted by the identity in their certificate, not by address
        client.setHostnameVerificationEnabled(false);
        client.settings().addTrustedPackage("dev.heysulo.archon.**");
        client.settings().setConnectTimeout(RegistryConstants.CONNECT_TIMEOUT);
        try {
            client.connect();
            client.send(new CertificateRequest(requestIds.incrementAndGet(), identity.group(), identity.name(), token,
                    Certificates.encode(keys.getPublic())));
            CertificateResponse response = answer.get(ANSWER_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (!response.isIssued()) {
                throw new CertificateRefusedException(response.getError());
            }
            X509Certificate certificate = Certificates.decode(response.getCertificate());
            X509Certificate ca = Certificates.decode(response.getCaCertificate());
            // The registry was already checked against the pinned CA, but the answer is checked too
            CaTrust trusted = material == null ? pinned : material.ca();
            if (!trusted.matches(ca)) {
                throw new CertificateException("The registry answered with another CA");
            }
            if (!certificate.getPublicKey().equals(keys.getPublic())) {
                throw new CertificateException("The certificate is for another key");
            }
            ArchonTrustManager.expecting(CaTrust.of(ca), identity).verify(new X509Certificate[]{certificate});
            return new TlsMaterial(keys.getPrivate(), certificate, CaTrust.of(ca));
        } finally {
            client.stop();
        }
    }

    static KeyPair newKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static String describe(Exception e) {
        Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
        if (cause instanceof TimeoutException) {
            return "no answer in " + ANSWER_TIMEOUT.toSeconds() + " s";
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /** A registry refused the request: a bad token or an undefined application. */
    static final class CertificateRefusedException extends Exception {
        private static final long serialVersionUID = 1L;

        CertificateRefusedException(String message) {
            super(message);
        }
    }
}
