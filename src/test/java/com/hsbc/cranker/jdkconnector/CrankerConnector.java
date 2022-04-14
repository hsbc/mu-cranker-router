package com.hsbc.cranker.jdkconnector;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.hsbc.cranker.jdkconnector.HttpUtils.urlEncode;
import static java.util.stream.Collectors.toList;

/**
 * A cranker connector maintains connections to one of more routers.
 * <p>Create a connector builder with {@link CrankerConnectorBuilder#connector()}</p>
 */
public interface CrankerConnector {

    /**
     * Creates the connections to the routers.
     */
    void start();

    /**
     * Starts a graceful disconnection from the routers which allows for zero-downtime deployments of components
     * that have multiple instances.
     * <p>This method will send a message to each router stating its intention to shut down, and the router will
     * stop sending new requests to this connector, and close any idle connections. When any active connections
     * have completed, the future returned by this returns.</p>
     * <p>So, to perform a zero-downtime deployment where there are at least 2 services, perform a restart on
     * each instance sequentially. For each instance, first call this stop method, and when it completes, shut down
     * the target server. Then start a new instance before shutting down the next instance.</p>
     * @return A future that completes when no more requests are being processed
     */
    CompletableFuture<Void> stop();

    /**
     * @return A unique ID assigned to this connector that is provided to the router for diagnostic reasons.
     */
    String connectorId();

    /**
     * @return Meta data about the routers that this connector is connected to. Provided for diagnostic purposes.
     */
    List<RouterRegistration> routers();
}

class CrankerConnectorImpl implements CrankerConnector {

    private volatile List<RouterRegistrationImpl> routers = Collections.emptyList();
    private final String connectorId;
    private final RouterRegistrationImpl.Factory routerConFactory;
    private final Supplier<Collection<URI>> crankerUriSupplier;
    private final RouterEventListener routerEventListener;
    private final String componentName;
    private volatile ScheduledExecutorService routerUpdateExecutor;

    CrankerConnectorImpl(String connectorId, RouterRegistrationImpl.Factory routerConFactory, Supplier<Collection<URI>> crankerUriSupplier, String componentName, RouterEventListener routerEventListener) {
        this.componentName = componentName;
        this.connectorId = connectorId;
        this.routerConFactory = routerConFactory;
        this.crankerUriSupplier = crankerUriSupplier;
        this.routerEventListener = routerEventListener;
    }

    CompletableFuture<Void> updateRouters() {
        var before = this.routers;
        Collection<URI> newUris = crankerUriSupplier.get();
        var toAdd = newUris.stream()
            .filter(uri -> before.stream().noneMatch(existing -> sameRouter(uri, existing.registrationUri())))
            .map(uri -> uri.resolve("/register/?connectorInstanceID=" + urlEncode(connectorId) + "&componentName=" + urlEncode(componentName)))
            .map(routerConFactory::create).collect(toList());

        var toRemove = before.stream().filter(existing -> newUris.stream().noneMatch(newUri -> sameRouter(newUri, existing.registrationUri()))).collect(toList());

        if (!toAdd.isEmpty() || !toRemove.isEmpty()) {
            var toLeave = before.stream().filter(existing -> newUris.stream().anyMatch(newUri -> sameRouter(newUri, existing.registrationUri()))).collect(toList());
            var result = new ArrayList<>(toLeave);
            result.addAll(toAdd);
            this.routers = result;
            for (RouterRegistrationImpl newOne : toAdd) {
                newOne.start();
            }

            if (routerEventListener != null) {
                routerEventListener.onRegistrationChanged(
                    new RouterEventListener.ChangeData(
                        toAdd.stream().map(RouterRegistration.class::cast).collect(Collectors.toUnmodifiableList()),
                        toRemove.stream().map(RouterRegistration.class::cast).collect(Collectors.toUnmodifiableList()),
                        toLeave.stream().map(RouterRegistration.class::cast).collect(Collectors.toUnmodifiableList()))
                );
            }

            return CompletableFuture.allOf(toRemove.stream().map(RouterRegistrationImpl::stop).toArray(CompletableFuture<?>[]::new));
        }
        return null;
    }

    private static boolean sameRouter(URI registrationUrl1, URI registrationUrl2) {
        return registrationUrl1.getScheme().equals(registrationUrl2.getScheme()) && registrationUrl1.getAuthority().equals(registrationUrl2.getAuthority());
    }

    @Override
    public void start() {
        routerUpdateExecutor = Executors.newSingleThreadScheduledExecutor();
        routerConFactory.start();
        updateRouters();
        for (RouterRegistrationImpl registration : routers) {
            registration.start();
        }
        routerUpdateExecutor.scheduleWithFixedDelay(this::updateRouters, 1, 1, TimeUnit.MINUTES);
    }

    @Override
    public CompletableFuture<Void> stop() {
        if (this.routerUpdateExecutor == null) {
            throw new IllegalStateException("Cannot call stop() when the connector is not running. Did you call stop() twice?");
        }

        routerConFactory.stop();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (RouterRegistrationImpl registration : routers) {
            futures.add(registration.stop());
        }
        ScheduledExecutorService exec = this.routerUpdateExecutor;
        this.routerUpdateExecutor = null;
        exec.shutdownNow();
        futures.add(CompletableFuture.runAsync(() -> {
            try {
                exec.awaitTermination(1, TimeUnit.MINUTES);
            } catch (InterruptedException ignored) {
            }
        }));
        routers.clear();
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }

    @Override
    public String connectorId() {
        return connectorId;
    }

    @Override
    public List<RouterRegistration> routers() {
        return Collections.unmodifiableList(routers);
    }

    @Override
    public String toString() {
        return "CrankerConnector (" + connectorId + ") registered to: " + routers;
    }
}
