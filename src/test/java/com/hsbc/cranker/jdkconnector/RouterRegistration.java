package com.hsbc.cranker.jdkconnector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.Collection;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Information about connections to a router.
 * <p>To get this data, call {@link CrankerConnector#routers()}</p>
 */
public interface RouterRegistration {

    /**
     * @return The number of expected idle connections to this router
     */
    int expectedWindowSize();

    /**
     * @return The current number of idle connections
     */
    int idleSocketSize();

    /**
     * @return The sockets that are currently connected to the router, ready to process a request
     */
    Collection<ConnectorSocket> idleSockets();

    /**
     * @return The router's websocket registration URI
     */
    URI registrationUri();

    /**
     * @return The current state of the connection to this router
     */
    State state();

    /**
     * If a router is unavailable, then the connector will repeatedly retry (with exponential backoff, up to 10 seconds).
     * This returns the current number of failed attempts. When an attempt is succesful, this is reset to 0.
     * @return The current number of failed attempts to connect to this router
     */
    int currentUnsuccessfulConnectionAttempts();

    /**
     * @return If this socket is not connected due to an error, then this is the reason; otherwise this is null.
     */
    Throwable lastConnectionError();

    enum State {NOT_STARTED, ACTIVE, STOPPING, STOPPED}
}

class RouterRegistrationImpl implements ConnectorSocketListener, RouterRegistration {

    private volatile State state = State.NOT_STARTED;
    private final HttpClient client;
    private final URI registrationUri;
    private final String route;
    private final int windowSize;
    private final Set<ConnectorSocket> idleSockets = ConcurrentHashMap.newKeySet();
    private final URI targetUri;
    private final ScheduledExecutorService executor;
    private final AtomicInteger connectAttempts = new AtomicInteger();
    private volatile Throwable lastConnectionError;
    private final RouterEventListener routerEventListener;

    RouterRegistrationImpl(HttpClient client, URI registrationUri, String route, int windowSize, URI targetUri, ScheduledExecutorService executor, RouterEventListener routerEventListener) {
        this.client = client;
        this.registrationUri = registrationUri;
        this.route = route;
        this.windowSize = windowSize;
        this.targetUri = targetUri;
        this.executor = executor;
        this.routerEventListener = routerEventListener;
    }

    void start() {
        state = State.ACTIVE;
        addAnyMissing();
    }

    CompletableFuture<Void> stop() {
        state = State.STOPPING;
        URI deregisterUri = registrationUri.resolve("/deregister/?" + registrationUri.getRawQuery());
        return client.newWebSocketBuilder()
            .header("CrankerProtocol", "1.0")
            .header("Route", this.route)
            .buildAsync(deregisterUri, new WebSocket.Listener() {
                @Override
                public void onOpen(WebSocket webSocket) {
                    webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "");
                }
            })
            .thenRun(() -> state = State.STOPPED);
    }

    private void addAnyMissing() {
        while (state == State.ACTIVE && idleSockets.size() < windowSize) {
            ConnectorSocketImpl connectorSocket = new ConnectorSocketImpl(targetUri, client, this, executor);
            idleSockets.add(connectorSocket);

            client.newWebSocketBuilder()
                .header("CrankerProtocol", "1.0")
                .header("Route", route)
                .connectTimeout(Duration.ofMillis(5000))
                .buildAsync(registrationUri, connectorSocket)
                .whenComplete((webSocket, throwable) -> {
                    if (throwable == null) {
                        connectAttempts.set(0);
                        lastConnectionError = null;
                    } else {
                        lastConnectionError = throwable;
                        connectAttempts.incrementAndGet();
                        idleSockets.remove(connectorSocket);
                        if (routerEventListener != null) {
                            routerEventListener.onSocketConnectionError(this, throwable);
                        }
                        executor.schedule(this::addAnyMissing, retryAfterMillis(), TimeUnit.MILLISECONDS);
                    }
                });
        }
    }

    /**
     * @return Milliseconds to wait until trying again, increasing exponentially, capped at 10 seconds
     */
    private int retryAfterMillis() {
        return 500 + Math.min(10000, (int)Math.pow(2, connectAttempts.get()));
    }

    @Override
    public void onConnectionAcquired(ConnectorSocket socket) {
        remove(socket);
    }

    @Override
    public void onError(ConnectorSocket socket, Throwable error) {
        remove(socket);
    }

    private void remove(ConnectorSocket socket) {
        idleSockets.remove(socket);
        addAnyMissing();
    }

    @Override
    public int expectedWindowSize() {
        return windowSize;
    }

    @Override
    public int idleSocketSize() {
        return idleSockets.size();
    }

    @Override
    public Collection<ConnectorSocket> idleSockets() {
        return idleSockets;
    }

    @Override
    public URI registrationUri() {
        return registrationUri;
    }

    @Override
    public State state() {
        return state;
    }

    @Override
    public int currentUnsuccessfulConnectionAttempts() {
        return connectAttempts.get();
    }

    @Override
    public Throwable lastConnectionError() {
        return lastConnectionError;
    }

    @Override
    public String toString() {
        return "RouterRegistration{" +
            "state=" + state +
            ", registrationUri=" + registrationUri +
            ", route='" + route + '\'' +
            ", windowSize=" + windowSize +
            ", targetUri=" + targetUri +
            ", connectAttempts=" + connectAttempts +
            ", lastConnectionError=" + lastConnectionError +
            ", idleSockets=" + idleSockets +
            '}';
    }


    static class Factory {
        private final HttpClient client;
        private final String route;
        private final int windowSize;
        private final URI targetUri;
        private volatile ScheduledExecutorService executor;
        private final RouterEventListener routerEventListener;

        Factory(HttpClient client, String route, int windowSize, URI targetUri, RouterEventListener routerEventListener) {
            this.client = client;
            this.route = route;
            this.windowSize = windowSize;
            this.targetUri = targetUri;
            this.routerEventListener = routerEventListener;
        }
        RouterRegistrationImpl create(URI registrationUri) {
            return new RouterRegistrationImpl(client, registrationUri, route, windowSize, targetUri, executor, routerEventListener);
        }
        void start() {
            executor = Executors.newSingleThreadScheduledExecutor();
        }
        void stop() {
            executor.shutdownNow();
        }
    }

}
