package dev.heysulo.archon.registry.messages.leaderelection;

import dev.heysulo.archon.dictionary.sdk.ApplicationRegistrationResponseMessage;

public class RegistryRegistrationResponse extends ApplicationRegistrationResponseMessage {
    public RegistryRegistrationResponse(String group, String name, int rank) {
        super(group, name, rank);
    }
}
