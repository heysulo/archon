package dev.heysulo.archon.registry.utils;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.messages.leaderelection.RegistryRegistrationResponse;

public final class Utils {
    private Utils() {
    }

    public static ApplicationRegistrationResponseMessage createRegistrationResponse(Application application) {
        return new ApplicationRegistrationResponseMessage(
                application.getGroup(),
                application.getName(),
                application.getRank()
        );
    }

    public static ApplicationRegistrationResponseMessage createRegistryRegistrationResponse(Application application) {
        return new RegistryRegistrationResponse(
                application.getGroup(),
                application.getName(),
                application.getRank()
        );
    }
}
