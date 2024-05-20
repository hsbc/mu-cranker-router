package com.hsbc.cranker.mucranker;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;


import static com.hsbc.cranker.mucranker.RouterInfoImpl.getConnectorServiceList;
import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;


class CrankerRouterImpl implements CrankerRouter {
    private static final Logger log = LoggerFactory.getLogger(CrankerRouterImpl.class);

    private final static String CRANKER_PROTOCOL = "CrankerProtocol";
    private final static String SEC_WEBSOCKET_PROTOCOL = "Sec-WebSocket-Protocol";
    private final static String VERSION_3 = "3.0";
    private final static String VERSION_1 = "1.0";

    private final IPValidator ipValidator;
    private final WebSocketFarm webSocketFarm;
    private final WebSocketFarmV3Holder webSocketFarmV3Holder;
    private final boolean discardClientForwardedHeaders;
    private final boolean sendLegacyForwardedHeaders;
    private final String viaValue;
    private final Set<String> doNotProxy;
    private final long idleTimeoutMillis;
    private final long pingScheduleMillis;
    private final long routesKeepTimeMillis;
    private final List<ProxyListener> proxyListeners;
    private final DarkModeManager darkModeManager;
    private final List<String> supportedCrankerProtocols;
    private final ScheduledExecutorService executor;

