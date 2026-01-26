package dev.heysulo.archon.registry.applications;

import dev.heysulo.archon.registry.constants.Constants;

import java.util.*;
import java.util.stream.Collectors;

public class ApplicationManager {
    private static ApplicationManager instance;
    private Map<String, ArrayList<Application>> applications = new HashMap<>();

    public static ApplicationManager getInstance() {
        if (instance == null) {
            instance = new ApplicationManager();
        }
        return instance;
    }

    public Application getApplicationInstance(String group, String name, int rank) {
        List<Application> appList = applications.computeIfAbsent(name, k -> new ArrayList<>());
        return appList.stream()
                .filter(a -> a.getRank() == rank)
                .findFirst()
                .orElseGet(() -> {
                    Application newApp = createApplication(group, name, rank);
                    appList.add(newApp);
                    return newApp;
                });
    }

    public Application getApplicationInstance(String group, String name) {
        int newRank = findNextAvailableRank(name);
        return getApplicationInstance(group, name, newRank);
    }

    private int findNextAvailableRank(String name) {
        List<Application> appList = applications.computeIfAbsent(name, k -> new ArrayList<>());
        Set<Integer> existingRanks = appList.stream()
                .map(Application::getRank)
                .collect(Collectors.toSet());
        int candidate = 1;
        while (existingRanks.contains(candidate)) {
            candidate++;
        }
        return candidate;
    }

    private Application createApplication(String group, String name, int rank) {
        if (Constants.APPLICATION_GROUP_NAME.equals(group) && Constants.APPLICATION_NAME_REGISTRY.equals(name)) {
            return new RegistryApplication(group, name, rank);
        }
        return new Application(group, name, rank);
    }
}
