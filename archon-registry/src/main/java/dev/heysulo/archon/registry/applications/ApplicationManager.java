package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.server.RegistryServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ApplicationManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApplicationManager.class);
    private static ApplicationManager instance;
    private static final ConcurrentHashMap<String, ReentrantLock> namespaceLocks = new ConcurrentHashMap<>();
    // Read by lookups and the portal while registrations add to it, so both levels are safe to iterate concurrently
    private final Map<String, List<Application>> applications = new ConcurrentHashMap<>();

    private ApplicationManager() {
    }

    public static synchronized ApplicationManager getInstance() {
        if (instance == null) {
            instance = new ApplicationManager();
        }
        return instance;
    }

    /**
     * Finds the record for a rank, preferring a running one. Promotion moves an instance to rank 1 while the dead
     * leader's record keeps it, so a rank can briefly have a stale record next to its live owner.
     */
    public Application findApplication(String group, String name, int rank) {
        List<Application> matches = findAllApplications(group, name).stream()
                .filter(a -> a.getRank() == rank)
                .toList();
        return matches.stream()
                .filter(Application::isRunning)
                .findFirst()
                .orElse(matches.isEmpty() ? null : matches.get(0));
    }

    public List<Application> findAllApplications(String group, String name) {
        String appDefName = buildApplicationDefinitionName(group, name);
        return applications.computeIfAbsent(appDefName, k -> new CopyOnWriteArrayList<>());
    }

    public List<Application> findAllKnownApplications() {
        return applications.values().stream().flatMap(List::stream).toList();
    }

    public Application createApplicationWithNextAvailableRank(String group, String name) {
        return createApplicationWithNextAvailableRank(group, name, Constants.RANK_PRIMARY);
    }

    /**
     * Assigns the lowest free rank that is at least {@code lowestRank}, reusing a retired record for it if one exists.
     */
    public Application createApplicationWithNextAvailableRank(String group, String name, int lowestRank) {
        int rank = findNextAvailableApplicationRank(group, name, lowestRank);
        Application retired = findApplication(group, name, rank);
        return retired != null ? retired : createApplication(group, name, rank);
    }

    private int findNextAvailableApplicationRank(String group, String name, int lowestRank) {
        String appDefName = buildApplicationDefinitionName(group, name);
        List<Application> appList = applications.computeIfAbsent(appDefName, k -> new CopyOnWriteArrayList<>());
        Set<Integer> existingRanks = appList.stream()
                .filter(Application::isRunning)
                .map(Application::getRank)
                .collect(Collectors.toSet());
        int candidate = lowestRank;
        while (existingRanks.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    public Application createApplication(String group, String name, int rank) {
        Application application;
        if (Constants.APPLICATION_GROUP_NAME.equals(group)
                && Constants.APPLICATION_NAME_REGISTRY.equals(name)) {
            application = new RegistryApplication(group, name, rank);
        } else {
            application = new Application(group, name, rank);
        }
        String appDefName = buildApplicationDefinitionName(group, name);
        List<Application> appList = applications.computeIfAbsent(appDefName, k -> new CopyOnWriteArrayList<>());
        application.setRegistryServer(RegistryServer.getInstance());
        appList.add(application);
        return application;
    }

    public void electLeader(String group, String name) {
        String appDefName = buildApplicationDefinitionName(group, name);
        LOGGER.info("Electing leader for app definition: {}", appDefName);
        List<Application> candidates = applications.get(appDefName)
                .stream()
                .filter(app -> app.getRunLevel() == RunLevel.RUNNING)
                .toList();

        for (Application candidate : candidates) {
            if (candidate.promote()) {
                LOGGER.info("Successfully promoted {} to leader for {}", candidate.getDisplayName(), appDefName);
                return;
            }
            LOGGER.warn("Promotion failed for {}. Trying next candidate.", candidate.getDisplayName());
        }
        LOGGER.warn("Could not find a viable leader for {}", appDefName);
    }

    private static String buildApplicationDefinitionName(String group, String name) {
        return String.format("%s:%s", group, name);
    }

    public static void withNamespaceLock(String group, String name, Runnable action) {
        ReentrantLock lock = namespaceLocks.computeIfAbsent(buildApplicationDefinitionName(group, name),
                k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public static <T> T withNamespaceLockReturn(String group, String name, Supplier<T> action) {
        ReentrantLock lock = namespaceLocks.computeIfAbsent(buildApplicationDefinitionName(group, name),
                k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
