package dev.heysulo.archon.config;

import dev.heysulo.archon.dictionary.sdk.config.ConfigEntry;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHello;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigHelloResponse;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigRequest;
import dev.heysulo.archon.dictionary.sdk.messages.ConfigResponse;
import dev.heysulo.databridge.core.common.Connection;
import dev.heysulo.databridge.core.common.Message;
import dev.heysulo.databridge.core.server.Server;
import dev.heysulo.databridge.core.server.callback.ServerCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * The archon-config service. Each connection must first say who it is with a {@link ConfigHello}, which the
 * registry confirms; until then every request is refused. Applications then see and set their own configurations.
 * Only {@code Archon / Portal} may list and change every configuration.
 *
 * <p>Verification and database work run on worker threads, never on the network threads.
 */
final class ConfigServer implements ServerCallback {
    static final String PORTAL_GROUP = "Archon";
    static final String PORTAL_NAME = "Portal";
    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigServer.class);

    private final ConfigService service;
    private final Verifier verifier;
    private final ExecutorService workers;
    private final Map<Connection, Session> sessions = new ConcurrentHashMap<>();

    /** One connection, and who it turned out to be. */
    private static final class Session {
        private volatile String group;
        private volatile String name;
        private volatile boolean verifying;
        private volatile ConfigService.Subscriber subscriber;

        boolean verified() {
            return subscriber != null;
        }

        boolean isPortal() {
            return verified() && PORTAL_GROUP.equals(group) && PORTAL_NAME.equals(name);
        }
    }

    ConfigServer(ConfigService service, Verifier verifier, ExecutorService workers) {
        this.service = service;
        this.verifier = verifier;
        this.workers = workers;
    }

    @Override
    public void onConnect(Server server, Connection connection) {
        sessions.put(connection, new Session());
    }

    @Override
    public void onDisconnect(Server server, Connection connection) {
        Session session = sessions.remove(connection);
        if (session != null && session.subscriber != null) {
            service.unsubscribe(session.subscriber);
        }
    }

    @Override
    public void onMessage(Server server, Connection connection, Message message) {
        Session session = sessions.computeIfAbsent(connection, key -> new Session());
        if (message instanceof ConfigHello hello) {
            if (session.verified() || session.verifying) {
                return;
            }
            session.verifying = true;
            workers.execute(() -> hello(connection, session, hello));
        } else if (message instanceof ConfigRequest request) {
            if (!session.verified()) {
                connection.send(ConfigResponse.refused(request.getRequestId(), "Say who you are first"));
                return;
            }
            workers.execute(() -> connection.send(handle(session, request)));
        }
    }

    @Override
    public void onError(Server server, Connection connection, Throwable cause) {
        LOGGER.debug("Connection error from {}: {}", connection.getRemoteAddress(), cause.getMessage());
    }

    private void hello(Connection connection, Session session, ConfigHello hello) {
        try {
            Verifier.Result result = verifier.verify(hello.getGroup(), hello.getName(), hello.getRank(),
                    hello.getAuthenticationToken());
            if (result != Verifier.Result.VALID) {
                String reason = result == Verifier.Result.UNAVAILABLE
                        ? "Cannot confirm who you are right now; try again shortly"
                        : "The registry does not know this instance";
                LOGGER.warn("Refused {}/{} rank {} from {}: {}", hello.getGroup(), hello.getName(), hello.getRank(),
                        connection.getRemoteAddress(), result);
                connection.send(ConfigHelloResponse.refused(reason));
                return;
            }
            session.group = hello.getGroup();
            session.name = hello.getName();
            ConfigService.Subscriber subscriber = new ConfigService.Subscriber(hello.getGroup(), hello.getName(),
                    connection::send);
            List<ConfigEntry> entries = service.subscribe(subscriber);
            session.subscriber = subscriber;
            if (!sessions.containsKey(connection)) {
                // Closed while being verified
                service.unsubscribe(subscriber);
                return;
            }
            connection.send(ConfigHelloResponse.accepted(entries));
            LOGGER.info("{}/{} rank {} connected and sees {} configurations", hello.getGroup(), hello.getName(),
                    hello.getRank(), entries.size());
        } catch (RuntimeException e) {
            LOGGER.error("Hello from {}/{} failed", hello.getGroup(), hello.getName(), e);
            connection.send(ConfigHelloResponse.refused("Something went wrong; try again shortly"));
        } finally {
            session.verifying = false;
        }
    }

    ConfigResponse handle(Session session, ConfigRequest request) {
        long id = request.getRequestId();
        try {
            ConfigEntry entry = request.getEntry();
            ConfigRequest.Operation operation = request.getOperation();
            if (operation == null) {
                return ConfigResponse.refused(id, "Say what to do");
            }
            if (operation == ConfigRequest.Operation.SET) {
                if (entry == null) {
                    return ConfigResponse.refused(id, "Say which configuration to set");
                }
                return ConfigResponse.ok(id, List.of(service.setValue(session.group, session.name, entry.name(),
                        entry.value())));
            }
            if (!session.isPortal()) {
                return ConfigResponse.refused(id, "Only the portal manages configurations");
            }
            String actor = request.getActor() == null || request.getActor().isBlank() ? "portal" : request.getActor();
            return switch (operation) {
                case LIST_ALL -> ConfigResponse.ok(id, service.listAll());
                case CREATE -> ConfigResponse.ok(id, List.of(service.create(entry, actor)));
                case UPDATE -> ConfigResponse.ok(id, List.of(service.update(entry, actor)));
                case DELETE -> {
                    if (entry == null) {
                        yield ConfigResponse.refused(id, "Say which configuration to delete");
                    }
                    service.delete(entry.id(), actor);
                    yield ConfigResponse.ok(id, List.of());
                }
                default -> ConfigResponse.refused(id, "Unknown operation");
            };
        } catch (ConfigException e) {
            return ConfigResponse.refused(id, e.getMessage());
        } catch (RuntimeException e) {
            LOGGER.error("Request {} from {}/{} failed", request.getOperation(), session.group, session.name, e);
            return ConfigResponse.refused(id, "archon-config cannot reach its database; try again shortly");
        }
    }

    int sessionCount() {
        return sessions.size();
    }
}
