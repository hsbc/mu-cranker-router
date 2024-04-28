package com.hsbc.cranker.mucranker;

import io.muserver.MuRequest;
import io.muserver.MuResponse;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Information about a proxied request and response. Use {@link CrankerRouterBuilder#withProxyListeners(List)} to subscribe
 * to events that exposes this data.
 */
public interface ProxyInfo {

    /**
     * isCatchAll
     * @return Returns true if the connector is a catch-all connector (i.e. the router of the connector is '*').
     */
    boolean isCatchAll();

    /**
     * A unique ID for the service connector.
     * @return A unique ID for the service connector.
     */
    String connectorInstanceID();

    /**
     * The address of the service connector that this request is being proxied to.
     * @return The address of the service connector that this request is being proxied to.
     */
    InetSocketAddress serviceAddress();

    /**
     * The cranker route (i.e. the first part of a path) for the request, or '*' if a catch-all connector is used.
     * @return The cranker route (i.e. the first part of a path) for the request, or '*' if a catch-all connector is used.
     */
    String route();

    /**
     * The client's request to the router.
     * @return The client's request to the router.
     */
    MuRequest request();

    /**
     * The router's response to the client.
     * @return The router's response to the client.
     */
    MuResponse response();

    /**
     * The time in millis from when the router received the request until it sent the last response byte.
     * @return The time in millis from when the router received the request until it sent the last response byte.
     */
    long durationMillis();

    /**
     * The number of bytes uploaded by the client in the request
     * @return The number of bytes uploaded by the client in the request
     */
    long bytesReceived();

    /**
     * The number of bytes sent to the client on the response
     * @return The number of bytes sent to the client on the response
     */
    long bytesSent();

    /**
     * Response bodies are sent from a connector to the router as a number of binary websocket frames. This is a count of
     * the number of frames received on this socket.
     * <p>This can be used to understand how target services are streaming responses to clients, especially if used
     * in conjunction with {@link #bytesSent()} as it can give an idea of average response chunk size.</p>
     * @return The number of chunks of binary data that the connector sent for this response
     */
    long responseBodyFrames();

    /**
     * If the response was not proxied successfully, then this has the exception.
     * @return null if no problems, otherwise an exception
     */
    Throwable errorIfAny();

    /**
     * Wait time in millis seconds to get a websocket (which is used for proxy requests)
     * @return wait time in millis seconds to get a websocket (which is used for proxy requests)
     */
    long socketWaitInMillis();
}
