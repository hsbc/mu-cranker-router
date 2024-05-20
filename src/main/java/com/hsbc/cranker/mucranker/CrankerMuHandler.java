package com.hsbc.cranker.mucranker;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.*;

import static java.util.Arrays.asList;

class CrankerMuHandler implements MuHandler {

    private static final Logger log = LoggerFactory.getLogger(CrankerMuHandler.class);
    private static final String ANY_DOMAIN = "*";

    static final Set<String> HOP_BY_HOP = new HashSet<>(asList(
            "keep-alive", "transfer-encoding",
            "te", "connection", "trailer", "upgrade",
            "proxy-authorization",
            "proxy-authenticate"
    ));
    static final Set<String> REPRESSED;
    static final String MU_ID = "muid";

    private static final String ipAddress;
    private final Random random = new Random();

    static {
        String ip;
        try {
            ip = InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            ip = "unknown";
            log.info("Could not find local address so using " + ip);
        }
        ipAddress = ip;

        List<String> doNotForwardToTarget = new ArrayList<>();
        doNotForwardToTarget.addAll(HOP_BY_HOP);
        doNotForwardToTarget.addAll(asList(
                // expect is already handled by mu server, so if it's forwarded it will break stuff
                "expect",

                // Headers that mucranker will overwrite
                "forwarded", "x-forwarded-by", "x-forwarded-for", "x-forwarded-host", "x-forwarded-proto", "x-forwarded-port", "x-forwarded-server", "via"
        ));
        REPRESSED = new HashSet<>(doNotForwardToTarget);
    }

    private final WebSocketFarm webSocketFarm;
    private final WebSocketFarmV3Holder webSocketFarmV3Holder;
    private final boolean discardClientForwardedHeaders;
    private final boolean sendLegacyForwardedHeaders;
    private final String viaValue;
    private final Set<String> doNotProxy;
    private final List<ProxyListener> proxyListeners;

    CrankerMuHandler(WebSocketFarm webSocketFarm, WebSocketFarmV3Holder webSocketFarmV3Holder, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders, String viaValue, Set<String> doNotProxy, List<ProxyListener> proxyListeners) {
        this.webSocketFarm = webSocketFarm;
        this.webSocketFarmV3Holder = webSocketFarmV3Holder;
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
        this.viaValue = viaValue;
        this.doNotProxy = doNotProxy;
        this.proxyListeners = proxyListeners;
    }

    @Override
    public boolean handle(MuRequest clientRequest, MuResponse clientResponse) throws Exception {

        if (clientRequest.attribute(MU_ID) == null) {
            clientRequest.attribute(MU_ID, UUID.randomUUID().toString());
        }

        if (clientRequest.method() == Method.TRACE) {
            clientResponse.status(405);
            clientResponse.write("Method not supported");
            return true;
        }

        String target = clientRequest.uri().getPath();
        String domain = clientRequest.uri().getHost();
        AsyncHandle asyncHandle = clientRequest.handleAsync();

        // try route by domain
        if (webSocketFarmV3Holder.canHandle(domain, target, true)) {
            return dispatchV3(clientRequest, clientResponse, domain, target, true, asyncHandle);
        }

        // Try route WITHOUT catchAll route
        // In migration period from V1 to V3, this make specific route take higher priority
        if (distributeTraffic(clientRequest, clientResponse, domain, target, false, asyncHandle)) {
            return true;
        }

        // Try route WITH catchAll route
        if (distributeTraffic(clientRequest, clientResponse, domain, target, true, asyncHandle)) {
            return true;
        }

        // default fallback to V1
        dispatchV1(clientRequest, clientResponse, target, true, asyncHandle);
        return true;
    }

