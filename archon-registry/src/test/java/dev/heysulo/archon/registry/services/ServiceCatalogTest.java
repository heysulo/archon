package dev.heysulo.archon.registry.services;

import dev.heysulo.archon.dictionary.sdk.enums.ServiceMode;
import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;
import dev.heysulo.archon.registry.services.ServiceCatalog.Row;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class ServiceCatalogTest {

    private static final Row ORDERS = new Row("orders", "Order Service", 1, "Shop", "OrderApp", "PRIMARY_MIRROR");
    private static final Row SEARCH = new Row("search", "Search", 2, "Shop", "SearchApp", "LOAD_BALANCE");

    @Test
    public void loadsEveryRow() {
        ServiceCatalog catalog = ServiceCatalog.fromRows(List.of(ORDERS, SEARCH));

        Assert.assertEquals(catalog.size(), 2);
        Assert.assertEquals(catalog.find("orders").orElseThrow(),
                new ServiceDefinition("orders", "Order Service", 1, "Shop", "OrderApp", ServiceMode.PRIMARY_MIRROR));
        Assert.assertEquals(catalog.find("search").orElseThrow().mode(), ServiceMode.LOAD_BALANCE);
        Assert.assertTrue(catalog.find("billing").isEmpty());
        Assert.assertTrue(catalog.find(null).isEmpty());
    }

    @Test
    public void anEmptyTableDefinesNoServices() {
        Assert.assertEquals(ServiceCatalog.fromRows(List.of()).size(), 0);
    }

    @Test
    public void rejectsADuplicateId() {
        assertRejected(List.of(ORDERS, ORDERS), "more than once");
    }

    @Test
    public void rejectsAMissingColumn() {
        assertRejected(List.of(new Row("orders", "Orders", null, "Shop", "App", "LOAD_BALANCE")),
                "missing port_offset");
        assertRejected(List.of(new Row("orders", "Orders", 1, " ", "App", "LOAD_BALANCE")), "missing app_group");
    }

    @Test
    public void rejectsAnUnknownMode() {
        assertRejected(List.of(new Row("orders", "Orders", 1, "Shop", "App", "ROUND_ROBIN")), "orders is invalid");
    }

    @Test
    public void rejectsANegativeOffset() {
        assertRejected(List.of(new Row("orders", "Orders", -1, "Shop", "App", "LOAD_BALANCE")), "orders is invalid");
    }

    // The schema's column names, which the registry reads by name
    @Test
    public void queriesTheSchemaColumns() {
        Assert.assertEquals(ServiceCatalog.QUERY,
                "SELECT id, name, port_offset, app_group, app_name, mode FROM services ORDER BY id");
    }

    private static void assertRejected(List<Row> rows, String expectedMessage) {
        IllegalStateException e = Assert.expectThrows(IllegalStateException.class, () -> ServiceCatalog.fromRows(rows));
        Assert.assertTrue(e.getMessage().contains(expectedMessage), e.getMessage());
    }
}
