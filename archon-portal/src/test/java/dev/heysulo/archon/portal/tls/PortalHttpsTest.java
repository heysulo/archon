package dev.heysulo.archon.portal.tls;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.testng.Assert;
import org.testng.annotations.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;

public class PortalHttpsTest {

    private static KeyPair keys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        return generator.generateKeyPair();
    }

    private static X509Certificate selfSigned(KeyPair keys, String name) throws Exception {
        X500Name subject = new X500Name("CN=" + name);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(subject, BigInteger.ONE,
                Date.from(Instant.now().minusSeconds(60)), Date.from(Instant.now().plusSeconds(3600)), subject,
                keys.getPublic());
        builder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, "localhost")));
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(keys.getPrivate())));
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
                + "-----\n";
    }

    private static void write(Path cert, Path key, KeyPair keys, X509Certificate certificate) throws Exception {
        Files.writeString(cert, pem("CERTIFICATE", certificate.getEncoded()), StandardCharsets.US_ASCII);
        Files.writeString(key, pem("PRIVATE KEY", keys.getPrivate().getEncoded()), StandardCharsets.US_ASCII);
    }

    private static HttpClient trusting(X509Certificate certificate) throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("portal", certificate);
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, factory.getTrustManagers(), null);
        return HttpClient.newBuilder().sslContext(context).build();
    }

    @Test
    public void servesHttpsAndPicksUpARenewedCertificate() throws Exception {
        Path dir = Files.createTempDirectory("portal-https");
        Path cert = dir.resolve("tls.crt");
        Path key = dir.resolve("tls.key");
        KeyPair firstKeys = keys();
        X509Certificate first = selfSigned(firstKeys, "first");
        write(cert, key, firstKeys, first);
        MutableClock clock = new MutableClock();
        HttpsServer server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(PortalHttps.context(cert, key, clock)));
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 2);
            exchange.getResponseBody().write("ok".getBytes(StandardCharsets.US_ASCII));
            exchange.close();
        });
        server.start();
        try {
            URI uri = URI.create("https://localhost:" + server.getAddress().getPort() + "/");
            Assert.assertEquals(trusting(first).send(HttpRequest.newBuilder(uri).build(),
                    HttpResponse.BodyHandlers.ofString()).body(), "ok");

            KeyPair secondKeys = keys();
            X509Certificate second = selfSigned(secondKeys, "second");
            write(cert, key, secondKeys, second);
            Files.setLastModifiedTime(cert, FileTime.from(Instant.now().plusSeconds(5)));
            Files.setLastModifiedTime(key, FileTime.from(Instant.now().plusSeconds(5)));
            clock.now = clock.now.plus(PortalHttps.CHECK_INTERVAL);
            Assert.assertEquals(trusting(second).send(HttpRequest.newBuilder(uri).build(),
                    HttpResponse.BodyHandlers.ofString()).body(), "ok", "the renewed certificate is served");
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void explainsKeysInTheWrongFormat() {
        Assert.assertThrows(java.security.spec.InvalidKeySpecException.class,
                () -> PortalHttps.privateKey("-----BEGIN EC PRIVATE KEY-----\nAAAA\n-----END EC PRIVATE KEY-----"));
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
