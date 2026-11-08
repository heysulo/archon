package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.dictionary.sdk.messages.VerifyInstanceRequest;
import dev.heysulo.archon.dictionary.sdk.messages.VerifyInstanceResponse;
import org.testng.Assert;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;
import static dev.heysulo.archon.registry.constants.Constants.RANK_UNKNOWN;

public class VerifyInstanceTest {

    private RegistryServer server;
    private String token;
    private int rank;

    @BeforeClass
    public void registerAnInstance() {
        server = RegistryServer.getInstance();
        RegistryServer.setRank(RANK_PRIMARY);
        FakeConnection app = new FakeConnection();
        ApplicationRegistrationMessage registration = new ApplicationRegistrationMessage("VerifiedApp", "Verify", "TBD");
        registration.setRank(RANK_UNKNOWN);
        server.onMessage(null, app, registration);
        ApplicationRegistrationResponseMessage response = app.last(ApplicationRegistrationResponseMessage.class);
        token = response.getAuthenticationToken();
        rank = response.getRank();
    }

    // Other components, such as archon-config, ask this before trusting who a caller says it is
    @Test
    public void confirmsAGenuineInstance() {
        Assert.assertTrue(ask("Verify", "VerifiedApp", rank, token));
    }

    @Test
    public void refusesAWrongTokenOrIdentity() {
        Assert.assertFalse(ask("Verify", "VerifiedApp", rank, "guessed-token"));
        Assert.assertFalse(ask("Verify", "OtherApp", rank, token), "a token belongs to one application");
        Assert.assertFalse(ask("Verify", "VerifiedApp", rank + 1, token), "and to one rank");
        Assert.assertFalse(ask("Verify", "VerifiedApp", rank, null));
    }

    private boolean ask(String group, String name, int askedRank, String askedToken) {
        FakeConnection caller = new FakeConnection();
        server.onMessage(null, caller, new VerifyInstanceRequest(7, group, name, askedRank, askedToken));
        VerifyInstanceResponse response = caller.last(VerifyInstanceResponse.class);
        Assert.assertEquals(response.getRequestId(), 7);
        return response.isValid();
    }
}
