package com.hsbc.cranker.mucranker;

import io.muserver.Headers;

import javax.ws.rs.WebApplicationException;
import java.nio.ByteBuffer;
import java.util.List;

/**
 * Hooks to intercept, change and observe the proxying of requests from a client to a target and back.
 * <p>Register listeners when constructor the router with the {@link CrankerRouterBuilder#withProxyListeners(List)}
 * method.</p>
 * <p><strong>Note:</strong> the default implementation of each method is a no-op operation, so you can just
 * override the events you are interested in.</p>
 */
public interface ProxyListener {

    /**
     * This is called before sending a request to the target service
     *
     * @param info                   Info about the request and response. Note that duration, bytes sent and bytes received
     *                               will contain values as the the current point in time.
     * @param requestHeadersToTarget The headers that will be sent to the client. Modify this object in order to change
     *                               the headers sent to the target server.
     * @throws WebApplicationException Throw a web application exception to send an error to the client rather than
     *                                 proxying the request.
     */
    default void onBeforeProxyToTarget(ProxyInfo info, Headers requestHeadersToTarget) throws WebApplicationException {};

    /**
     * <p>This is called before sending the response to the client.</p>
     * <p>The response info contains the response objects with the HTTP status and headers that will be sent
     * to the client. You are able to change these values at this point in the lifecycle.</p>
     *
     * @param info Info about the request and response. Note that duration, bytes sent and bytes received
     *             will contain values as the the current point in time.
     * @throws WebApplicationException Throw a web application exception to send an error to the client rather than
     *                                 proxying the response. This allows you reject responses from services that
     *                                 you deem to be invalid.
     */
    default void onBeforeRespondingToClient(ProxyInfo info) throws WebApplicationException {};

    /**
     * This is called after a response has been completed.
     * <p>Note that this is called even if the response was not completed successfully (for example if a browser
     * was closed before a response was complete). If the proxying was not successful, then
     * {@link ProxyInfo#errorIfAny()} will not be null.</p>
     *
     * @param proxyInfo Information about the response.
     */
    default void onComplete(ProxyInfo proxyInfo) {};

    /**
     * This is called if a free socket could not be found for the target in which case:
     *
     * <ul>
     *   <li>{@link ProxyInfo#bytesReceived()} will be 0.</li>
     *   <li>{@link ProxyInfo#bytesSent()} will be 0.</li>
     *   <li>{@link ProxyInfo#connectorInstanceID()} will be null.</li>
     *   <li>{@link ProxyInfo#serviceAddress()} will be null.</li>
     *   <li>{@link ProxyInfo#errorIfAny()} will be null.</li>
     * </ul>
     *
     * @param proxyInfo Information about the request.
     */
    default void onFailureToAcquireProxySocket(ProxyInfo proxyInfo) {};


    /**
     * This is called if async method which used to send request headers has already called.
     * Because the operation of send header is async so this callback does not mean headers has sent complete
     *
     * @param info                   Info about the request and response.
     * @param headers                The headers that has sent to the target
     * @throws WebApplicationException Throw a web application exception to send an error to the client rather than
     *                                 proxying the response. At this stage, the header maybe already sent to target but
     *                                 because this exception has been thrown so will stop send or receive data from target
     */
    default void onAfterProxyToTargetHeadersSent(ProxyInfo info, Headers headers) throws WebApplicationException {};


    /**
     * This is called if response headers has already received from target
     *
     * @param info                   Info about the request and response.
     * @param status                 Response status
     * @param headers                The headers that has sent to the target
     * @throws WebApplicationException Throw a web application exception to send an error to the client rather than
     *                                 keep getting data from target.
     */
    default void onAfterTargetToProxyHeadersReceived(ProxyInfo info, int status, Headers headers) throws WebApplicationException {};


    /**
     * Called when a chunk of request body data is sent to the target
     * This will be called many times if the body has been fragmented
     *
     * @param info                     Info about the request and response.
     * @param chunk                    Request body data which already been sent to target successfully.
     *
     */
    default void onRequestBodyChunkSentToTarget(ProxyInfo info, ByteBuffer chunk) {};

    /**
     * Called when the full request body has been received to the target
     *
     * @param info                     Info about the request and response.
     */
    default void onRequestBodySentToTarget(ProxyInfo info) {};


    /**
     * Called when a chunk of response body data is received from the target
     * This will be called many times if the body has been fragmented
     *
     * @param info                     Info about the request and response.
     * @param chunk                    Response body data received from the target.
     *
     */
    default void onResponseBodyChunkReceivedFromTarget(ProxyInfo info, ByteBuffer chunk) {};


    /**
     * Called when the full response body has been received from the target
     *
     * @param info                     Info about the request and response.
     *                                 proxying the request.
     */
    default void onResponseBodyChunkReceived(ProxyInfo info) {};

}
