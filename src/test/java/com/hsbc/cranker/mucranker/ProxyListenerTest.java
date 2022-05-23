package com.hsbc.cranker.mucranker;

import io.muserver.Headers;
import io.muserver.Method;
import io.muserver.MuResponse;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import scaffolding.ClientUtils;

import javax.ws.rs.WebApplicationException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static java.util.Collections.singletonList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static scaffolding.AssertUtils.assertEventually;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class ProxyListenerTest extends BaseEndToEndTest {


    @Test
    public void completedRequestsGetNotifiedWithoutAnyError() throws Exception {
        List<ProxyInfo> received = new CopyOnWriteArrayList<>();

        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write("hello"))
            .start();
        startRouterAndConnector(crankerRouter()
            .withProxyListeners(singletonList(new ProxyListener() {
                @Override
                public void onComplete(ProxyInfo proxyInfo) {
                    received.add(proxyInfo);
                }
            }))
        );

        try (Response response = call(request(router.uri().resolve("/?message=hello%20world")))) {
            assertThat(response.code(), is(200));
        }
        assertEventually(() -> received, hasSize(1));
        ProxyInfo info = received.get(0);
        assertThat(info.route(), is("*"));
        assertThat(info.request().uri().getQuery(), is("message=hello world"));
        assertThat(info.response().status(), is(200));
        assertThat(info.durationMillis(), greaterThan(-1L));
        assertThat(info.bytesReceived(), greaterThan(0L));
        assertThat(info.bytesSent(), greaterThan(0L));
        assertThat(info.errorIfAny(), is(nullValue()));
        assertThat(info.responseBodyFrames(), greaterThan(0L));
    }

    @Test
    public void onFailureToAcquireProxySocketDueToNoConnectorA404IsReportedImmediately() throws Exception {
        List<ProxyInfo> received = new CopyOnWriteArrayList<>();

        this.crankerRouter = crankerRouter()
            .withConnectorMaxWaitInMillis(50)
            .withProxyListeners(singletonList(new ProxyListener() {
                @Override
                public void onFailureToAcquireProxySocket(ProxyInfo proxyInfo) {
                    received.add(proxyInfo);
                }
            })).start();
        this.router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();


        try (Response response = call(request(router.uri().resolve("/?message=hello%20world")))) {
            assertThat(response.code(), is(404));
        }
        assertEventually(received::size, is(1));
        ProxyInfo info = received.get(0);
        assertThat(info.socketWaitInMillis(), is(0L));
        assertThat(info.route(), is("*"));
        assertThat(info.request().uri().getQuery(), is("message=hello world"));
        assertThat(info.response().status(), is(404));
    }

    @Test
    public void headersToTargetCanBeChangedWithOnBeforeProxyToTarget() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) ->
                response.write(
                    "Headers at target: " +
                        request.headers().get("to-remove") +
                        "; " +
                        request.headers().get("to-retain") +
                        "; " +
                        request.headers().get("added")))
            .start();
        startRouterAndConnector(crankerRouter()
            .withProxyListeners(singletonList(new ProxyListener() {
                @Override
                public void onBeforeProxyToTarget(ProxyInfo info, Headers requestHeadersToTarget) throws WebApplicationException {
                    requestHeadersToTarget.remove("To-Remove");
                    requestHeadersToTarget.set("Added", "added");
                }
            }))
        );

        final Request.Builder request = request(router.uri())
            .header("to-remove", "You shall not pass")
            .header("to-retain", "This header will be proxied");
        try (Response response = call(request)) {
            assertThat(response.body().string(), is("Headers at target: null; This header will be proxied; added"));
        }
    }

    @Test
    public void responseHeadersToTheClientCanBeChanged() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.headers().set("response-header-1", "response value 1");
                response.headers().set("response-header-2", "response value 2");
                response.headers().set("response-header-3", "response value 3");
                response.write("Headers at target: " + request.headers().get("to-remove") + "; " + request.headers().get("to-retain")
                    + "; " + request.headers().get("added"));
            })
            .start();
        startRouterAndConnector(crankerRouter()
            .withProxyListeners(singletonList(new ProxyListener() {
                @Override
                public void onBeforeRespondingToClient(ProxyInfo info) {
                    MuResponse clientResponse = info.response();
                    clientResponse.headers().remove("response-header-1");
                    clientResponse.headers().add("response-header-3", "duplicate response value 3");
                    clientResponse.headers().add("response-header-4", "response value 4");
                }
            }))
        );


        try (Response response = call(request(router.uri()))) {
            assertThat(response.headers("response-header-1"), is(empty()));
            assertThat(response.headers("response-header-2"), contains("response value 2"));
            assertThat(response.headers("response-header-3"), contains("response value 3", "duplicate response value 3"));
            assertThat(response.headers("response-header-4"), contains("response value 4"));
        }
    }

    @Test
    public void webAppExceptionsThrownInAResponseListenerResultInErrorWithMessageGoingToClient() throws Exception {

        this.targetServer = httpServer().start();
        startRouterAndConnector(crankerRouter()
            .withProxyListeners(singletonList(new ProxyListener() {
                @Override
                public void onBeforeRespondingToClient(ProxyInfo info) {
                    throw new WebApplicationException("There is a conflict", 409);
                }
            }))
        );

        try (Response response = call(request(router.uri()))) {
            assertThat(response.code(), is(409));
            assertThat(response.header("content-type"), is("text/html;charset=utf-8"));
            String bodyString = response.body().string();
            assertThat(bodyString, containsString("There is a conflict"));
            assertThat(bodyString, containsString("409 Conflict"));
        }
    }

    @Test
    public void webAppExceptionsThrownInARequestListenerResultInErrorWithMessageGoingToClient() throws Exception {
        AtomicBoolean targetHit = new AtomicBoolean(false);
        this.targetServer = httpServer()
            .addHandler((request, response) -> {
                targetHit.set(true);
                return true;
            })
            .start();
        startRouterAndConnector(crankerRouter()
            .withProxyListeners(singletonList(new ProxyListener() {
                @Override
                public void onBeforeProxyToTarget(ProxyInfo info, Headers requestHeadersToTarget) throws WebApplicationException {
                    throw new WebApplicationException("There is a conflict", 409);
                }
            }))
        );

        try (Response response = call(request(router.uri()))) {
            assertThat(response.code(), is(409));
            assertThat(response.header("content-type"), is("text/html;charset=utf-8"));
            String bodyString = response.body().string();
            assertThat(bodyString, containsString("There is a conflict"));
            assertThat(bodyString, containsString("409 Conflict"));
        }
        assertThat(targetHit.get(), is(false));
    }

    @Test
    public void webAppExceptionsThrownInOnAfterProxyToTargetHeadersSent() throws Exception {
        this.targetServer = httpServer()
            .addHandler((request, response) -> true)
            .start();
        startRouterAndConnector(crankerRouter()
            .withProxyListeners(singletonList(new ProxyListener() {

                @Override
                public void onAfterProxyToTargetHeadersSent(ProxyInfo info, Headers headers) throws WebApplicationException {
                    throw new WebApplicationException("There is a conflict", 409);
                }

            }))
        );

        try (Response response = call(request(router.uri()))) {
            assertThat(response.code(), is(409));
            assertThat(response.header("content-type"), is("text/html;charset=utf-8"));
            String bodyString = response.body().string();
            assertThat(bodyString, containsString("There is a conflict"));
            assertThat(bodyString, containsString("409 Conflict"));
        }
    }

    @Test
    public void webAppExceptionsThrownInARequestListenerResultInErrorWithMessageAndTargetHasCalled() throws Exception {
        AtomicBoolean targetHit = new AtomicBoolean(false);
        this.targetServer = httpServer()
            .addHandler((request, response) -> {
                targetHit.set(true);
                return true;
            })
            .start();
        startRouterAndConnector(crankerRouter()
            .withProxyListeners(singletonList(new ProxyListener() {
                @Override
                public void onAfterTargetToProxyHeadersReceived(ProxyInfo info, int status, Headers headers) throws WebApplicationException {
                    throw new WebApplicationException("There is a conflict", 409);
                }
            }))
        );

        try (Response response = call(request(router.uri()))) {
            String bodyString = response.body().string();
            assertThat(targetHit.get(), is(true)); // already call target
            assertThat(response.code(), is(409));
            assertThat(response.header("content-type"), is("text/html;charset=utf-8"));
            assertThat(bodyString, containsString("There is a conflict"));
            assertThat(bodyString, containsString("409 Conflict"));
        }
    }

    @Test
    public void canGetRequestHeaderAfterSentAndGetResponseHeaderAfterReceiver() throws Exception {
        final Headers[] targetReqHeaderToBeCheck = new Headers[1];
        final Headers[] targetResHeaderToBeCheck = new Headers[1];

        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                    response.headers().add("res-header1", "value1");
                    response.headers().add("res-header2", "value2");
                    response.write("hello");
                }
            )
            .start();
        startRouterAndConnector(crankerRouter()
            .withProxyListeners(singletonList(new ProxyListener() {
                @Override
                public void onAfterProxyToTargetHeadersSent(ProxyInfo info, Headers headers) {
                    targetReqHeaderToBeCheck[0] = headers;
                }

                @Override
                public void onAfterTargetToProxyHeadersReceived(ProxyInfo info, int status, Headers headers) throws WebApplicationException {
                    targetResHeaderToBeCheck[0] = headers;
                }
            }))
        );


        Request request = new Request.Builder()
            .url(router.uri().toURL())
            .addHeader("req-header1", "value1")
            .addHeader("req-header2", "value2")
            .build();

        try (Response response = ClientUtils.client.newCall(request).execute()) {
            response.body().string();
        }

        assertEquals("value1", targetReqHeaderToBeCheck[0].get("req-header1"));
        assertEquals("value2", targetReqHeaderToBeCheck[0].get("req-header2"));

        assertEquals("value1", targetResHeaderToBeCheck[0].get("res-header1"));
        assertEquals("value2", targetResHeaderToBeCheck[0].get("res-header2"));

    }

    @Test
    public void canGetRequestBodyAfterSentAndGetResponseBodyAfterReceiver() throws Exception {
        final ByteArrayOutputStream reqBody = new ByteArrayOutputStream();
        final ByteArrayOutputStream resBody = new ByteArrayOutputStream();

        final boolean[] complete = {false, false};

        this.targetServer = httpServer()
            .addHandler(Method.POST, "/", (request, response, pathParams) -> {
                    response.headers().add("res-header1", "value1");
                    response.headers().add("res-header2", "value2");
                    response.write("hello");
                }
            )
            .start();
        startRouterAndConnector(crankerRouter()
            .withProxyListeners(singletonList(new ProxyListener() {
                @Override
                public void onRequestBodyChunkSentToTarget(ProxyInfo info, ByteBuffer chunk) {

                    try {
                        final ByteBuffer readOnlyBuffer = chunk.asReadOnlyBuffer();
                        byte[] arr = new byte[readOnlyBuffer.remaining()];
                        readOnlyBuffer.get(arr);
                        reqBody.write(arr);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public void onRequestBodySentToTarget(ProxyInfo info) {
                    complete[0] = true;
                }

                @Override
                public void onResponseBodyChunkReceivedFromTarget(ProxyInfo info, ByteBuffer chunk) {
                    try {
                        final ByteBuffer readOnlyBuffer = chunk.asReadOnlyBuffer();
                        byte[] arr = new byte[readOnlyBuffer.remaining()];
                        readOnlyBuffer.get(arr);
                        resBody.write(arr);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }

                @Override
                public void onResponseBodyChunkReceived(ProxyInfo info) {
                    complete[1] = true;
                }
            }))
        );

        RequestBody body = RequestBody.create("{\"hello\": 1}", MediaType.parse("application/json; charset=utf-8"));

        Request request = new Request.Builder()
            .url(router.uri().toURL())
            .post(body)
            .build();

        String res;
        try (Response response = ClientUtils.client.newCall(request).execute()) {
            res = response.body().string();
        }

        while (!(complete[0] && complete[1])) {
            Thread.sleep(100);
        }

        assertEquals("hello", res);
        assertEquals("hello", resBody.toString(StandardCharsets.UTF_8));
        assertEquals("{\"hello\": 1}", reqBody.toString(StandardCharsets.UTF_8));


    }

}
