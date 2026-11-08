package dev.heysulo.archon.sdk.services;

import dev.heysulo.archon.dictionary.sdk.services.ServiceDefinition;

/** Works out the port an application instance serves a service on: its base port plus the service's offset. */
public final class ServicePorts {
    public static final String BASE_PORT_ENV = "ARCHON_BASE_PORT";
    private static final int MAX_PORT = 65535;

    private ServicePorts() {
    }

    public static int fromEnvironment(ServiceDefinition definition) {
        return resolve(System.getenv(BASE_PORT_ENV), definition);
    }

    static int resolve(String basePort, ServiceDefinition definition) {
        if (basePort == null || basePort.isBlank()) {
            throw new ServiceException(String.format("Cannot start service %s: %s is not set", definition.id(),
                    BASE_PORT_ENV));
        }
        int base;
        try {
            base = Integer.parseInt(basePort.trim());
        } catch (NumberFormatException e) {
            throw new ServiceException(String.format("Cannot start service %s: %s is not a number: %s",
                    definition.id(), BASE_PORT_ENV, basePort), e);
        }
        int port = base + definition.portOffset();
        if (base <= 0 || port > MAX_PORT) {
            throw new ServiceException(String.format("Cannot start service %s: base port %d plus offset %d is not "
                    + "a valid port", definition.id(), base, definition.portOffset()));
        }
        return port;
    }
}
