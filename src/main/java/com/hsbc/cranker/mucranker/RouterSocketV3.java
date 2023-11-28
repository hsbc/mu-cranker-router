package com.hsbc.cranker.mucranker;

import io.muserver.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.hsbc.cranker.mucranker.CrankerMuHandler.*;

class RouterSocketV3 extends BaseWebSocket {

    static final byte MESSAGE_TYPE_DATA = 0;
    static final byte MESSAGE_TYPE_HEADER = 1;
    static final byte MESSAGE_TYPE_RST_STREAM = 3;
    static final byte MESSAGE_TYPE_WINDOW_UPDATE = 8;

    static final int ERROR_INTERNAL = 1;

    private static final Logger log = LoggerFactory.getLogger(RouterSocketV3.class);
    private static final List<String> RESPONSE_HEADERS_TO_NOT_SEND_BACK = Collections.singletonList("server");

    final String route;
    final String componentName;
    final String routerSocketID = UUID.randomUUID().toString();
    private final WebSocketFarmV3 webSocketFarmV3;
    private final String connectorInstanceID;
    private final List<ProxyListener> proxyListeners;
    private final boolean discardClientForwardedHeaders;
    private final boolean sendLegacyForwardedHeaders;
    private final String viaValue;
    private final Set<String> doNotProxy;
    private Runnable onReadyForAction;
    private InetSocketAddress remoteAddress;

    private boolean isRemoved;

    private final Map<Integer, RequestContext> contextMap = new ConcurrentHashMap<>();
    private final AtomicInteger idMaker = new AtomicInteger(0);

    RouterSocketV3(String route, String componentName, WebSocketFarmV3 webSocketFarmV3,
                   String remotePort, List<ProxyListener> proxyListeners,
                   boolean discardClientForwardedHeaders, boolean sendLegacyForwardedHeaders,
                   String viaValue, Set<String> doNotProxy) {
        this.webSocketFarmV3 = webSocketFarmV3;
        this.route = route;
        this.componentName = componentName;
        this.connectorInstanceID = remotePort;
        this.proxyListeners = proxyListeners;
        this.discardClientForwardedHeaders = discardClientForwardedHeaders;
        this.sendLegacyForwardedHeaders = sendLegacyForwardedHeaders;
        this.viaValue = viaValue;
        this.doNotProxy = doNotProxy;
        this.isRemoved = false;
    }

    public WebsocketSessionState state() {
        return super.state();
    }

    public Map<Integer, RequestContext> getContextMap() {
        return contextMap;
    }

