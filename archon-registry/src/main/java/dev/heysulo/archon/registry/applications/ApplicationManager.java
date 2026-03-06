package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.dictionary.sdk.enums.RunLevel;
import dev.heysulo.archon.registry.constants.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

public class ApplicationManager {
    private static final Logger logger = LoggerFactory.getLogger(ApplicationManager.class);
    private static ApplicationManager instance;
    public static final Map<String, String> namespaceLock = new HashMap<>();
    private final Map<String, ArrayList<Application>> applications = new HashMap<>();

    public static ApplicationManager getInstance() {
        if (instance == null) {
            instance = new ApplicationManager();
        }
        return instance;
    }

    public Application getApplicationInstance(String group, String name, int rank) {
        synchronized (getNamespaceLock(group, name)) {
            String appDefName = buildApplicationDefinitionName(group, name);
            List<Application> appList = applications.computeIfAbsent(appDefName, k -> new ArrayList<>());
            return appList.stream()
                    .filter(a -> a.getRank() == rank)
                    .findFirst()
                    .orElseGet(() -> {
                        Application newApp = createApplication(group, name, rank);
                        appList.add(newApp);
                        return newApp;
                    });
        }
    }

    public Application getApplicationInstance(String group, String name) {
        int newRank = 0;
        synchronized (getNamespaceLock(group, name)) {
            newRank = findNextAvailableRank(group, name);
        }
        return getApplicationInstance(group, name, newRank);
    }

    private int findNextAvailableRank(String group, String name) {
        synchronized (getNamespaceLock(group, name)) {
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
    }

    private Application createApplication(String group, String name, int rank) {
        synchronized (getNamespaceLock(group, name)) {
            if (Constants.APPLICATION_GROUP_NAME.equals(group) && Constants.APPLICATION_NAME_REGISTRY.equals(name)) {
                return new RegistryApplication(group, name, rank);
            }
            return new Application(group, name, rank);
        }
    }

    public void electLeader(String group, String name) {
        synchronized (getNamespaceLock(group, name)) {
            String appDefName = buildApplicationDefinitionName(group, name);
            logger.info(">>>>>>>>>>>>> Electing leader for app definition: {}", appDefName);
            Optional<Application> candidate = applications.get(appDefName)
                    .stream()
                    .filter(app -> app.getRunLevel() == RunLevel.RUNNING)
                    .findFirst();
            if (candidate.isPresent()) {
                candidate.get().promote();
            } else {
                logger.warn("Could not find leader for {}", appDefName);
            }
        }
    }
    
    private static String buildApplicationDefinitionName(String group, String name) {
        return String.format("%s:%s", group, name);
    }

    public static String getNamespaceLock(String group, String name) {
        return namespaceLock.computeIfAbsent(buildApplicationDefinitionName(group, name), k -> "");
    }
}
