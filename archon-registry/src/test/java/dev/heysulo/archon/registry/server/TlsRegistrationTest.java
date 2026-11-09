package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.CertificateRequest;
import dev.heysulo.archon.dictionary.sdk.messages.CertificateResponse;
import dev.heysulo.archon.dictionary.tls.ArchonIdentity;
import dev.heysulo.archon.dictionary.tls.ArchonTrustManager;
import dev.heysulo.archon.dictionary.tls.CaTrust;
import dev.heysulo.archon.dictionary.tls.Certificates;
import dev.heysulo.archon.dictionary.tls.JoinTokens;
import dev.heysulo.archon.dictionary.tls.TlsMaterial;
import dev.heysulo.archon.dictionary.tls.TlsMode;
import dev.heysulo.archon.registry.constants.ApplicationTermination;
import dev.heysulo.archon.registry.db.Database;
import dev.heysulo.archon.registry.tls.CertificateAuthority;
import dev.heysulo.archon.registry.tls.JoinTokenStore;
import dev.heysulo.archon.registry.tls.RegistryTls;
import dev.heysulo.databridge.core.client.Client;
import dev.heysulo.databridge.core.client.callback.ClientCallback;
import dev.heysulo.databridge.core.common.Connection;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import dev.heysulo.databridge.core.server.secure.SecureServer;
import io.netty.handler.ssl.SslContext;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.net.ServerSocket;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/** Joining and registering over real TLS connections to a registry's server context. */
public class TlsRegistrationTest {
    private static final ArchonIdentity CART = new ArchonIdentity("Shop", "Cart");

    @AfterMethod
    public void plain() {
        RegistryTls.setCurrent(RegistryTls.off());
    }

    @Test
    public void refusesRegistrationsTheCertificateDoesNotCover() throws Exception {
        CertificateAuthority ca = CertificateAuthority.create();
        X509Certificate cart = ca.issue(CertificateAuthority.newKeyPair().getPublic(), CART, Duration.ofHours(1));
        RegistryTls.setCurrent(RegistryTls.start(TlsMode.OPTIONAL, ca, null, Duration.ofHours(1)));
        Assert.assertNull(RegistryServer.certificateRefusal(new FakeConnection().presenting(cart), "Shop", "Cart"));
        Assert.assertEquals(RegistryServer.certificateRefusal(new FakeConnection().presenting(cart), "Shop",
                "Search"), ApplicationTermination.REASON_CERTIFICATE_MISMATCH);
        Assert.assertNull(RegistryServer.certificateRefusal(new FakeConnection(), "Shop", "Cart"),
                "a plain instance may still register while TLS is optional");

        RegistryTls.setCurrent(RegistryTls.start(TlsMode.REQUIRED, ca, null, Duration.ofHours(1)));
        Assert.assertEquals(RegistryServer.certificateRefusal(new FakeConnection(), "Shop", "Cart"),
                ApplicationTermination.REASON_CERTIFICATE_REQUIRED);
    }

    @Test
    public void reportsTurnedAwayCallersBriefly() {
        Assert.assertEquals(RegistryServer.tlsRefusal(new io.netty.handler.codec.DecoderException(
                new io.netty.handler.ssl.NotSslRecordException("not an SSL/TLS record: 000000a4aced"))),
                "it did not use TLS, which is required");
        Assert.assertTrue(RegistryServer.tlsRefusal(new javax.net.ssl.SSLHandshakeException("bad certificate"))
                .contains("bad certificate"));
        Assert.assertNull(RegistryServer.tlsRefusal(new IllegalStateException("a real fault")));
    }

