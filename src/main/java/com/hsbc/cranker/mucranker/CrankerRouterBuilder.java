package com.hsbc.cranker.mucranker;

import io.muserver.Mutils;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Collections.emptyList;

/**
 * Use {@link #crankerRouter()} to create a builder where you can configure your cranker router options.
 * The {@link #start()} method returns a {@link CrankerRouter} object which can be used to create handlers
 * that you add to your own Mu Server instance.
 */
public class CrankerRouterBuilder {

    private IPValidator ipValidator = IPValidator.AllowAll;
    private boolean discardClientForwardedHeaders = false;
    private boolean sendLegacyForwardedHeaders = false;
    private String viaValue = "muc";
    private final Set<String> doNotProxyHeaders = new HashSet<>();
    private long maxWaitInMillis = 5000;
    private long pingAfterWriteMillis = 10000;
    private long idleReadTimeoutMills = 60000;
    private long routesKeepTimeMillis = 2 * 60 * 60 * 1000L;
    private List<ProxyListener> completionListeners = emptyList();
    private RouteResolver routeResolver;
    private List<String> supportedCrankerProtocol = List.of("1.0", "3.0");

    /**
     * @return A new builder
     */
    public static CrankerRouterBuilder crankerRouter() {
        return new CrankerRouterBuilder();
    }

    /**
     * If true, then any <code>Forwarded</code> or <code>X-Forwarded-*</code> headers that are sent
     * from the client to this reverse proxy will be dropped (defaults to false). Set this to <code>true</code>
     * if you do not trust the client.
     *
     * @param discardClientForwardedHeaders <code>true</code> to ignore Forwarded headers from the client; otherwise <code>false</code>
     * @return This builder
     */
    public CrankerRouterBuilder withDiscardClientForwardedHeaders(boolean discardClientForwardedHeaders) {
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        return this;
    }

    /**
     * Mucranker always sends <code>Forwarded</code> headers, however by default does not send the
     * non-standard <code>X-Forwarded-*</code> headers. Set this to <code>true</code> to enable
     * these legacy headers for older clients that rely on them.
     *
     * @param sendLegacyForwardedHeaders <code>true</code> to forward headers such as <code>X-Forwarded-Host</code>; otherwise <code>false</code>
     * @return This builder
     */
    public CrankerRouterBuilder withSendLegacyForwardedHeaders(boolean sendLegacyForwardedHeaders) {
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
        return this;
    }

    /**
     * The name to add as the <code>Via</code> header, which defaults to <code>muc</code>.
     *
     * @param viaName The name to add to the <code>Via</code> header.
     * @return This builder
     */
    public CrankerRouterBuilder withViaName(String viaName) {
        if (!viaName.matches("^[0-9a-zA-Z!#$%&'*+-.^_`|~:]+$")) {
            throw new IllegalArgumentException("Via names must be hostnames or HTTP header tokens");
        }
        this.viaValue = viaName;
        return this;
    }

