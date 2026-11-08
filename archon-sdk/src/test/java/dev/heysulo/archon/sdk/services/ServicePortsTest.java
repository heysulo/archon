package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import org.testng.Assert;
import org.testng.annotations.Test;

public class ServicePortsTest {

    private static final ServiceDefinition ORDERS =
            new ServiceDefinition("orders", "Orders", 5, "Shop", "OrderApp", ServiceMode.PRIMARY_MIRROR);

    @Test
    public void addsTheOffsetToTheBasePort() {
        Assert.assertEquals(ServicePorts.resolve("9000", ORDERS), 9005);
        Assert.assertEquals(ServicePorts.resolve(" 9000 ", ORDERS), 9005);
    }

    @Test
    public void rejectsAMissingOrInvalidBasePort() {
        assertRejected(null, "not set");
        assertRejected("", "not set");
        assertRejected("ninety", "not a number");
        assertRejected("0", "not a valid port");
        assertRejected("65533", "not a valid port");
    }

    private static void assertRejected(String basePort, String expectedMessage) {
        ServiceException e = Assert.expectThrows(ServiceException.class, () -> ServicePorts.resolve(basePort, ORDERS));
        Assert.assertTrue(e.getMessage().contains(expectedMessage), e.getMessage());
    }
}