    @Test
    public void joinsThenConnectsAsItsIdentity() throws Exception {
        Database database = new Database("jdbc:h2:mem:tls-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORE_UNKNOWN_SETTINGS=TRUE", "sa", "");
        String token = JoinTokens.newToken();
        try (java.sql.Connection connection = database.connect()) {
            JoinTokenStore.prepare(connection);
            try (PreparedStatement insert = connection.prepareStatement("INSERT INTO join_tokens (app_group, "
                    + "app_name, token_hash) VALUES ('Shop', 'Cart', ?)")) {
                insert.setString(1, JoinTokens.hash(token));
                insert.executeUpdate();
            }
        }
        RegistryTls tls = RegistryTls.start(TlsMode.REQUIRED, CertificateAuthority.create(),
                new JoinTokenStore(database), Duration.ofHours(1));
        List<Connection> connections = new CopyOnWriteArrayList<>();
        int port;
        try (ServerSocket socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        SecureServer server = new SecureServer(port, new ServerCallback() {
            @Override
            public void onConnect(Server s, Connection connection) {
                connections.add(connection);
            }

            @Override
            public void onDisconnect(Server s, Connection connection) {
            }

            @Override
            public void onMessage(Server s, Connection connection, Message message) {
                if (message instanceof CertificateRequest request) {
                    tls.handle(connection, request);
                }
            }

            @Override
            public void onError(Server s, Connection connection, Throwable throwable) {
            }
        }, tls::serverContext, false, null, null);
        server.settings().addTrustedPackage("dev.heysulo.archon.**");
        server.start();
        try {
            // Knowing only the CA's fingerprint, as an instance configured with ARCHON_CA_FINGERPRINT does
            CaTrust pinned = CaTrust.pinned(Certificates.fingerprint(tls.ca().certificate()));
            KeyPair keys = CertificateAuthority.newKeyPair();
            CompletableFuture<CertificateResponse> answer = new CompletableFuture<>();
            Client joining = client(port, TlsMaterial.anonymousClientContext(
                    ArchonTrustManager.expecting(pinned, ArchonIdentity.REGISTRY)), answer);
            joining.send(new CertificateRequest(1, "Shop", "Cart", token, Certificates.encode(keys.getPublic())));
            CertificateResponse response = answer.get(10, TimeUnit.SECONDS);
            Assert.assertTrue(response.isIssued(), response.getError());
            joining.stop();

            TlsMaterial material = new TlsMaterial(keys.getPrivate(), Certificates.decode(response.getCertificate()),
                    CaTrust.of(Certificates.decode(response.getCaCertificate())));
            connections.clear();
            Client registered = client(port, material.clientContext(
                    ArchonTrustManager.expecting(material.ca(), ArchonIdentity.REGISTRY)), new CompletableFuture<>());
            long deadline = System.currentTimeMillis() + 5_000;
            while ((connections.isEmpty() || !connections.get(0).isSecure()) && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
            }
            Assert.assertEquals(ArchonIdentity.of(connections.get(0)).orElseThrow(), CART,
                    "the registry sees who connected");
            registered.stop();

            // A client expecting someone else than a registry does not trust this server
            Client wrong = new Client("127.0.0.1", port, new NoCallback(), material.clientContext(
                    ArchonTrustManager.expecting(material.ca(), CART)), null);
            wrong.setHostnameVerificationEnabled(false);
            wrong.connect();
            Thread.sleep(500);
            Assert.assertFalse(wrong.isSecure(), "the handshake fails");
            wrong.stop();
        } finally {
            server.stop();
        }
    }

    private static Client client(int port, SslContext context, CompletableFuture<CertificateResponse> answer)
            throws InterruptedException {
        Client client = new Client("127.0.0.1", port, new NoCallback() {
            @Override
            public void onMessage(Client c, Message message) {
                if (message instanceof CertificateResponse response) {
                    answer.complete(response);
                }
            }
        }, context, null);
        client.setHostnameVerificationEnabled(false);
        client.settings().addTrustedPackage("dev.heysulo.archon.**");
        client.connect();
        return client;
    }

    private static class NoCallback implements ClientCallback {
        @Override
        public void onConnect(Client client) {
        }

        @Override
        public void onDisconnect(Client client) {
        }

        @Override
        public void onMessage(Client client, Message message) {
        }

        @Override
        public void onError(Client client, Throwable throwable) {
        }
    }
}
