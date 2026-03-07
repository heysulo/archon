package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.registry.constants.Constants;
import dev.heysulo.archon.registry.server.RegistryServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public class ApplicationManager {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationManager.class);
    private static ApplicationManager instance;
    private static final ConcurrentHashMap<String, ReentrantLock> namespaceLocks = new ConcurrentHashMap<>();
    private final Map<String, ArrayList<Application>> applications = new HashMap<>();

    public static ApplicationManager getInstance() {
        if (instance == null) {
            instance = new ApplicationManager();
        }
        return instance;
    }

    public Application findApplication(String group, String name, int rank) {
        return findAllApplications(group, name).stream()
                .filter(a -> a.getRank() == rank)
                .findFirst()
                .orElse(null);
    }

    public List<Application> findAllApplications(String group, String name) {
        String appDefName = buildApplicationDefinitionName(group, name);
        return applications.computeIfAbsent(appDefName, k -> new ArrayList<>());
    }

    public Application createApplicationWithNextAvailableRank(String group, String name) {
        return createApplication(group, name, findNextAvailableApplicationRank(group, name));
    }

    private int findNextAvailableApplicationRank(String group, String name) {
        String appDefName = buildApplicationDefinitionName(group, name);
        List<Application> appList = applications.computeIfAbsent(appDefName, k -> new ArrayList<>());
        Set<Integer> existingRanks = appList.stream()
                .filter(Application::isRunning)
                .map(Application::getRank)
                .collect(Collectors.toSet());
        int candidate = 1;
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
        List<Application> appList = applications.computeIfAbsent(appDefName, k -> new ArrayList<>());
        application.setRegistryServer(RegistryServer.getInstance());
        appList.add(application);
        return application;
    }

    public void electLeader(String group, String name) {
        String appDefName = buildApplicationDefinitionName(group, name);
        logger.info(">>>>>>>>>>>>> Electing leader for app definition: {}", appDefName);
        List<Application> candidates = applications.get(appDefName)
                .stream()
                .filter(app -> app.getRunLevel() == RunLevel.RUNNING)
                .toList();

        for (Application candidate : candidates) {
            if (candidate.promote()) {
                logger.info("Successfully promoted {} to leader for {}", candidate.getDisplayName(), appDefName);
                return;
            }
            logger.warn("Promotion failed for {}. Trying next candidate.", candidate.getDisplayName());
        }
        logger.warn("Could not find a viable leader for {}", appDefName);
    }
    
    private static String buildApplicationDefinitionName(String group, String name) {
        return String.format("%s:%s", group, name);
    }

    public static void withNamespaceLock(String group, String name, Runnable action) {
        ReentrantLock lock = namespaceLocks.computeIfAbsent(buildApplicationDefinitionName(group, name), k -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
        }
    }

    public static <T> T withNamespaceLockReturn(String group, String name, Supplier<T> action) {
        ReentrantLock lock = namespaceLocks.computeIfAbsent(buildApplicationDefinitionName(group, name), k -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }
}
