package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.jdkconnector.ConnectorSocket;
import com.hsbc.cranker.jdkconnector.CrankerConnector;
import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.ResponseState;
import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.time.Duration;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.hsbc.cranker.jdkconnector.ConnectorSocket.State.IDLE;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.AssertUtils.assertEventually;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class TimeoutTest {

    private CrankerRouter router;
    private MuServer routerServer;
    private CrankerConnector connector;
    private MuServer target;

    @Test
    public void ifTheIdleTimeoutIsExceededBeforeResponseStartedThenA504IsReturned() throws IOException {
        router = crankerRouter().withIdleTimeout(250, TimeUnit.MILLISECONDS).start();
        routerServer = httpsServer()
            .addHandler(router.createRegistrationHandler())
            .addHandler(router.createHttpHandler())
            .start();

        target = httpServer()
            .addHandler(Method.GET, "/my-app/sleep-without-response",
                (request, response, pathParams) -> Thread.sleep(500))
            .start();
        connector = startConnector("my-app");
        try (Response resp = call(request(routerServer.uri().resolve("/my-app/sleep-without-response")))) {
            assertThat(resp.code(), is(504));
            assertThat(resp.header("content-type"), is("text/html;charset=utf-8"));
            String body = resp.body().string();
            assertThat(body, containsString("<h1>504 Gateway Timeout</h1>"));
            assertThat(body, containsString("<p>The <code>my-app</code> service did not respond in time."));
        }
    }

    @Test
    public void ifTheIdleTimeoutIsExceededAfterResponseStartedThenConnectionIsClosed() throws IOException {
        router = crankerRouter()
            .withIdleTimeout(1450, TimeUnit.MILLISECONDS)
            .start();
        routerServer = httpsServer()
            .addHandler(router.createRegistrationHandler())
            .addHandler(router.createHttpHandler())
            .start();

        target = httpServer()
            .addHandler(Method.GET, "/my-app/send-chunk-then-sleep",
                (request, response, pathParams) -> {
                    response.sendChunk("hi");
                    Thread.sleep(1700);
                    response.sendChunk("bye");
                })
            .start();
        connector = startConnector("my-app");
        try (Response resp = call(request(routerServer.uri().resolve("/my-app/send-chunk-then-sleep")))) {
            assertThat(resp.code(), is(200));
            resp.body().string();
            Assert.fail("should throw exception already.");
        } catch (IOException expected) {
            assertThat(expected instanceof EOFException, is(true));
        }
    }


    @Test
    public void ifTheConnectorDisconnectsWithoutGracefulShutdownItIsEventuallyDetected() throws Exception {
        router = crankerRouter()
            .withIdleTimeout(1450, TimeUnit.MILLISECONDS)
            .start();
        routerServer = httpsServer()
            .addHandler(router.createRegistrationHandler())
            .addHandler(router.createHttpHandler())
            .start();

        target = httpServer()
            .addHandler(Method.GET, "/my-app/send-chunk-then-sleep",
                (request, response, pathParams) -> {
                    response.sendChunk("hi");
                    Thread.sleep(1700);
                    response.sendChunk("bye");
                })
            .start();

        connector = startConnector("my-app-x");

        assertThat(router.collectInfo().service("my-app-x").get().connectors(), hasSize(1));
        final Collection<ConnectorSocket> idleSockets = connector.routers().get(0).idleSockets();
        assertThat(idleSockets.size(), is(2));

        for (ConnectorSocket socket : idleSockets) {
            assertEventually(socket::state, is(IDLE));
            socket.abort();
        }

        // without stopping the connector
        assertEventually(() -> router.collectInfo().service("my-app-x").get().connectors(), empty());
    }

    @Test
    public void ifClientDisconnectedBeforeResponseStartThenProxyListenersShouldInvoke() throws InterruptedException {
        AtomicReference<ProxyInfo> ref = new AtomicReference<>();
        CountDownLatch countDownLatch = new CountDownLatch(1);
        router = crankerRouter()
            .withProxyListeners(Collections.singletonList(new ProxyListener() {
                @Override
                public void onComplete(ProxyInfo proxyInfo) {
                    ref.set(proxyInfo);
                    countDownLatch.countDown();
                }
            }))
            .start();
        routerServer = httpsServer()
            .addHandler(router.createRegistrationHandler())
            .addHandler(router.createHttpHandler())
            .start();

        String url = "/my-app/sleep-without-response";
        target = httpServer()
            .addHandler(Method.GET, url,
                (request, response, pathParams) -> {
                    Thread.sleep(500);
                    response.write("ok!");
                })
            .start();
        connector = startConnector("my-app");

        long callerTimeout = 400L;
        try (Response response = call(request(routerServer.uri().resolve(url)), Duration.ofMillis(callerTimeout))) {
            Assert.fail("Call should have timed out");
        } catch (Exception e) {
            // expected
        } finally {
            assertThat(countDownLatch.await(1000, TimeUnit.MILLISECONDS), is(true));
            assertThat(ref.get(), notNullValue());
            assertThat(ref.get().response().responseState(), is(ResponseState.CLIENT_DISCONNECTED));
        }
    }



    @After
    public void cleanup() throws Exception {
        if (connector != null) connector.stop().get(20, TimeUnit.SECONDS);
        if (target != null) target.stop();
        routerServer.stop();
        if (router != null) router.stop();
    }

    private CrankerConnector startConnector(String targetServiceName) {
        return BaseEndToEndTest.startConnectorAndWaitForRegistration(router, targetServiceName, target, routerServer);
    }

}
