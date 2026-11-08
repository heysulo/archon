package dev.heysulo.archon.sdk;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationMessage;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ArchonInterfaceTest {

    // The registry keys ranks and service ownership by group and name, so swapping them breaks both
    @Test
    public void registersWithTheGroupAndNameItWasGiven() {
        ApplicationRegistrationMessage message = ArchonInterface.registrationMessage("Shop", "OrderApp", 3);
        Assert.assertEquals(message.getGroupName(), "Shop");
        Assert.assertEquals(message.getApplicationName(), "OrderApp");
        Assert.assertEquals(message.getRank(), 3);
    }

    @Test
    public void reportsWhereTheInstanceRuns() {
        ApplicationRegistrationMessage message = ArchonInterface.registrationMessage("Shop", "OrderApp", 3);
        Assert.assertNotNull(message.getDetails());
        Assert.assertNotNull(message.getDetails().host());
        Assert.assertNotEquals(message.getIpAddress(), "", "an address or the placeholder");
    }
}
