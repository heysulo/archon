package dev.heysulo.archon.registry.server;

import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.applications.ApplicationManager;
import dev.heysulo.archon.registry.messages.sync.ApplicationSyncMessage;
import dev.heysulo.archon.registry.messages.sync.StateSnapshotComplete;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static dev.heysulo.archon.registry.constants.Constants.RANK_PRIMARY;

/**
 * A registry is ready, for Kubernetes, as the primary or as a mirror holding the primary's complete state, so a
 * rolling restart never makes a registry primary before it knows every application.
 */
public class RegistryReadinessTest {
    private Path file;

    @BeforeMethod
    public void useATemporaryFile() throws Exception {
        file = Files.createTempFile("archon-ready", "").toAbsolutePath();
        Files.delete(file);
        RegistryReadiness.useFile(file);
    }

    @AfterMethod
    public void restore() throws Exception {
        Files.deleteIfExists(file);
        RegistryServer.setRank(RANK_PRIMARY);
    }

    @Test
    public void writesTheFileOnlyWhileReady() {
        RegistryReadiness.notReady("starting");
        Assert.assertFalse(Files.exists(file));
        RegistryReadiness.ready("acting as primary");
        Assert.assertTrue(Files.exists(file));
        RegistryReadiness.notReady("lost the primary");
        Assert.assertFalse(Files.exists(file));
    }

    @Test
    public void theSnapshotEndsWithItsCompletion() {
        RegistryServer.setRank(RANK_PRIMARY);
        ApplicationManager.getInstance().createApplication("Ready", "Snapshot", 1);
        Application mirror = ApplicationManager.getInstance().createApplication("Archon", "Registry", 7);
        FakeConnection connection = new FakeConnection();
        mirror.setConnection(connection);

        RegistryServer.getInstance().sendStateSnapshot(mirror);

        Assert.assertNotNull(connection.last(ApplicationSyncMessage.class));
        Assert.assertTrue(connection.lastSent() instanceof StateSnapshotComplete, "the completion comes last");
    }

    @Test
    public void mirrorsLearnAStartingInstancesToken() {
        RegistryServer.setRank(RANK_PRIMARY);
        Application mirror = ApplicationManager.getInstance().createApplication("Archon", "Registry", 8);
        FakeConnection toMirror = new FakeConnection();
        mirror.setConnection(toMirror);
        mirror.setRunLevel(dev.heysulo.archon.dictionary.sdk.enums.RunLevel.RUNNING);
        Application starting = ApplicationManager.getInstance().createApplication("Ready", "Starting", 3);
        // A rank reused after an instance that held it died, so an old token is on record
        starting.setAuthenticationToken("the previous instance's");

        starting.handleRegistration(new FakeConnection());

        ApplicationSyncMessage synced = toMirror.last(ApplicationSyncMessage.class);
        Assert.assertEquals(synced.getRunLevel(), dev.heysulo.archon.dictionary.sdk.enums.RunLevel.STARTING);
        Assert.assertEquals(synced.getAuthenticationToken(), starting.getAuthenticationToken(),
                "a mirror promoted before the instance acknowledges must accept it back");
    }

    @Test
    public void aMirrorIsReadyOnceTheSnapshotIsComplete() {
        RegistryServer.setRank(2);
        RegistryReadiness.notReady("waiting for the primary's state");
        Application primary = ApplicationManager.getInstance().createApplication("Archon", "Registry", 9);
        FakeConnection toPrimary = new FakeConnection();

        primary.onMessage(toPrimary, new ApplicationSyncMessage(primary));
        Assert.assertFalse(RegistryReadiness.isReady(), "not until the last record");
        primary.onMessage(toPrimary, new StateSnapshotComplete());
        Assert.assertTrue(RegistryReadiness.isReady());
        Assert.assertTrue(Files.exists(file));
    }
}
