package dev.heysulo.archon.registry.tls;

import dev.heysulo.archon.dictionary.sdk.messages.CertificateRequest;
import dev.heysulo.archon.dictionary.sdk.messages.CertificateResponse;
import dev.heysulo.archon.dictionary.tls.ArchonIdentity;
import dev.heysulo.archon.dictionary.tls.Certificates;
import dev.heysulo.archon.dictionary.tls.JoinTokens;
import dev.heysulo.archon.dictionary.tls.TlsMode;
import dev.heysulo.archon.registry.applications.ApplicationDefinitions;
import dev.heysulo.archon.registry.db.Database;
import dev.heysulo.archon.registry.server.FakeConnection;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.security.KeyPair;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;

public class RegistryTlsTest {
    private static final ArchonIdentity CART = new ArchonIdentity("Shop", "Cart");
    private Database database;
    private RegistryTls tls;
    private KeyPair keys;

    @BeforeMethod
    public void setUp() throws Exception {
        database = CertificateAuthorityTest.h2();
        try (Connection connection = database.connect()) {
            JoinTokenStore.prepare(connection);
        }
        tls = RegistryTls.start(TlsMode.REQUIRED, CertificateAuthority.create(), new JoinTokenStore(database),
                Duration.ofHours(24));
        ApplicationDefinitions.setCurrent(ApplicationDefinitions.of(Set.of("Shop:Cart", "Shop:Search")));
        keys = CertificateAuthority.newKeyPair();
    }

    @AfterMethod
    public void allowEveryone() {
        ApplicationDefinitions.setCurrent(ApplicationDefinitions.allowAll());
    }

    private String token(ArchonIdentity identity, boolean reusable, Instant expires) throws Exception {
        String token = JoinTokens.newToken();
        try (Connection connection = database.connect();
             PreparedStatement insert = connection.prepareStatement("INSERT INTO join_tokens (app_group, app_name, "
                     + "token_hash, reusable, expires_at) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, identity.group());
            insert.setString(2, identity.name());
            insert.setString(3, JoinTokens.hash(token));
            insert.setBoolean(4, reusable);
            insert.setTimestamp(5, expires == null ? null : Timestamp.from(expires));
            insert.executeUpdate();
        }
        return token;
    }

    private CertificateResponse ask(FakeConnection connection, ArchonIdentity identity, String token) {
        return tls.answer(connection, new CertificateRequest(1, identity.group(), identity.name(), token,
                Certificates.encode(keys.getPublic())));
    }

    @Test
    public void certifiesAnInstanceWithAJoinToken() throws Exception {
        CertificateResponse response = ask(new FakeConnection(), CART, token(CART, false, null));
        Assert.assertTrue(response.isIssued(), response.getError());
        var certificate = Certificates.decode(response.getCertificate());
        Assert.assertEquals(ArchonIdentity.of(certificate).orElseThrow(), CART);
        Assert.assertEquals(certificate.getPublicKey(), keys.getPublic());
        Assert.assertEquals(Certificates.decode(response.getCaCertificate()), tls.ca().certificate());
    }

    @Test
    public void spendsSingleUseTokensOnce() throws Exception {
        String single = token(CART, false, null);
        Assert.assertTrue(ask(new FakeConnection(), CART, single).isIssued());
        Assert.assertEquals(ask(new FakeConnection(), CART, single).getError(), "The join token has been used");

        String reusable = token(CART, true, null);
        Assert.assertTrue(ask(new FakeConnection(), CART, reusable).isIssued());
        Assert.assertTrue(ask(new FakeConnection(), CART, reusable).isIssued());
    }

    @Test
    public void refusesBadTokens() throws Exception {
        Assert.assertFalse(ask(new FakeConnection(), CART, "ajt_made_up").isIssued());
        Assert.assertFalse(ask(new FakeConnection(), CART, token(new ArchonIdentity("Shop", "Search"), true, null))
                .isIssued(), "another application's token");
        Assert.assertEquals(ask(new FakeConnection(), CART, token(CART, true, Instant.now().minusSeconds(5)))
                .getError(), "The join token has expired");
    }

    @Test
    public void refusesUndefinedApplicationsAndRegistries() throws Exception {
        ArchonIdentity stranger = new ArchonIdentity("Nowhere", "Nobody");
        Assert.assertTrue(ask(new FakeConnection(), stranger, token(stranger, true, null)).getError()
                .contains("not a defined application"));
        Assert.assertEquals(ask(new FakeConnection(), ArchonIdentity.REGISTRY,
                token(ArchonIdentity.REGISTRY, true, null)).getError(), "Registries issue their own certificates");
    }

    @Test
    public void renewsOnlyOverAConnectionSecuredAsTheSameIdentity() throws Exception {
        var cart = tls.ca().issue(CertificateAuthority.newKeyPair().getPublic(), CART, Duration.ofHours(1));
        var search = tls.ca().issue(CertificateAuthority.newKeyPair().getPublic(), new ArchonIdentity("Shop",
                "Search"), Duration.ofHours(1));
        Assert.assertTrue(ask(new FakeConnection().presenting(cart), CART, null).isIssued());
        Assert.assertFalse(ask(new FakeConnection().presenting(search), CART, null).isIssued());
        Assert.assertFalse(ask(new FakeConnection(), CART, null).isIssued());
    }

    @Test
    public void connectsToOtherRegistriesOnlyWhenRequired() throws Exception {
        Assert.assertNotNull(tls.peerContext());
        Assert.assertNotNull(tls.serverContext());
        RegistryTls optional = RegistryTls.start(TlsMode.OPTIONAL, tls.ca(), new JoinTokenStore(database),
                Duration.ofHours(1));
        Assert.assertNull(optional.peerContext(), "registries talk in the clear while TLS is optional");
    }
}
