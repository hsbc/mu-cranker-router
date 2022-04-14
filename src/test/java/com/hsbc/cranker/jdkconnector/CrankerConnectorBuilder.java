package com.hsbc.cranker.jdkconnector;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.Collection;
import java.util.UUID;
import java.util.function.Supplier;

import static com.hsbc.cranker.jdkconnector.HttpUtils.createHttpClientBuilder;

/**
 * A builder of {@link CrankerConnector} objects
 */
public class CrankerConnectorBuilder {

    private Supplier<Collection<URI>> crankerUris;
    private String route;
    private URI target;
    private int slidingWindowSize = 2;
    private String componentName = "jdk-cranker-connector";
    private HttpClient client;
    private final String connectorId = UUID.randomUUID().toString();
    private RouterEventListener routerEventListener;

    /**
     * <p>Specifies the source of the router URIs to register with, for example: <code>builder.withRouterUris(RegistrationUriSuppliers.dnsLookup(URI.create("wss://router.example.org")))</code></p>
     * <p>This will be frequently called, allowing the routers to expand and shrink at runtime.</p>
     * <p>See the {@link RegistrationUriSuppliers} classes for some pre-built suppliers.</p>
     * @param supplier A function returning router websocket URIs to connect to. Note that no path is required.
     * @return This builder
     */
    public CrankerConnectorBuilder withRouterUris(Supplier<Collection<URI>> supplier) {
        this.crankerUris = supplier;
        return this;
    }

    /**
     * <p>Registers all A-records associated with the given URIs and uses those to connect to the cranker routers. This
     * DNS lookup happens periodically, so if the DNS is changed then the connected routers will auto-update.</p>
     * <p>This is just a shortcut for <code>withRouterUris(RegistrationUriSuppliers.dnsLookup(uris))</code></p>
     * @param uris The URIs to perform a DNS lookup on, and then connect to. Example: <code>URI.create("wss://router.example.org");</code>
     * @return This builder
     */
    public CrankerConnectorBuilder withRouterLookupByDNS(URI... uris) {
        return withRouterUris(RegistrationUriSuppliers.dnsLookup(uris));
    }

    /**
     * Specifies which route, or path prefix, this connector is serving. For example, if this connector serves
     * requests from <code>/my-app/*</code> then the route is <code>my-app</code>
     * @param route The path prefix of the web app, or <code>*</code> to forward everything that isn't matched
     *              by any other connectors
     * @return This builder
     */
    public CrankerConnectorBuilder withRoute(String route) {
        if (route == null) throw new IllegalArgumentException("route cannot be null");
        if ("*".equals(route) || route.matches("[a-zA-Z0-9_-]+")) {
            this.route = route;
            return this;
        } else {
            throw new IllegalArgumentException("Routes must contain only letters, numbers, underscores or hyphens");
        }
    }

    /**
     * Specifies the web server that calls should be proxied to
     * @param target The root URI of the web server to send requests to
     * @return This builder
     */
    public CrankerConnectorBuilder withTarget(URI target) {
        if (target == null) throw new IllegalArgumentException("Target cannot be null");
        this.target = target;
        return this;
    }

    /**
     * Optionally sets the number of idle connections per router.
     * @param slidingWindowSize the number of idle connections to connect to each router.
     * @return This builder
     */
    public CrankerConnectorBuilder withSlidingWindowSize(int slidingWindowSize) {
        this.slidingWindowSize = slidingWindowSize;
        return this;
    }

    /**
     * Sets the name of this component so that the router is able to expose in diagnostics the component name of
     * connections that are connected.
     * @param componentName The name of the target component, for example &quot;my-app-service&quot;
     * @return This builder
     */
    public CrankerConnectorBuilder withComponentName(String componentName) {
        this.componentName = componentName;
        return this;
    }

    /**
     * Sets a listener that is notified whenever the router registrations change.
     * <p>As some changes may occur due to external actions (e.g. a change to DNS if {@link RegistrationUriSuppliers#dnsLookup(Collection)} is used)
     * it may be useful to listen for changes and simply log the result for informational purposes.</p>
     * @param listener The listener to be called when registration changes
     * @return This builder
     */
    public CrankerConnectorBuilder withRouterRegistrationListener(RouterEventListener listener) {
        this.routerEventListener = listener;
        return this;
    }

    /**
     * Optionally sets an HTTP client used. If not set, then a default one will be used.
     * <p>An HTTP builder suitable for using can be created with {@link #createHttpClient(boolean)}</p>
     *
     * @param client The client to use to connect to the target server
     * @return This builder
     */
    public CrankerConnectorBuilder withHttpClient(HttpClient client) {
        this.client = client;
        return this;
    }

    /**
     * Creates a new HTTP Client builder that is suitable for use in the connector.
     *
     * @param trustAll If true, then any SSL certificate is allowed.
     * @return An HTTP Client builder
     */
    public static HttpClient.Builder createHttpClient(boolean trustAll) {
        return createHttpClientBuilder(trustAll)
            .followRedirects(HttpClient.Redirect.NEVER);
    }

    /**
     * @return A new connector builder
     */
    public static CrankerConnectorBuilder connector() {
        return new CrankerConnectorBuilder();
    }

    /**
     * Creates a connector. Consider using {@link #start()} instead.
     * @return A (non-started) connector
     * @see #start()
     */
    public CrankerConnector build() {

        if (route == null) throw new IllegalStateException("A route must be specified");
        if (target == null) throw new IllegalStateException("A target must be specified");
        if (componentName == null) throw new IllegalStateException("A componentName must be specified");

        HttpClient clientToUse = client != null ? client : createHttpClient(false).build();
        var factory = new RouterRegistrationImpl.Factory(clientToUse, route, slidingWindowSize, target, routerEventListener);
        return new CrankerConnectorImpl(connectorId, factory, crankerUris, componentName, routerEventListener);
    }

    /**
     * Creates and then starts a connector
     * @return A started connector
     */
    public CrankerConnector start() {
        CrankerConnector cc = build();
        cc.start();
        return cc;
    }
}
