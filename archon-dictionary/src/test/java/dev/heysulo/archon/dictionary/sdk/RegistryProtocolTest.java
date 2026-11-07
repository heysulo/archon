package dev.heysulo.archon.dictionary.sdk;

import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;

public class RegistryProtocolTest {

    @Test
    public void missingLocationsMeansNone() {
        Assert.assertEquals(RegistryProtocol.parseLocations(null), List.of());
    }

    @Test
    public void locationsAreSeparatedByPipes() {
        Assert.assertEquals(RegistryProtocol.parseLocations("10.0.0.1|10.0.0.2"), List.of("10.0.0.1", "10.0.0.2"));
    }

    @Test
    public void singleLocationIsSupported() {
        Assert.assertEquals(RegistryProtocol.parseLocations("10.0.0.1"), List.of("10.0.0.1"));
    }
}