    private boolean distributeTraffic(MuRequest clientRequest, MuResponse clientResponse, String domain, String target, boolean useCatchall, AsyncHandle asyncHandle) {
        final boolean canHandleByV3 = webSocketFarmV3Holder.canHandle(ANY_DOMAIN, target, useCatchall);
        final boolean canHandleByV1 = webSocketFarm.canHandle(target, useCatchall);

        if (canHandleByV3 && canHandleByV1) {
            // not loading all the traffic to V3 during the migration period
            if (random.nextBoolean()) {
                return dispatchV3(clientRequest, clientResponse, ANY_DOMAIN, target, useCatchall, asyncHandle);
            } else {
                return dispatchV1(clientRequest, clientResponse, target, useCatchall, asyncHandle);
            }

        } else if (canHandleByV3) {
            return dispatchV3(clientRequest, clientResponse, ANY_DOMAIN, target, useCatchall, asyncHandle);
        } else if (canHandleByV1) {
            return dispatchV1(clientRequest, clientResponse, target, useCatchall, asyncHandle);
        }
        return false;
    }

    private boolean dispatchV1(MuRequest clientRequest, MuResponse clientResponse, String target, boolean useCatchAll, AsyncHandle asyncHandle) {
        webSocketFarm.acquireSocket(target, useCatchAll, clientRequest, clientResponse,
                (crankedSocket, waitTimeInMillis) -> sendRequestOverWebSocket(clientRequest, clientResponse, asyncHandle, crankedSocket, waitTimeInMillis),
                (statusCode, waitTimeInMillis, header, body) -> {
                    sendSimpleResponse(clientResponse, asyncHandle, statusCode, header, body);
                    if (!proxyListeners.isEmpty()) {
                        ProxyInfo proxyInfo = new ErrorProxyInfo(target, clientRequest, clientResponse, waitTimeInMillis);
                        for (ProxyListener proxyListener : proxyListeners) {
                            proxyListener.onFailureToAcquireProxySocket(proxyInfo);
                        }
                    }
                });
        return true;
    }

    private boolean dispatchV3(MuRequest clientRequest, MuResponse clientResponse, String domain, String target, boolean useCatchAll, AsyncHandle asyncHandle) {
        final WebSocketFarmV3 webSocketFarmV3 = webSocketFarmV3Holder.getWebSocketFarmV3(domain);
        if (webSocketFarmV3 == null) {
            sendSimpleResponse(clientResponse, asyncHandle, 503,
                    "503 Service Unavailable",
                    "V3 connector not available for domain");
            return true;
        }
        webSocketFarmV3.getWebSocket(target, useCatchAll)
                .whenComplete((routerSocketV3, throwable) -> {
                    if (routerSocketV3 == null || throwable != null) {
                        sendSimpleResponse(clientResponse, asyncHandle, 503,
                                "503 Service Unavailable",
                                "V3 connector not available");
                        if (!proxyListeners.isEmpty()) {
                            ProxyInfo proxyInfo = new ErrorProxyInfo(target, clientRequest, clientResponse, 0);
                            for (ProxyListener proxyListener : proxyListeners) {
                                proxyListener.onFailureToAcquireProxySocket(proxyInfo);
                            }
                        }
                        return;
                    }
                    routerSocketV3.sendRequestOverWebSocketV3(clientRequest, clientResponse);
                });
        return true;
    }

