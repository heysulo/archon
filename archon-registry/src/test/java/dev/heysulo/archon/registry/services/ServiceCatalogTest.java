package dev.heysulo.archon.registry.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.io.StringReader;

public class ServiceCatalogTest {

    private static final String ORDERS = """
            {"id": "orders", "name": "Order Service", "portOffset": 1, "group": "Shop", "application": "OrderApp",
             "mode": "PRIMARY_MIRROR"}""";
    private static final String SEARCH = """
            {"id": "search", "name": "Search", "portOffset": 2, "group": "Shop", "application": "SearchApp",
             "mode": "LOAD_BALANCE"}""";

    @Test
    public void loadsEveryDefinition() {
        ServiceCatalog catalog = parse(ORDERS + "," + SEARCH);

        Assert.assertEquals(catalog.size(), 2);
        Assert.assertEquals(catalog.find("orders").orElseThrow(),
                new ServiceDefinition("orders", "Order Service", 1, "Shop", "OrderApp", ServiceMode.PRIMARY_MIRROR));
        Assert.assertEquals(catalog.find("search").orElseThrow().mode(), ServiceMode.LOAD_BALANCE);
        Assert.assertTrue(catalog.find("billing").isEmpty());
        Assert.assertTrue(catalog.find(null).isEmpty());
    }

    @Test
    public void anEmptyFileDefinesNoServices() {
        Assert.assertEquals(ServiceCatalog.parse(new StringReader("{}")).size(), 0);
        Assert.assertEquals(ServiceCatalog.parse(new StringReader("")).size(), 0);
    }

    @Test
    public void rejectsADuplicateId() {
        assertRejected(ORDERS + "," + ORDERS, "more than once");
    }

    @Test
    public void rejectsAMissingField() {
        assertRejected("{\"id\": \"orders\", \"name\": \"Orders\", \"group\": \"Shop\", \"application\": \"App\","
                + " \"mode\": \"LOAD_BALANCE\"}", "missing portOffset");
        assertRejected(ORDERS.replace("\"group\": \"Shop\"", "\"group\": \" \""), "missing group");
    }

    @Test
    public void rejectsAnUnknownMode() {
        assertRejected(ORDERS.replace("PRIMARY_MIRROR", "ROUND_ROBIN"), "orders is invalid");
    }

    @Test
    public void rejectsANegativeOffset() {
        assertRejected(ORDERS.replace("\"portOffset\": 1", "\"portOffset\": -1"), "orders is invalid");
    }

    @Test
    public void rejectsMalformedJson() {
        assertRejected("{\"id\": ", "not valid JSON");
    }

    private static ServiceCatalog parse(String services) {
        return ServiceCatalog.parse(new StringReader("{\"services\": [" + services + "]}"));
    }

    private static void assertRejected(String services, String expectedMessage) {
        IllegalStateException e = Assert.expectThrows(IllegalStateException.class, () -> parse(services));
        Assert.assertTrue(e.getMessage().contains(expectedMessage), e.getMessage());
    }
}
