package dev.heysulo.archon.sdk.registry;

import dev.heysulo.archon.dictionary.sdk.messages.PrimaryRegistryLookupResponseMessage;
import org.testng.Assert;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static dev.heysulo.archon.sdk.registry.RegistryLookupService.NOT_PRIMARY;

public class RegistryLookupServiceTest {

    private static final List<String> LOCATIONS = List.of("10.21.3.201", "10.21.3.202", "10.21.3.203");

    @Test
    public void findsTheOnlyPrimary() {
        Assert.assertEquals(choose(Map.of("10.21.3.202", 4L)), "10.21.3.202");
    }

    @Test
    public void findsNothingWhenNoRegistryIsPrimary() {
        Assert.assertNull(choose(Map.of()));
    }

    // A partitioned primary still answers until it loses the vote; the newly elected one has the newer term
    @Test
    public void prefersTheNewerTermWhenTwoClaimToBePrimary() {
        Assert.assertEquals(choose(Map.of("10.21.3.201", 1L, "10.21.3.203", 2L)), "10.21.3.203");
        Assert.assertEquals(choose(Map.of("10.21.3.201", 7L, "10.21.3.203", 2L)), "10.21.3.201");
    }

    // A cluster restarted from scratch counts terms from 1 again. An application that saw term 9 must still
    // follow it, or it would never reconnect.
    @Test
    public void acceptsALonePrimaryWhateverItsTerm() {
        Assert.assertEquals(choose(Map.of("10.21.3.201", 9L)), "10.21.3.201");
        Assert.assertEquals(choose(Map.of("10.21.3.201", 1L)), "10.21.3.201");
    }

    // The portal looks up from two threads at once. A mirror's lookup must never take the primary's answer.
    @Test
    public void concurrentLookupsKeepTheirOwnAnswers() throws Exception {
        RegistryLookupService.Lookup askingPrimary = new RegistryLookupService.Lookup();
        RegistryLookupService.Lookup askingMirror = new RegistryLookupService.Lookup();
        PrimaryRegistryLookupResponseMessage primary = new PrimaryRegistryLookupResponseMessage(1);
        primary.setTerm(10);

        askingPrimary.onMessage(null, primary);

        Assert.assertEquals(askingPrimary.answer.get(1, TimeUnit.SECONDS), Long.valueOf(10));
        Assert.assertFalse(askingMirror.answer.isDone());
        askingMirror.onMessage(null, new PrimaryRegistryLookupResponseMessage(2));
        Assert.assertEquals(askingMirror.answer.get(1, TimeUnit.SECONDS), Long.valueOf(NOT_PRIMARY));
    }

    private static String choose(Map<String, Long> primaries) {
        return RegistryLookupService.choosePrimary(LOCATIONS,
                location -> primaries.getOrDefault(location, NOT_PRIMARY));
    }
}
