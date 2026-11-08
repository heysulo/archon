package dev.heysulo.archon.registry.messages.leaderelection;

import dev.heysulo.archon.dictionary.sdk.messages.ApplicationRegistrationResponseMessage;

public class RegistryRegistrationResponse extends ApplicationRegistrationResponseMessage {
    private static final long serialVersionUID = -2143573046129980732L;

    public RegistryRegistrationResponse(String group, String name, int rank) {
        super(group, name, rank);
    }
}
