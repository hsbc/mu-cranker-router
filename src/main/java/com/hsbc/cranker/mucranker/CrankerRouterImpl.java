package com.hsbc.cranker.mucranker;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.ClientErrorException;
import javax.ws.rs.ForbiddenException;
import javax.ws.rs.ServerErrorException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static io.muserver.ContextHandlerBuilder.context;
import static io.muserver.WebSocketHandlerBuilder.webSocketHandler;


class CrankerRouterImpl implements CrankerRouter {
    private static final Logger log = LoggerFactory.getLogger(CrankerRouterImpl.class);

    private final IPValidator ipValidator;
    private final WebSocketFarm webSocketFarm;
    private final boolean discardClientForwardedHeaders;
    private final boolean sendLegacyForwardedHeaders;
    private final String viaValue;
    private final Set<String> doNotProxy;
    private final long idleTimeoutMillis;
    private final long pingScheduleMillis;
    private final List<ProxyListener> proxyListeners;
    private final DarkModeManager darkModeManager;

    CrankerRouterImpl(IPValidator ipValidator, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders, String viaValue, Set<String> doNotProxy, WebSocketFarm webSocketFarm, long idleTimeoutMillis, long pingScheduleMillis, List<ProxyListener> proxyListeners, DarkModeManager darkModeManager) {
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
        this.viaValue = viaValue;
        this.doNotProxy = doNotProxy;
        this.webSocketFarm = webSocketFarm;
        this.ipValidator = ipValidator;
        this.idleTimeoutMillis = idleTimeoutMillis;
        this.pingScheduleMillis = pingScheduleMillis;
        this.proxyListeners = proxyListeners;
        this.darkModeManager = darkModeManager;
    }

    @Override
    public MuHandler createRegistrationHandler() {
        WebSocketHandlerBuilder registerHandler = webSocketHandler()
            .withIdleReadTimeout(idleTimeoutMillis, TimeUnit.MILLISECONDS)
            .withPingSentAfterNoWritesFor((int) pingScheduleMillis, TimeUnit.MILLISECONDS)
            .withWebSocketFactory((request, responseHeaders) -> {
                responseHeaders.set("CrankerProtocol", CrankerProtocol.CRANKER_PROTOCOL_VERSION_1_0);
                validateRequest(ipValidator, request);
                return connectorRegisterToRouter(request);
            });
        WebSocketHandlerBuilder deregisterHandler = webSocketHandler()
            .withWebSocketFactory((request, responseHeaders) -> {
                validateRequest(ipValidator, request);
                responseHeaders.set("CrankerProtocol", CrankerProtocol.CRANKER_PROTOCOL_VERSION_1_0);
                String route = getRoute(request);
                String connectorInstanceID = request.query().get("connectorInstanceID", null);
                if (connectorInstanceID == null) {
                    log.info("the service" + route + " using unsupported zero down time connector, will not deregistersocket");
                } else {
                    String remoteAddr = request.remoteAddress();
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

    private RouterSocket connectorRegisterToRouter(MuRequest request) {
        String route = getRoute(request);
        String componentName = request.query().get("componentName");
        String connectorInstanceID = request.query().get("connectorInstanceID", "unknown-" + request.remoteAddress());
        RouterSocket routerSocket = new RouterSocket(route, componentName, webSocketFarm, connectorInstanceID, proxyListeners);
        routerSocket.setOnReadyForAction(() -> webSocketFarm.addWebSocketAsync(route, routerSocket));
        return routerSocket;
    }

    private static String getRoute(MuRequest request) {
        String route = request.headers().get("Route");
        if (Mutils.nullOrEmpty(route)) {
            route = "*";
        }
        return route;
    }

    private static void validateRequest(IPValidator ipValidator, MuRequest request) {
        validateIpAddress(ipValidator, request);
        validateCrankerProtocolVersion(request);
    }

    private static void validateIpAddress(IPValidator ipValidator, MuRequest request) {
        String remoteAddress = request.remoteAddress();
        if (!ipValidator.allow(remoteAddress)) {
            String errorMsg = "Fail to establish websocket connection to craker connector because of not supported ip address="
                + remoteAddress + " the routerName=" + Mutils.htmlEncode(request.headers().get("Route"));
            log.warn(errorMsg);
            throw new ForbiddenException(errorMsg);
        }
    }

    @Override
    public int idleConnectionCount() {
        return webSocketFarm.idleCount();
    }

    private static void validateCrankerProtocolVersion(MuRequest request) {
        String crankerProtocolVersion = request.headers().get("CrankerProtocol");
        if (!CrankerProtocol.validateCrankerProtocolVersion(crankerProtocolVersion, log)) {
            String message = "Failed to establish websocket connection to cranker " +
                "connector because of not supported cranker protocol version found: " + Mutils.htmlEncode(crankerProtocolVersion);
            throw new ServerErrorException(message, 501);
        }
    }

    @Override
    public MuHandler createHttpHandler() {
        return new CrankerMuHandler(webSocketFarm, discardClientForwardedHeaders, sendLegacyForwardedHeaders, viaValue, doNotProxy, proxyListeners);
    }

    @Override
    public RouterInfo collectInfo() {
        List<ConnectorService> services = new ArrayList<>();
        Set<DarkHost> darkHosts = webSocketFarm.getDarkHosts();
        RouterInfoImpl.addSocketData(services, webSocketFarm.getSockets(), darkHosts);
        return new RouterInfoImpl(services, darkHosts, webSocketFarm.getWaitingTasks());
    }

    @Override
    public void stop() {
       webSocketFarm.stop();
    }

    @Override
    public DarkModeManager darkModeManager() {
        return this.darkModeManager;
    }

}