    /**
     * Sets the idle timeout. If no messages are received within this time then the connection is closed.
     * <p>The default is 5 minutes.</p>
     *
     * @param duration The allowed timeout duration, or 0 to disable timeouts.
     * @param unit     The unit of the duration.
     * @return This builder
     */
    public CrankerRouterBuilder withIdleTimeout(long duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("The duration must be 0 or greater");
        }
        Mutils.notNull("unit", unit);
        this.idleReadTimeoutMills = unit.toMillis(duration);
        return this;
    }

    /**
     * <p>Sets the routes keep time if no more connector registered. Within the time, client will receive 503 (no cranker available).
     * After that, the route info will be cleaned up, and client will receive 404 if requesting against this route.</p>
     *
     * <p>The default keep time is 2 hours.</p>
     *
     * @param duration The duration for keeping the route info, or 0 to disable cleaning.
     * @param unit     The unit of the duration.
     * @return This builder
     */
    public CrankerRouterBuilder withRoutesKeepTime(long duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("The duration must be 0 or greater");
        }
        Mutils.notNull("unit", unit);
        this.routesKeepTimeMillis = unit.toMillis(duration);
        return this;
    }

    /**
     * Sets the amount of time to wait before sending a ping message if no messages having been sent.
     * <p>The default is 10 seconds.</p>
     *
     * @param duration The allowed timeout duration, or 0 to disable timeouts.
     * @param unit     The unit of the duration.
     * @return This builder
     */
    public CrankerRouterBuilder withPingSentAfterNoWritesFor(int duration, TimeUnit unit) {
        if (duration < 0) {
            throw new IllegalArgumentException("The duration must be 0 or greater");
        }
        Mutils.notNull("unit", unit);
        this.pingAfterWriteMillis = unit.toMillis(duration);
        return this;
    }

    /**
     * <p>When a request is made for a route that has no connectors connected currently, the router will wait for a
     * period to see if a connector will connect that can service the request.</p>
     * <p>This is important because if there are a burst of requests for a route, there might be just a few milliseconds
     * gap where there is no connector available, so there is no point sending an error back to the client if it would
     * be find after a short period.</p>
     * <p>This setting controls how long it waits before returning a <code>503 Service Unavailable</code>
     * to the client.</p>
     *
     * @param maxWaitInMillis The maximum wait time in millis for connector availability
     * @return This builder
     */
    public CrankerRouterBuilder withConnectorMaxWaitInMillis(long maxWaitInMillis) {
        this.maxWaitInMillis = maxWaitInMillis;
        return this;
    }

    /**
     * <p>Specifies whether or not to send the original <code>Host</code> header to the target server.</p>
     * <p>Reverse proxies are generally supposed to forward the original <code>Host</code> header to target
     * servers, however there are cases (particularly where you are proxying to HTTPS servers) that the
     * Host needs to match the Host of the SSL certificate (in which case you may see SNI-related errors).</p>
     *
     * @param sendHostToTarget If <code>true</code> (which is the default) the <code>Host</code> request
     *                         header will be sent to the target; if <code>false</code> then the host header
     *                         will be based on the target's URL.
     * @return This builder
     */
    public CrankerRouterBuilder proxyHostHeader(boolean sendHostToTarget) {
        if (sendHostToTarget) {
            doNotProxyHeaders.remove("host");
        } else {
            doNotProxyHeaders.add("host");
        }
        return this;
    }

    /**
     * Sets the IP validator for service registration requests. Defaults to {@link IPValidator#AllowAll}
     *
     * @param ipValidator The validator to use.
     * @return This builder
     */
    public CrankerRouterBuilder withRegistrationIpValidator(IPValidator ipValidator) {
        Mutils.notNull("ipValidator", ipValidator);
        this.ipValidator = ipValidator;
        return this;
    }

    /**
     * Registers proxy listeners to be called before, during and after requests are processed.
     *
     * @param proxyListeners The listeners to add.
     * @return This builder
     */
    public CrankerRouterBuilder withProxyListeners(List<ProxyListener> proxyListeners) {
        Mutils.notNull("proxyListeners", proxyListeners);
        this.completionListeners = proxyListeners;
        return this;
    }

    /**
     * Customized route resolver. If it's not specified, will use the default implementation in {@link RouteResolver#resolve(Set, String)}
     *
     * @param routeResolver The customized routeResolver.
     * @return This builder
     */
    public CrankerRouterBuilder withRouteResolver(RouteResolver routeResolver) {
        this.routeResolver = routeResolver;
        return this;
    }

    /**
     * Set cranker protocols. Default supporting both [&quot;cranker_1.0&quot;, &quot;cranker_3.0&quot;].
     *
     * @param protocols the protocols to support
     * @return this builder
     */
    public CrankerRouterBuilder withSupportedCrankerProtocols(List<String> protocols) {
        if (protocols == null || protocols.isEmpty()) {
            throw new CrankerProtocol.CrankerProtocolVersionNotFoundException("protocols is null or empty");
        }
        List<String> supportedProtocols = protocols.stream()
            .filter(Objects::nonNull)
            .map(String::toLowerCase)
            .map(it -> it.replace("cranker_", ""))
            .filter(it -> it.equalsIgnoreCase("1.0") || it.equalsIgnoreCase("3.0"))
            .collect(Collectors.toList());
        if (supportedProtocols.isEmpty()) {
            throw new CrankerProtocol.CrankerProtocolVersionNotFoundException("protocols is empty after filter");
        }
        this.supportedCrankerProtocol = supportedProtocols;
        return this;
    }


    /**
     * @return A newly created CrankerRouter object
     */
    public CrankerRouter start() {
        Set<String> doNotProxy = new HashSet<>(CrankerMuHandler.REPRESSED);
        doNotProxyHeaders.forEach(h -> doNotProxy.add(h.toLowerCase()));
        if (routeResolver == null) routeResolver = new RouteResolver() {};
        WebSocketFarm webSocketFarm = new WebSocketFarm(routeResolver, maxWaitInMillis);
        WebSocketFarmV3Holder webSocketFarmV3Holder = new WebSocketFarmV3Holder(routeResolver);
        webSocketFarm.start();
        List<ProxyListener> completionListeners = this.completionListeners.isEmpty() ? emptyList() : new ArrayList<>(this.completionListeners);
        DarkModeManager darkModeManager = new DarkModeManagerImpl(webSocketFarm);
        return new CrankerRouterImpl(ipValidator, discardClientForwardedHeaders,
            sendLegacyForwardedHeaders, viaValue, doNotProxy, webSocketFarm, webSocketFarmV3Holder,
            idleReadTimeoutMills, pingAfterWriteMillis, routesKeepTimeMillis, completionListeners, darkModeManager, supportedCrankerProtocol);
    }
}
