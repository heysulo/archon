package dev.heysulo.archon.registry.utils;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;
import dev.heysulo.archon.registry.applications.Application;
import dev.heysulo.archon.registry.messages.leaderelection.RegistryRegistrationResponse;
import dev.heysulo.databridge.core.common.Message;

public class Utils {
    public static Message createRegistrationResponse(Application application) {
        return new ApplicationRegistrationResponseMessage(
                application.getGroup(),
                application.getName(),
                application.getRank()
        );
    }

    public static Message createRegistryRegistrationResponse(Application application) {
        return new RegistryRegistrationResponse(
                application.getGroup(),
                application.getName(),
                application.getRank()
        );
    }
}
