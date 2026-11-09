package dev.heysulo.archon.sdk.tls;

import dev.heysulo.archon.dictionary.tls.CaTrust;
import dev.heysulo.archon.dictionary.tls.TlsMode;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class ArchonTlsTest {
    private static final String FINGERPRINT = "AB:".repeat(31) + "AB";

    @Test
    public void doesNothingWithTlsOff() throws Exception {
        ArchonTls tls = ArchonTls.create(TlsMode.OFF, null, null, List::of);
        tls.join("Shop", "Cart");
        Assert.assertFalse(tls.enabled());
        Assert.assertNull(tls.registryContext(), "plain connections");
        Assert.assertNull(tls.certificateExpiry());
    }

    @Test
    public void needsAFingerprintAndATokenToJoin() {
        ArchonTls withoutCa = ArchonTls.create(TlsMode.OPTIONAL, null, "ajt_token", List::of);
        Assert.assertTrue(Assert.expectThrows(IllegalStateException.class, () -> withoutCa.join("Shop", "Cart"))
                .getMessage().contains(ArchonTls.FINGERPRINT_ENV));
        ArchonTls withoutToken = ArchonTls.create(TlsMode.REQUIRED, CaTrust.pinned(FINGERPRINT), " ", List::of);
        Assert.assertTrue(Assert.expectThrows(IllegalStateException.class, () -> withoutToken.join("Shop", "Cart"))
                .getMessage().contains(ArchonTls.JOIN_TOKEN_ENV));
        Assert.assertNull(withoutToken.registryContext(), "nothing to connect with until joined");
        Assert.assertThrows(IllegalStateException.class, withoutToken::serverContext);
    }

    @Test
    public void acceptsFingerprintsAsPeopleWriteThem() {
        Assert.assertEquals(CaTrust.pinned(FINGERPRINT).fingerprint(), "ab".repeat(32));
        Assert.assertThrows(IllegalArgumentException.class, () -> CaTrust.pinned("abc"));
    }
}