    CrankerRouterImpl(IPValidator ipValidator, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders,
                      String viaValue, Set<String> doNotProxy, WebSocketFarm webSocketFarm,
                      WebSocketFarmV3Holder webSocketFarmV3Holder, long idleTimeoutMillis, long pingScheduleMillis,
                      long routesKeepTimeMillis, List<ProxyListener> proxyListeners, DarkModeManager darkModeManager,
                      List<String> supportedCrankerProtocol) {
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
        this.viaValue = viaValue;
        this.doNotProxy = doNotProxy;
        this.webSocketFarm = webSocketFarm;
        this.ipValidator = ipValidator;
        this.webSocketFarmV3Holder = webSocketFarmV3Holder;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.pingScheduleMillis = pingScheduleMillis;
        this.routesKeepTimeMillis = routesKeepTimeMillis;
        this.proxyListeners = proxyListeners;
        this.darkModeManager = darkModeManager;
        this.supportedCrankerProtocols = supportedCrankerProtocol;
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> new Thread(runnable, "cranker-router-cleanup"));
        if (routesKeepTimeMillis > 0) {
            this.executor.scheduleWithFixedDelay(this::cleanRoute, routesKeepTimeMillis, routesKeepTimeMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void cleanRoute() {
        try {
            webSocketFarm.cleanRoutes(routesKeepTimeMillis);
            webSocketFarmV3Holder.cleanRoutes(routesKeepTimeMillis);
        } catch (Throwable throwable) {
            log.warn("Exception on clean up routes", throwable);
        }
    }

    @Override
    public MuHandler createRegistrationHandler() {
        WebSocketHandlerBuilder registerHandler = webSocketHandler()
            .withIdleReadTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS)
            .withPingSentAfterNoWritesFor((int) pingScheduleMillis, TimeUnit.MILLISECONDS)
            .withWebSocketFactory((request, responseHeaders) -> {
                validateIpAddress(ipValidator, request);
                String version = validateAndGetCrankerProtocolVersion(this.supportedCrankerProtocols, request); // return "3.0" or "1.0"
                return connectorRegisterToRouter(request, responseHeaders, version);
            });
        WebSocketHandlerBuilder deregisterHandler = webSocketHandler()
            .withWebSocketFactory((request, responseHeaders) -> {
                validateIpAddress(ipValidator, request);
                String route = getRoute(request);
                String connectorInstanceID = request.query().get("connectorInstanceID", null);
                if (connectorInstanceID == null) {
                    log.info("the service" + route + " using unsupported zero down time connector, will not deregister socket");
                } else {
                    String remoteAddr = request.remoteAddress();
                    webSocketFarmV3Holder.deRegisterSocket(route, remoteAddr, connectorInstanceID);
                    webSocketFarm.deRegisterSocket(route, remoteAddr, connectorInstanceID);
                }
                return new BaseWebSocket() {
                    @Override
                    public void onConnect(MuWebSocketSession session) throws Exception {
                        super.onConnect(session);
                        try {
                            session.close(1000, "Deregister complete");
                        } catch (IOException ignored) {
                        }
                    }
                };
            });
        return context("")
            .addHandler((request, response) -> {
                if (request.method() == Method.TRACE) {
                    throw new ClientErrorException("Method Not Allowed", 405);
                }
                return false;
            })
            .addHandler(registerHandler.withPath("/register/").build())
            .addHandler(registerHandler.withPath("/register").build()) // some JS connectors don't have trailing slashes, and mu is very strict in this regard
            .addHandler(deregisterHandler.withPath("/deregister/").build())
            .addHandler(deregisterHandler.withPath("/deregister").build())
            .build();
    }

    private MuWebSocket connectorRegisterToRouter(MuRequest request, Headers responseHeaders, String version) {

        // only allowed to send back subProtocols which exist in request
        final String negotiateVersion = "cranker_" + version;
        final String subProtocol = request.headers().get(SEC_WEBSOCKET_PROTOCOL);
        if (subProtocol != null && subProtocol.contains(negotiateVersion)) {
            responseHeaders.set(SEC_WEBSOCKET_PROTOCOL, negotiateVersion);
        }

        String route = getRoute(request);
        String domain = getDomain(request);
        String componentName = request.query().get("componentName");
        String connectorInstanceID = request.query().get("connectorInstanceID", "unknown-" + request.remoteAddress());

        if (VERSION_3.equals(version)) {
            final WebSocketFarmV3 webSocketFarmV3 = webSocketFarmV3Holder.getOrCreateWebSocketFarmV3(domain);
            RouterSocketV3 routerSocketV3 = new RouterSocketV3(route, componentName, webSocketFarmV3,
                connectorInstanceID, proxyListeners,
                discardClientForwardedHeaders, sendLegacyForwardedHeaders, viaValue, doNotProxy);
            routerSocketV3.setOnReadyForAction(() -> webSocketFarmV3.addWebSocket(route, routerSocketV3));
            return routerSocketV3;
        } else {
            responseHeaders.set("CrankerProtocol", version);
            RouterSocket routerSocket = new RouterSocket(route, componentName, webSocketFarm, connectorInstanceID, proxyListeners);
            routerSocket.setOnReadyForAction(() -> webSocketFarm.addWebSocketAsync(route, routerSocket));
            return routerSocket;
        }
    }

    private static String getRoute(MuRequest request) {
        String route = request.headers().get("Route");
        if (Mutils.nullOrEmpty(route)) {
            route = "*";
        }
        return route;
    }

    private static String getDomain(MuRequest request) {
        String route = request.headers().get("Domain");
        if (Mutils.nullOrEmpty(route)) {
            route = "*";
        }
        return route;
    }

    private static void validateIpAddress(IPValidator ipValidator, MuRequest request) {
        String remoteAddress = request.connection().remoteAddress().getAddress().getHostAddress();
        if (!ipValidator.allow(remoteAddress)) {
            String errorMsg = "Fail to establish websocket connection to craker connector because of not supported ip address="
                + remoteAddress + " the routerName=" + Mutils.htmlEncode(request.headers().get("Route"));
            log.warn(errorMsg);
            throw new ForbiddenException(errorMsg);
        }
    }

    @Override
    public int idleConnectionCount() {
        return webSocketFarm.idleCount() + webSocketFarmV3Holder.idleCount();
    }


    private static String validateAndGetCrankerProtocolVersion(List<String> supportedCrankerProtocols, MuRequest request) {

        String subProtocols = request.headers().get(SEC_WEBSOCKET_PROTOCOL);
        String legacyProtocolHeader = request.headers().get(CRANKER_PROTOCOL);

        if (subProtocols == null && legacyProtocolHeader == null) {
            throw new CrankerProtocol.CrankerProtocolVersionNotFoundException("version is null, " +
                "please set header Sec-WebSocket-Protocol for cranker protocol negotiation");
        }

        // protocol negotiation
        if (subProtocols != null) {
            for (String subProtocol : subProtocols.split(",")) {
                final String version = subProtocol.toLowerCase().trim().replace("cranker_", "");
                if (supportedCrankerProtocols.contains(version)) {
                    return version;
                }
            }
        }

        // legacy header support
        if (legacyProtocolHeader != null && supportedCrankerProtocols.contains(legacyProtocolHeader)) {
            return legacyProtocolHeader;
        }

        throw new CrankerProtocol.CrankerProtocolVersionNotSupportedException("cranker protocol version not supported.");
    }

    @Override
    public MuHandler createHttpHandler() {
        return new CrankerMuHandler(webSocketFarm, webSocketFarmV3Holder, discardClientForwardedHeaders, sendLegacyForwardedHeaders, viaValue, doNotProxy, proxyListeners);
    }

    @Override
    public RouterInfo collectInfo() {
        Set<DarkHost> darkHosts = webSocketFarm.getDarkHosts();
        List<ConnectorService> services = getConnectorServiceList(webSocketFarm.getSockets(), webSocketFarmV3Holder.getSocketMaps(), darkHosts);
        return new RouterInfoImpl(services, darkHosts, webSocketFarm.getWaitingTasks());
    }

    @Override
    public void stop() {
        executor.shutdown();
        webSocketFarm.stop();
        webSocketFarmV3Holder.stop();
    }

    @Override
    public DarkModeManager darkModeManager() {
        return this.darkModeManager;
    }

}
