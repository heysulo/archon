package dev.heysulo.archon.registry.tls;

import dev.heysulo.archon.dictionary.tls.ArchonIdentity;
import dev.heysulo.archon.dictionary.tls.ArchonTrustManager;
import dev.heysulo.archon.dictionary.tls.CaTrust;
import dev.heysulo.archon.dictionary.tls.Certificates;
import dev.heysulo.archon.registry.db.Database;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.UUID;

public class CertificateAuthorityTest {
    private static final ArchonIdentity CART = new ArchonIdentity("Shop", "Cart");

    static Database h2() {
        return new Database("jdbc:h2:mem:tls-" + UUID.randomUUID()
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;IGNORE_UNKNOWN_SETTINGS=TRUE", "sa", "");
    }

    @Test
    public void issuesCertificatesNamingOneIdentity() throws Exception {
        CertificateAuthority ca = CertificateAuthority.create();
        KeyPair keys = CertificateAuthority.newKeyPair();
        X509Certificate certificate = ca.issue(keys.getPublic(), CART, Duration.ofHours(24));

        Assert.assertEquals(ArchonIdentity.of(certificate).orElseThrow(), CART);
        Assert.assertEquals(certificate.getPublicKey(), keys.getPublic());
        Assert.assertEquals(certificate.getBasicConstraints(), -1, "not a CA");
        Assert.assertTrue(ca.certificate().getBasicConstraints() >= 0, "the CA is one");
        certificate.verify(ca.certificate().getPublicKey());
        Assert.assertTrue(certificate.getNotAfter().toInstant().isBefore(java.time.Instant.now()
                .plus(Duration.ofHours(25))));
    }

    @Test
    public void trustsByIdentityAndPinnedFingerprint() throws Exception {
        CertificateAuthority ca = CertificateAuthority.create();
        X509Certificate cart = ca.issue(CertificateAuthority.newKeyPair().getPublic(), CART, Duration.ofHours(1));
        X509Certificate[] chain = {cart, ca.certificate()};

        // Pinned by fingerprint: the CA is found in the chain the peer sent
        CaTrust pinned = CaTrust.pinned(Certificates.fingerprint(ca.certificate()).toUpperCase());
        Assert.assertEquals(ArchonTrustManager.expecting(pinned, CART).verify(chain), CART);
        Assert.assertThrows(CertificateException.class, () -> ArchonTrustManager.expecting(pinned, CART)
                .verify(new X509Certificate[]{cart}));
        Assert.assertThrows(CertificateException.class, () -> ArchonTrustManager.expecting(ca.trust(),
                ArchonIdentity.REGISTRY).verify(chain));

        // Another CA's certificate, even with a chain naming ours, is refused
        CertificateAuthority other = CertificateAuthority.create();
        X509Certificate forged = other.issue(CertificateAuthority.newKeyPair().getPublic(), CART, Duration.ofHours(1));
        Assert.assertThrows(CertificateException.class, () -> ArchonTrustManager.anyIdentity(ca.trust())
                .verify(new X509Certificate[]{forged, ca.certificate()}));
        Assert.assertThrows(CertificateException.class, () -> ArchonTrustManager.anyIdentity(pinned)
                .verify(new X509Certificate[]{forged, ca.certificate()}));
        // The CA's own certificate is no identity
        Assert.assertThrows(CertificateException.class, () -> ArchonTrustManager.anyIdentity(ca.trust())
                .verify(new X509Certificate[]{ca.certificate()}));
    }

    @Test
    public void sealsTheKeyWithTheMasterKey() throws Exception {
        MasterKey master = new MasterKey("correct horse battery staple");
        String sealed = master.seal("secret".getBytes());
        Assert.assertEquals(new String(master.open(sealed)), "secret");
        Assert.assertNotEquals(master.seal("secret".getBytes()), sealed, "fresh salt and nonce each time");
        Assert.assertThrows(GeneralSecurityException.class,
                () -> new MasterKey("another sixteen chars").open(sealed));
        Assert.assertThrows(IllegalArgumentException.class, () -> new MasterKey("short"));
    }

    @Test
    public void everyRegistrySharesTheStoredCa() throws Exception {
        Database database = h2();
        MasterKey master = new MasterKey("correct horse battery staple");
        CertificateAuthority first = CertificateAuthorityStore.loadOrCreate(database, master);
        CertificateAuthority second = CertificateAuthorityStore.loadOrCreate(database, master);
        Assert.assertEquals(second.certificate(), first.certificate());
        Assert.assertEquals(second.key().getEncoded(), first.key().getEncoded());
        Assert.assertThrows(GeneralSecurityException.class,
                () -> CertificateAuthorityStore.loadOrCreate(database, new MasterKey("the wrong master key")));
    }
}
