package dev.heysulo.archon.portal.auth;

import org.testng.Assert;
import org.testng.annotations.Test;

public class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher(1_000);

    @Test
    public void verifiesOnlyTheRightPassword() {
        String stored = hasher.hash("correct horse battery");
        Assert.assertTrue(hasher.verify("correct horse battery", stored));
        Assert.assertFalse(hasher.verify("correct horse batterY", stored));
    }

    @Test
    public void saltsEveryHash() {
        Assert.assertNotEquals(hasher.hash("same password"), hasher.hash("same password"));
    }

    // Hashes record their own iteration count, so raising the default later keeps old accounts working
    @Test
    public void verifiesHashesMadeWithOtherIterationCounts() {
        String older = new PasswordHasher(500).hash("correct horse battery");
        Assert.assertTrue(hasher.verify("correct horse battery", older));
    }

    @Test
    public void neverMatchesMalformedHashes() {
        Assert.assertFalse(hasher.verify("anything", null));
        Assert.assertFalse(hasher.verify("anything", "plain-text"));
        Assert.assertFalse(hasher.verify("anything", "md5$1$abc$def"));
        Assert.assertFalse(hasher.verify("anything", "pbkdf2-sha256$x$abc$def"));
    }
}