    private void sendRequestOverWebSocket(MuRequest clientRequest, MuResponse clientResponse,
                                          AsyncHandle asyncHandle, RouterSocket crankedSocket,
                                          long waitTimeInMillis) {
        crankedSocket.setAsyncHandle(asyncHandle, clientRequest, clientResponse, waitTimeInMillis);
        try {
            CrankerProtocolRequestBuilder protocolRequest = CrankerProtocolRequestBuilder.newBuilder();
            protocolRequest.withRequestLine(createRequestLine(clientRequest));

            HeadersBuilder headers = new HeadersBuilder();
            setTargetRequestHeaders(clientRequest, headers, discardClientForwardedHeaders, sendLegacyForwardedHeaders, viaValue, doNotProxy);

            try {
                if (!proxyListeners.isEmpty()) {
                    for (ProxyListener proxyListener : proxyListeners) {
                        proxyListener.onBeforeProxyToTarget(crankedSocket, headers.muHeaders());
                    }
                }
            } catch (WebApplicationException e) {
                handleWebApplicationException(e, clientResponse, asyncHandle);
                crankedSocket.socketSessionClose();
                return;
            }

            protocolRequest.withRequestHeaders(headers);

            if (clientRequest.headers().hasBody()) {
                // Stream the body
                crankedSocket.sendText(protocolRequest.withRequestBodyPending().build());

                if (!proxyListeners.isEmpty()) {
                    for (ProxyListener proxyListener : proxyListeners) {
                        proxyListener.onAfterProxyToTargetHeadersSent(crankedSocket, headers.muHeaders());
                    }
                }

                asyncHandle.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(ByteBuffer buffer, DoneCallback callback) {
                        try {
                            final int position = buffer.position();
                            final DoneCallback doneWrapper = error -> {
                                if (error == null && !proxyListeners.isEmpty()) {
                                    for (ProxyListener proxyListener : proxyListeners) {
                                        proxyListener.onRequestBodyChunkSentToTarget(crankedSocket, buffer.position(position));
                                    }
                                }
                                callback.onComplete(error);
                            };
                            crankedSocket.sendData(buffer, doneWrapper);
                        } catch (Exception e) {
                            onError(e);
                        }
                    }

                    @Override
                    public void onComplete() {
                        try {
                            String bodyEndedRequestMsg = CrankerProtocolRequestBuilder.newBuilder().withRequestBodyEnded().build();
                            crankedSocket.sendText(bodyEndedRequestMsg);

                            if (!proxyListeners.isEmpty()) {
                                for (ProxyListener proxyListener : proxyListeners) {
                                    proxyListener.onRequestBodySentToTarget(crankedSocket);
                                }
                            }
                        } catch (Exception e) {
                            onError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        asyncHandle.complete(t);
                        try {
                            crankedSocket.onError(t);
                        } catch (Exception ignored) {
                        }
                    }
                });

            } else {
                // No request body
                crankedSocket.sendText(protocolRequest.withRequestHasNoBody().build());

                if (!proxyListeners.isEmpty()) {
                    for (ProxyListener proxyListener : proxyListeners) {
                        proxyListener.onAfterProxyToTargetHeadersSent(crankedSocket, headers.muHeaders());
                        proxyListener.onRequestBodySentToTarget(crankedSocket);
                    }
                }
            }

        } catch (WebApplicationException e) {
            handleWebApplicationException(e, clientResponse, asyncHandle);
            crankedSocket.socketSessionClose();
            return;
        } catch (Throwable e) {
            handleException(clientRequest, clientResponse, asyncHandle, e);
            try {
                crankedSocket.socketSessionClose();
            } catch (Throwable ei) {
                log.error("Fail to close crankedSocket, routerName=" + crankedSocket.route + ", routerSocketID=" + crankedSocket.routerSocketID
                        + " \n" + ei.getMessage());
            } finally {
                asyncHandle.complete();
            }
        }
    }

    static void handleException(MuRequest clientRequest, MuResponse clientResponse, AsyncHandle asyncHandle, Throwable e) {
        final Object muId = clientRequest.attribute(MU_ID);
        log.error(String.format("Error setting up. ErrorID=%s, request.uri=%s, request.startTime=%s, response.hasStartedSendingData=%s, response.status=%s, response.state=%s",
                muId, clientRequest.uri(), clientRequest.startTime(), clientResponse.hasStartedSendingData(), clientResponse.status(), clientResponse.responseState()), e);
        try {
            if (!clientResponse.hasStartedSendingData()) {
                clientResponse.status(500);
                asyncHandle.write(Mutils.toByteBuffer("Server ErrorID=" + muId));
            }
        } catch (Throwable e1) {
            log.info("Fail to send error msg.", e1);
        }
    }

    static void handleWebApplicationException(WebApplicationException e, MuResponse response, AsyncHandle asyncHandle) {
        Response.StatusType status = e.getResponse().getStatusInfo();
        response.status(status.getStatusCode());
        String entity = Mutils.htmlEncode(e.getMessage());
        String header = status.getStatusCode() + " " + status.getReasonPhrase();
        sendSimpleResponse(response, asyncHandle, status.getStatusCode(), header, entity);
    }

    static void sendSimpleResponse(MuResponse response, AsyncHandle asyncHandle, int code, String header, String htmlBody) {
        if (response.hasStartedSendingData()) {
            asyncHandle.complete(new RuntimeException("Was going to send " + code + " but response was already started or closed"));
        } else {
            response.status(code);
            response.headers().remove("content-length");
            response.contentType(ContentTypes.TEXT_HTML_UTF8);
            String html = "<html><head><title>" + Mutils.htmlEncode(header) + "</title><body>"
                    + "<h1>" + Mutils.htmlEncode(header) + "</h1><p>"
                    + htmlBody + "</p></body></html>";
            asyncHandle.write(Mutils.toByteBuffer(html), throwable -> {
                        if (throwable == null) {
                            asyncHandle.complete();
                        } else {
                            asyncHandle.complete(throwable);
                        }
                    }
            );
        }
    }

    static String createRequestLine(MuRequest request) {
        // Request-Line   = Method SP Request-URI SP HTTP-Version CRLF
        String uri = request.uri().getRawPath();
        String qs = request.uri().getRawQuery();
        qs = (qs == null) ? "" : "?" + qs;
        return request.method().name() + " " + uri + qs + " HTTP/1.1";
    }


    static boolean setTargetRequestHeaders(MuRequest clientRequest, HeadersBuilder headersBuilder, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders, String viaValue, Set<String> excludedHeaders) {
        Headers reqHeaders = clientRequest.headers();
        List<String> customHopByHop = getCustomHopByHopHeaders(reqHeaders.get(HeaderNames.CONNECTION));

        boolean hasContentLengthOrTransferEncoding = false;
        for (Map.Entry<String, String> clientHeader : reqHeaders) {
            String key = clientHeader.getKey();
            String lowKey = key.toLowerCase();
            if (excludedHeaders.contains(lowKey) || customHopByHop.contains(lowKey)) {
                continue;
            }
            hasContentLengthOrTransferEncoding |= lowKey.equals("content-length") || lowKey.equals("transfer-encoding");
            headersBuilder.appendHeader(key, clientHeader.getValue());
        }

        String newViaValue = getNewViaValue(clientRequest.connection().protocol() + " " + viaValue, clientRequest.headers().getAll(HeaderNames.VIA));
        headersBuilder.appendHeader("via", newViaValue);

        setForwardedHeaders(clientRequest, headersBuilder, discardClientForwardedHeaders, sendLegacyForwardedHeaders);

        return hasContentLengthOrTransferEncoding;
    }

    private static String getNewViaValue(String viaValue, List<String> previousViasList) {
        String previousVias = String.join(", ", previousViasList);
        if (!previousVias.isEmpty()) previousVias += ", ";
        return previousVias + viaValue;
    }

    /**
     * Sets Forwarded and optionally X-Forwarded-* headers to the target request, based on the client request
     *
     * @param clientRequest                 the received client request
     * @param headersBuilder                the target request to write the headers to
     * @param discardClientForwardedHeaders if <code>true</code> then existing Forwarded headers on the client request will be discarded (normally false, unless you do not trust the upstream system)
     * @param sendLegacyForwardedHeaders    if <code>true</code> then X-Forwarded-Proto/Host/For headers will also be added
     */
    public static void setForwardedHeaders(MuRequest clientRequest, HeadersBuilder headersBuilder, boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders) {
        Mutils.notNull("clientRequest", clientRequest);
        Mutils.notNull("targetRequest", headersBuilder);
        List<ForwardedHeader> forwardHeaders;
        if (discardClientForwardedHeaders) {
            forwardHeaders = Collections.emptyList();
        } else {
            forwardHeaders = clientRequest.headers().forwarded();
            for (ForwardedHeader existing : forwardHeaders) {
                headersBuilder.appendHeader("forwarded", existing.toString());
            }
        }

        ForwardedHeader newForwarded = createForwardedHeader(clientRequest);
        headersBuilder.appendHeader("forwarded", newForwarded.toString());

        if (sendLegacyForwardedHeaders) {
            ForwardedHeader first = forwardHeaders.isEmpty() ? newForwarded : forwardHeaders.get(0);
            setXForwardedHeaders(headersBuilder, first);
        }
    }

    /**
     * Sets X-Forwarded-Proto, X-Forwarded-Host and X-Forwarded-For on the request given the forwarded header.
     *
     * @param headersBuilder  The request to add the headers to
     * @param forwardedHeader The forwarded header that has the original client information on it.
     */
    private static void setXForwardedHeaders(HeadersBuilder headersBuilder, ForwardedHeader forwardedHeader) {
        String proto = forwardedHeader.proto();
        if (proto != null) {
            headersBuilder.appendHeader(HeaderNames.X_FORWARDED_PROTO.toString(), proto);
        }
        String host = forwardedHeader.host();
        if (host != null) {
            headersBuilder.appendHeader(HeaderNames.X_FORWARDED_HOST.toString(), host);
        }
        String forValue = forwardedHeader.forValue();
        if (forValue != null) {
            headersBuilder.appendHeader(HeaderNames.X_FORWARDED_FOR.toString(), forValue);
        }
    }

    /**
     * Creates a Forwarded header for the based on the current request which can be used when
     * proxying the request to a target.
     *
     * @param clientRequest The request from the client
     * @return A ForwardedHeader that can be added to a new request
     */
    private static ForwardedHeader createForwardedHeader(MuRequest clientRequest) {
        String forwardedFor = clientRequest.remoteAddress();
        String proto = clientRequest.serverURI().getScheme();
        String host = clientRequest.headers().get(HeaderNames.HOST);
        return new ForwardedHeader(ipAddress, forwardedFor, host, proto, null);
    }

    static List<String> getCustomHopByHopHeaders(String connectionHeaderValue) {
        if (connectionHeaderValue == null) {
            return Collections.emptyList();
        }
        List<String> customHopByHop = new ArrayList<>();
        String[] split = connectionHeaderValue.split("\\s*,\\s*");
        for (String s : split) {
            customHopByHop.add(s.toLowerCase());
        }
        return customHopByHop;
    }


    private static class ErrorProxyInfo implements ProxyInfo {
        private final boolean isCatchAll;
        private final String route;
        private final MuRequest clientRequest;
        private final MuResponse clientResponse;
        private final long socketWaitInMillis;
        private final long durationMillis;

        ErrorProxyInfo(String target, MuRequest clientRequest, MuResponse clientResponse, long socketWaitInMillis) {
            this.clientRequest = clientRequest;
            this.clientResponse = clientResponse;
            this.socketWaitInMillis = socketWaitInMillis;
            this.durationMillis = System.currentTimeMillis() - clientRequest.startTime();

            String[] split = target.split("/");
            if (split.length >= 2) {
                route = split[1];
                isCatchAll = false;
            } else {
                route = "*";
                isCatchAll = true;
            }
        }

        @Override
        public boolean isCatchAll() {
            return isCatchAll;
        }

        @Override
        public String connectorInstanceID() {
            return null;
        }

        @Override
        public InetSocketAddress serviceAddress() {
            return null;
        }

        @Override
        public String route() {
            return route;
        }

        @Override
        public MuRequest request() {
            return clientRequest;
        }

        @Override
        public MuResponse response() {
            return clientResponse;
        }

        @Override
        public long durationMillis() {
            return durationMillis;
        }

        @Override
        public long bytesReceived() {
            return 0;
        }

        @Override
        public long bytesSent() {
            return 0;
        }

        @Override
        public long responseBodyFrames() {
            return 0;
        }

        @Override
        public Throwable errorIfAny() {
            return null;
        }

        @Override
        public long socketWaitInMillis() {
            return socketWaitInMillis;
        }


    }
}
