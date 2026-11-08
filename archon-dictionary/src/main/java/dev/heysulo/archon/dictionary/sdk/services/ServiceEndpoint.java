package dev.heysulo.archon.dictionary.sdk.services;

import java.io.Serial;
import java.io.Serializable;

/**
 * A running instance of a service.
 *
 * @param host    the address of the application instance running it
 * @param port    the port it listens on
 * @param rank    the rank of the application instance running it
 * @param primary whether it is the serving instance of a primary/mirror service. Always false when load balancing.
 */
public record ServiceEndpoint(String host, int port, int rank, boolean primary) implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;
}