    public void sendRequestOverWebSocketV3(MuRequest clientRequest, MuResponse clientResponse) {

        final Integer requestId = idMaker.incrementAndGet();
        final AsyncHandle asyncHandle = clientRequest.handleAsync();

        final RequestContext context = new RequestContext(requestId, clientRequest, clientResponse, asyncHandle);
        contextMap.put(requestId, context);

        asyncHandle.addResponseCompleteHandler(info -> {
            if (!info.completedSuccessfully()) {
                log.info("Client request did not complete successfully " + clientRequest);
                if (context.error == null) {
                    context.error = new IllegalStateException("Client request did not complete successfully.");
                }
                raiseCompletionEvent(context);
                if (!state().endState()) {
                    resetStream(context, ERROR_INTERNAL, "Client early closed", DoneCallback.NoOp);
                }
            }
        });

        try {

            HeadersBuilder headers = new HeadersBuilder();
            setTargetRequestHeaders(clientRequest, headers, discardClientForwardedHeaders, sendLegacyForwardedHeaders, viaValue, doNotProxy);

            try {
                if (!proxyListeners.isEmpty()) {
                    for (ProxyListener proxyListener : proxyListeners) {
                        proxyListener.onBeforeProxyToTarget(context, headers.muHeaders());
                    }
                }
            } catch (WebApplicationException e) {
                handleWebApplicationException(e, clientResponse, asyncHandle);
                resetStream(context, ERROR_INTERNAL, "Proxy listener error", DoneCallback.NoOp);
                return;
            }

            final String headerText = createRequestLine(clientRequest) + "\n" + headers + "\n";

            if (clientRequest.headers().hasBody()) {

                for (ByteBuffer headerMessage : headerMessages(requestId, true, false, headerText)) {
                    context.sendingBytes(headerMessage.remaining() - 6);
                    sendData(headerMessage, DoneCallback.NoOp);
                    context.fromClientBytes.addAndGet(headerMessage.remaining() - 6);
                }

                if (!proxyListeners.isEmpty()) {
                    for (ProxyListener proxyListener : proxyListeners) {
                        proxyListener.onAfterProxyToTargetHeadersSent(context, headers.muHeaders());
                    }
                }

                // Stream the body
                asyncHandle.setReadListener(new RequestBodyListener() {
                    @Override
                    public void onDataReceived(ByteBuffer buffer, DoneCallback callback) {

                        final int remaining = buffer.remaining();
                        final int position = buffer.position();

                        DoneCallback wrapper = error -> {

                            context.fromClientBytes.addAndGet(remaining);

                            if (error != null) {
                                onError(error);
                                callback.onComplete(error);
                                return;
                            }

                            if (!proxyListeners.isEmpty()) {
                                for (ProxyListener proxyListener : proxyListeners) {
                                    // when creating the dataMessage, the buffer is read and position changed
                                    // calling buffer.rewind() to reset the position
                                    proxyListener.onRequestBodyChunkSentToTarget(context, buffer.position(position));
                                }
                            }

                            context.flowControl(() -> {
                                try {
                                    callback.onComplete(null);
                                } catch (Exception e) {
                                    onError(e);
                                }
                            });
                        };

                        try {
                            final ByteBuffer data = dataMessages(requestId, false, buffer);
                            context.sendingBytes(data.remaining() - 6);
                            sendData(data, wrapper);
                        } catch (Exception e) {
                            onError(e);
                        }
                    }

                    @Override
                    public void onComplete() {
                        try {
                            final ByteBuffer endMessage = dataMessages(requestId, true, null);
                            sendData(endMessage, DoneCallback.NoOp);

                            if (!proxyListeners.isEmpty()) {
                                for (ProxyListener proxyListener : proxyListeners) {
                                    proxyListener.onRequestBodySentToTarget(context);
                                }
                            }
                        } catch (Exception e) {
                            onError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        try {
                            notifyClientRequestError(context, t);
                            resetStream(context, ERROR_INTERNAL, "Client request body read error", DoneCallback.NoOp);
                        } catch (Exception ignored) {
                        }
                    }
                });

            } else {
                // No request body
                for (ByteBuffer headerMessage : headerMessages(requestId, true, true, headerText)) {
                    context.sendingBytes(headerMessage.remaining() - 6);
                    sendData(headerMessage, DoneCallback.NoOp);
                    context.fromClientBytes.addAndGet(headerMessage.remaining() - 6);
                }

                if (!proxyListeners.isEmpty()) {
                    for (ProxyListener proxyListener : proxyListeners) {
                        proxyListener.onAfterProxyToTargetHeadersSent(context, headers.muHeaders());
                        proxyListener.onRequestBodySentToTarget(context);
                    }
                }
            }

        } catch (WebApplicationException e) {
            handleWebApplicationException(e, clientResponse, asyncHandle);
            resetStream(context, 1001, "Going away", DoneCallback.NoOp);
        } catch (Throwable e) {
            handleException(clientRequest, clientResponse, asyncHandle, e);
            try {
                resetStream(context, 1001, "Going away", DoneCallback.NoOp);
            } catch (Throwable ei) {
                log.error("Fail to close crankedSocket, routerName=" + context.route() + ", routerSocketID=" + routerSocketID
                    + " \n" + ei.getMessage());
            } finally {
                asyncHandle.complete();
            }
        }

    }

    private void sendData(ByteBuffer byteBuffer, DoneCallback doneCallback) {
        session().sendBinary(byteBuffer, doneCallback);
    }

    void socketSessionClose() {
        if (contextMap.isEmpty()) {
            try {
                MuWebSocketSession session = session();
                if (session != null) {
                    session.close(1001, "Going away");
                }
            } catch (Exception ignored) {
            }
        }

        // otherwise wait for connector finish the task and close the websocket connection
    }

    void resetStream(RequestContext context, Integer errorCode, String message, DoneCallback doneCallback) {
        if (context != null && !context.state.isCompleted() && !context.isRstStreamSent) {
            final ByteBuffer buffer = rstMessage(context.requestId, errorCode, message);
            sendData(buffer, doneCallback);
            context.isRstStreamSent = true;
        }

        if (context != null) {
            contextMap.remove(context.requestId);
        }
    }

    @Override
    public void onConnect(MuWebSocketSession session) throws Exception {
        super.onConnect(session);
        this.remoteAddress = session.remoteAddress();
        onReadyForAction.run();
    }

    @Override
    public void onClientClosed(int statusCode, String reason) throws Exception {
        // status code: https://tools.ietf.org/html/rfc6455#section-7.4.1
        super.onClientClosed(statusCode, reason);
        if (!isRemoved) {
            webSocketFarmV3.removeWebSocket(this);
            isRemoved = true;
        }
        if (statusCode != 1000) {
            log.warn("websocket exceptional closed from client: statusCode={}, reason={}", statusCode, reason);
        }
        for (RequestContext context : contextMap.values()) {
            notifyClientRequestClose(context, statusCode);
        }
    }

    private void notifyClientRequestClose(RequestContext context, int statusCode) {
        try {
            if (!proxyListeners.isEmpty()) {
                for (ProxyListener proxyListener : proxyListeners) {
                    proxyListener.onResponseBodyChunkReceived(context);
                }
            }

            if (context.response != null && !context.response.hasStartedSendingData()) {
                if (statusCode == 1011) {
                    context.response.status(502);
                } else if (statusCode == 1008) {
                    context.response.status(400);
                }
            }
            if (context.asyncHandle != null) {
                try {
                    if (statusCode == 1000) {
                        context.asyncHandle.complete();
                    } else {
                        log.info("Closing client request early due to cranker wss connection close with status code {}", statusCode);
                        context.asyncHandle.complete(new RuntimeException("Upstream Server Error"));
                    }
                } catch (IllegalStateException e) {
                    log.info("Tried to complete a request, but it is probably already closed. " +
                        " routerName=" + route +
                        ", routerSocketID=" + routerSocketID, e);
                }
            }
        } finally {
            if (statusCode != 1000 && context.error == null) {
                context.error = new IllegalStateException("Upstream server close with code " + statusCode);
            }
            raiseCompletionEvent(context);
            contextMap.remove(context.requestId);
        }
    }

    private void raiseCompletionEvent(RequestContext context) {
        if (context != null && context.request != null && !proxyListeners.isEmpty()) {
            context.durationMillis = System.currentTimeMillis() - context.request.startTime();
            for (ProxyListener completionListener : proxyListeners) {
                try {
                    completionListener.onComplete(context);
                } catch (Exception e) {
                    log.warn("Error thrown by " + completionListener, e);
                }
            }
        }
    }

    @Override
    public void onError(Throwable cause) throws Exception {
        super.onError(cause);
        if (!isRemoved) {
            webSocketFarmV3.removeWebSocket(this);
            isRemoved = true;
        }
        for (RequestContext context : contextMap.values()) {
            notifyClientRequestError(context, cause);
        }
    }

    private void notifyClientRequestError(RequestContext context, Throwable cause) throws Exception {
        try {
            context.error = cause;
            if (cause instanceof TimeoutException) {
                if (context.response != null && !context.response.hasStartedSendingData()) {
                    String htmlBody = "The <code>" + Mutils.htmlEncode(route) + "</code> service did not respond in time.";
                    CrankerMuHandler.sendSimpleResponse(context.response, context.asyncHandle, 504, "504 Gateway Timeout", htmlBody);
                } else if (context.asyncHandle != null) {
                    log.info("Closing client request early due to timeout");
                    context.asyncHandle.complete(cause);
                }
            } else {
                if (context.response != null && !context.response.hasStartedSendingData()) {
                    String htmlBody = "The <code>" + Mutils.htmlEncode(route) + "</code> service error.";
                    CrankerMuHandler.sendSimpleResponse(context.response, context.asyncHandle, 502, "502 Bad Gateway", htmlBody);
                } else if (context.asyncHandle != null) {
                    log.info("Closing client request early due to cranker wss connection error", cause);
                    context.asyncHandle.complete(cause);
                }
            }
        } finally {
            raiseCompletionEvent(context);
            log.warn("stream error: requestId={}, target={}, error={}", context.requestId, context.request.uri(), cause.getMessage());
            contextMap.remove(context.requestId);
        }
    }

    @Override
    public void onText(String message, boolean isLast, DoneCallback doneCallback) {
        // V3 protocol not using the onText anymore...
    }

    private void handleHeaderMessage(RequestContext context, String content) {
        CrankerProtocolResponse protocolResponse = new CrankerProtocolResponse(content);
        context.response.status(protocolResponse.getStatus());
        putHeadersTo(context.response, protocolResponse);

        try {
            if (!proxyListeners.isEmpty()) {
                for (ProxyListener proxyListener : proxyListeners) {
                    proxyListener.onBeforeRespondingToClient(context);
                    proxyListener.onAfterTargetToProxyHeadersReceived(context, protocolResponse.getStatus(), context.response.headers());
                }
            }
        } catch (WebApplicationException e) {
            handleWebApplicationException(e, context.response, context.asyncHandle);
        }

        context.toClientBytes.getAndAdd(content.length()); // string length should be number of bytes as this is used for headers so is ASCII
    }


    @Override
    public void onBinary(ByteBuffer byteBuffer, boolean isLast, DoneCallback doneAndPullData, Runnable releaseBuffer) throws Exception {

        final int messageType = byteBuffer.get();
        final int flags = byteBuffer.get();
        final Integer requestId = byteBuffer.getInt();

        final RequestContext context = contextMap.get(requestId);
        if (context == null) {
            // consuming the data and release it, instead of blocking the tcp connection
            releaseBuffer.run();
            doneAndPullData.onComplete(null);
            return;
        }

        switch (messageType) {
            case MESSAGE_TYPE_DATA: {
                final boolean isEnd = ((flags & 1) > 0);
                handleData(context, isLast, isEnd, byteBuffer, doneAndPullData, releaseBuffer);
                break;
            }
            case MESSAGE_TYPE_HEADER: {
                final boolean isStreamEnd = ((flags & 1) > 0);
                final boolean isHeaderEnd = ((flags & 4) > 0);
                final int byteLength = byteBuffer.remaining();
                final String content = StandardCharsets.UTF_8.decode(byteBuffer).toString();

                if (!isHeaderEnd) {
                    if (context.headerLineBuilder == null) context.headerLineBuilder = new StringBuilder();
                    context.headerLineBuilder.append(content);
                } else {
                    String fullContent = content;
                    if (context.headerLineBuilder != null) {
                        context.headerLineBuilder.append(content);
                        fullContent = context.headerLineBuilder.toString();
                    }
                    handleHeaderMessage(context, fullContent);
                }
                if (isStreamEnd) {
                    notifyClientRequestClose(context, 1000);
                }
                sendData(windowUpdateMessage(requestId, byteLength), DoneCallback.NoOp);
                releaseBuffer.run();
                doneAndPullData.onComplete(null);
                break;
            }
            case MESSAGE_TYPE_RST_STREAM: {
                try {
                    final int errorCode = getErrorCode(byteBuffer);
                    String message = getErrorMessage(byteBuffer);
                    notifyClientRequestError(context, new RuntimeException(
                        String.format("stream closed by connector, errorCode=%s, message=%s", errorCode, message)));
                } catch (Throwable throwable) {
                    log.warn("exception on handling rst_stream", throwable);
                } finally {
                    releaseBuffer.run();
                    doneAndPullData.onComplete(null);
                }
                break;
            }
            case MESSAGE_TYPE_WINDOW_UPDATE: {
                final int windowUpdate = byteBuffer.getInt();
                context.ackedBytes(windowUpdate);
                releaseBuffer.run();
                doneAndPullData.onComplete(null);
                break;
            }
            default: {
                log.info("not supported binary message byte {}", messageType);
                releaseBuffer.run();
                doneAndPullData.onComplete(null);
            }
        }
    }
    private static String getErrorMessage(ByteBuffer byteBuffer) {
        String message = "";
        if (byteBuffer.remaining() > 0) {
            byte[] bytes = new byte[byteBuffer.remaining()];
            byteBuffer.get(bytes);
            message = new String(bytes, StandardCharsets.UTF_8);
        }
        return message;
    }

    private static int getErrorCode(ByteBuffer byteBuffer) {
        return byteBuffer.remaining() >= 4 ? byteBuffer.getInt() : -1;
    }

    private void handleData(RequestContext context, boolean isLast, boolean isEnd, ByteBuffer byteBuffer, DoneCallback doneAndPullData, Runnable releaseBuffer) throws Exception {

        int len = byteBuffer.remaining();
        if (len == 0) {
            if (isEnd) notifyClientRequestClose(context, 1000);
            releaseBuffer.run();
            doneAndPullData.onComplete(null);
            return;
        }

        context.wssOnBinaryCallCount.incrementAndGet();

        WebsocketSessionState websocketState = state();
        if (websocketState.endState()) {
            if (isEnd) notifyClientRequestClose(context, 1000);
            releaseBuffer.run();
            doneAndPullData.onComplete(new IllegalStateException("Received binary message from connector but state=" + websocketState));
            return;
        }

        if (log.isDebugEnabled()) {
            log.debug("routerName=" + route + ", routerSocketID=" + routerSocketID +
                ", sending " + len + " bytes to client");
        }

        // pullMoreData, avoid blocking the websocket tunnel
        doneAndPullData.onComplete(null);

        context.asyncHandle.write(byteBuffer, errorIfAny -> {
            try {
                if (errorIfAny == null) {
                    if (isEnd) notifyClientRequestClose(context, 1000);
                    context.toClientBytes.addAndGet(len);
                    sendData(windowUpdateMessage(context.requestId, len), DoneCallback.NoOp);
                } else {
                    log.info("routerName=" + route + ", routerSocketID=" + routerSocketID +
                        ", could not write to client response (maybe the user closed their browser)" +
                        " so will cancel the request. Error message: " + errorIfAny.getMessage());

                    // reset the request context instead of closing everything
                    // the rst_stream will be sent to wss socket in asyncHandle.addResponseCompleteHandler() callback
                    context.error = errorIfAny;
                    context.asyncHandle.complete(errorIfAny);
                }
                if (!proxyListeners.isEmpty()) {
                    for (ProxyListener proxyListener : proxyListeners) {
                        proxyListener.onResponseBodyChunkReceivedFromTarget(context, byteBuffer);
                    }
                }
            } catch (Throwable throwable) {
                log.warn("something wrong after sending bytes to cranker", throwable);
            } finally {
                releaseBuffer.run();
            }
        });
    }

    public String connectorInstanceID() {
        return connectorInstanceID;
    }

    void setOnReadyForAction(Runnable onReadyForAction) {
        this.onReadyForAction = onReadyForAction;
    }

    public InetSocketAddress serviceAddress() {
        return remoteAddress;
    }

    public String getProtocol() {
        return "cranker_3.0";
    }

    private static void putHeadersTo(MuResponse response, CrankerProtocolResponse protocolResponse) {
        response.headers().remove("date"); // Remove cranker-router's date because we want to use the target server's date
        for (String line : protocolResponse.headers) {
            int pos = line.indexOf(':');
            if (pos > 0) {
                String header = line.substring(0, pos);
                String lowerHeader = header.toLowerCase();
                if (!HOP_BY_HOP.contains(lowerHeader) && !RESPONSE_HEADERS_TO_NOT_SEND_BACK.contains(lowerHeader)) {
                    String value = line.substring(pos + 1);
                    response.headers().add(lowerHeader, value);
                }
            }
        }

        List<String> customHopByHop = CrankerMuHandler.getCustomHopByHopHeaders(response.headers().get(HeaderNames.CONNECTION));
        for (String header : customHopByHop) {
            response.headers().remove(header);
        }

    }

    static ByteBuffer windowUpdateMessage(Integer requestId, Integer windowUpdate) {
        return ByteBuffer.allocate(10)
            .put(MESSAGE_TYPE_WINDOW_UPDATE) // 1 byte
            .put((byte) 0) // 1 byte, flags unused
            .putInt(requestId) // 4 byte
            .putInt(windowUpdate) // 4 byte
            .rewind();
    }

    static ByteBuffer rstMessage(Integer requestId, Integer errorCode, String message) {
        final byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(10 + bytes.length)
            .put(MESSAGE_TYPE_RST_STREAM) // 1 byte
            .put((byte) 0) // 1 byte, flags unused
            .putInt(requestId) // 4 byte
            .putInt(errorCode) // 4 byte
            .put(bytes)
            .rewind();
    }

    static ByteBuffer[] headerMessages(Integer requestId, boolean isHeaderEnd, boolean isStreamEnd, String fullHeaderLine) {
        final int chunkSize = 16000;
        if (fullHeaderLine.length() < chunkSize) {
            return new ByteBuffer[]{headerMessage(requestId, isHeaderEnd, isStreamEnd, fullHeaderLine)};
        }

        List<ByteBuffer> buffers = new ArrayList<>();
        for (int i = 0; i < fullHeaderLine.length(); i += chunkSize) {
            final int endIndex = Math.min(fullHeaderLine.length(), i + chunkSize);
            final boolean isLast = endIndex == fullHeaderLine.length();
            buffers.add(headerMessage(requestId, isLast, isStreamEnd, fullHeaderLine.substring(i, endIndex)));
        }
        return buffers.toArray(new ByteBuffer[0]);
    }

    static ByteBuffer headerMessage(Integer requestId, boolean isHeaderEnd, boolean isStreamEnd, String headerLine) {
        int flags = 0;
        if (isStreamEnd) flags = flags | 1; // first bit 00000001
        if (isHeaderEnd) flags = flags | 4; // third bit 00000100
        final byte[] bytes = headerLine.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(6 + bytes.length)
            .put(MESSAGE_TYPE_HEADER) // 1 byte
            .put((byte) flags) // 1 byte
            .putInt(requestId) // 4 byte
            .put(bytes)
            .rewind();
    }

    static ByteBuffer dataMessages(Integer requestId, boolean isEnd, ByteBuffer buffer) {
        // TODO split if too large
        final ByteBuffer message = ByteBuffer.allocate(6 + (buffer == null ? 0 : buffer.remaining()))
            .put(MESSAGE_TYPE_DATA) // 1 byte
            .put((byte) (isEnd ? 1 : 0)) // 1 byte
            .putInt(requestId); // 4 byte
        if (buffer != null) message.put(buffer);
        message.rewind();
        return message;
    }

    public enum StreamState {

        OPEN(false),
        HALF_CLOSE(false),
        CLOSED(true),
        ERROR(true);

        final private boolean isCompleted;

        StreamState(boolean isCompleted) {
            this.isCompleted = isCompleted;
        }

        public boolean isCompleted() {
            return isCompleted;
        }
    }

    public class RequestContext implements ProxyInfo {

        final private static int WATER_MARK_HIGH = 64 * 1024;
        final private static int WATER_MARK_LOW = 16 * 1024;

        // wss tunnel
        final private AtomicInteger wssReceivedAckBytes = new AtomicInteger(0);
        final private AtomicInteger isWssSending = new AtomicInteger(0);
        final private AtomicBoolean isWssWritable = new AtomicBoolean(true);
        final private AtomicBoolean isWssWriting = new AtomicBoolean(false);
        final private Queue<Runnable> wssWriteCallbacks = new ConcurrentLinkedQueue<>();
        final AtomicLong wssOnBinaryCallCount = new AtomicLong();

        final public Integer requestId;
        final public MuRequest request;
        final public MuResponse response;
        final public AsyncHandle asyncHandle;

        // client
        final AtomicLong fromClientBytes = new AtomicLong();
        final AtomicLong toClientBytes = new AtomicLong();

        long durationMillis = 0;
        volatile Throwable error = null;
        volatile boolean isRstStreamSent = false;
        StreamState state = StreamState.OPEN;
        StringBuilder headerLineBuilder;

        public RequestContext(Integer requestId, MuRequest request, MuResponse response, AsyncHandle asyncHandle) {
            this.requestId = requestId;
            this.request = request;
            this.response = response;
            this.asyncHandle = asyncHandle;
        }

        void sendingBytes(int sendingBytes) {
            this.isWssSending.addAndGet(sendingBytes);
            if (this.isWssSending.get() > WATER_MARK_HIGH) {
                isWssWritable.compareAndSet(true, false);
            }
        }

        void ackedBytes(int ack) {
            this.wssReceivedAckBytes.addAndGet(ack);
            this.isWssSending.addAndGet(-ack);
            if (isWssSending.get() < WATER_MARK_LOW) {
                if (isWssWritable.compareAndSet(false, true)) {
                    writeItMaybe();
                }
            }
        }

        void flowControl(Runnable runnable) {
            if (isWssWritable.get() && !isWssWriting.get()) {
                runnable.run();
            } else {
                wssWriteCallbacks.add(runnable);
                writeItMaybe();
            }
        }

        private void writeItMaybe() {
            if (isWssWritable.get() && !wssWriteCallbacks.isEmpty() && isWssWriting.compareAndSet(false, true)) {
                try {
                    Runnable current;
                    while (isWssWritable.get() && (current = wssWriteCallbacks.poll()) != null) {
                        current.run();
                    }
                } finally {
                    isWssWriting.set(false);
                    writeItMaybe();
                }
            }
        }

        @Override
        public boolean isCatchAll() {
            return "*".equals(route);
        }

        @Override
        public String connectorInstanceID() {
            return connectorInstanceID;
        }

        @Override
        public InetSocketAddress serviceAddress() {
            return remoteAddress;
        }

        @Override
        public String route() {
            return route;
        }

        @Override
        public MuRequest request() {
            return request;
        }

        @Override
        public MuResponse response() {
            return response;
        }

        @Override
        public long durationMillis() {
            return durationMillis;
        }

        @Override
        public long bytesReceived() {
            return fromClientBytes.get();
        }

        @Override
        public long bytesSent() {
            return toClientBytes.get();
        }

        @Override
        public long responseBodyFrames() {
            return wssOnBinaryCallCount.get();
        }

        @Override
        public Throwable errorIfAny() {
            return error;
        }

        @Override
        public long socketWaitInMillis() {
            return 0;
        }

        @Override
        public String toString() {
            return new StringJoiner(", ", RequestContext.class.getSimpleName() + "[", "]")
                .add("wssReceivedAckBytes=" + wssReceivedAckBytes)
                .add("isWssSending=" + isWssSending)
                .add("isWssWritable=" + isWssWritable)
                .add("wssWriteCallbacks=" + wssWriteCallbacks.size())
                .add("isWssWriting=" + isWssWriting)
                .add("requestId=" + requestId)
                .add("request=" + request)
                .add("response=" + response)
                .add("fromClientBytes=" + fromClientBytes)
                .add("toClientBytes=" + toClientBytes)
                .add("wssOnBinaryCallCount=" + wssOnBinaryCallCount)
                .add("durationMillis=" + durationMillis)
                .add("error=" + error)
                .add("state=" + state)
                .toString();
        }
    }
}
