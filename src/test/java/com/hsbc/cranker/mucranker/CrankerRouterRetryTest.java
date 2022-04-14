package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.jdkconnector.CrankerConnector;
import io.muserver.MuServer;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;

import java.net.SocketTimeoutException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.httpsServerForTest;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.Action.swallowException;
import static scaffolding.AssertUtils.assertEventually;
import static scaffolding.ClientUtils.*;

public class CrankerRouterRetryTest {

    private CrankerRouter crankerRouter;
    private MuServer router;
    private MuServer target;
    private CrankerConnector connector;

    @Test
    public void willNotCallTargetServiceWhenClientDropEarly() throws Exception {

        // create a long retry duration router
        crankerRouter = crankerRouter()
            .withConnectorMaxWaitInMillis(5000L)
            .start();

        router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();

        AtomicInteger counter = new AtomicInteger(0);
        target = httpServer()
            .addHandler((req, resp) -> {
                counter.incrementAndGet();
                resp.write("OK");
                return true;
            })
            .start();

        connector = startConnector("something");

        // shutdown connector, which causing WebSocketFarm do retry
        connector.stop().get(5, TimeUnit.SECONDS);

        // client timeout is 200 millis, which smaller than cranker wait timeout which is 1000 ms.
        OkHttpClient client = new OkHttpClient.Builder()
            .hostnameVerifier((hostname, session) -> true)
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .sslSocketFactory(sslContextForTesting(veryTrustingTrustManager).getSocketFactory(), veryTrustingTrustManager)
            .build();

        // it should keep throwing timeout exception before connector available
        for (int i = 0; i < 10; i++) {
            try (Response ignored = client.newCall(request(router.uri().resolve("/something/blah")).build()).execute()) {
                Assert.fail(String.format("it should timeout, but status=%s, body=%s", ignored.code(), ignored.body().string()));
            } catch (Exception e) {
                assertThat(e instanceof SocketTimeoutException, is(true));
            }
        }

        // start connector again, WebsocketFarm retry will notify the waiting queue
        connector = startConnector("something");

        // target server should not be called as the request should all ended
        assertThat(counter.get(), is(0));

        // now call 10 time again, target server will be called 10 time
        for (int i = 0; i < 10; i++) {
            try (Response response = client.newCall(request(router.uri().resolve("/something/blah")).build()).execute()) {
                assertThat(response.code(), is(200));
                assertThat(response.body().string(), is("OK"));
            } catch (Exception e) {
                e.printStackTrace();
                Assert.fail("it should not throw exception but got " + e);
            }
        }
        assertThat(counter.get(), is(10));

        // sliding window can resume to 2 (default) finally
        assertEventually(() -> crankerRouter.collectInfo().service("something").isPresent(), is(true));
        assertEventually(() -> crankerRouter.collectInfo().service("something").get().connectors().size(), is(1));
        assertEventually(() -> crankerRouter.collectInfo().service("something").get().connectors().get(0).connections().size(), is(2));
    }

    @After
    public void cleanup() {
        if (connector != null) swallowException(() -> connector.stop().get(5, TimeUnit.SECONDS));
        if (target != null) swallowException(target::stop);
        if (crankerRouter != null) swallowException(crankerRouter::stop);
        if (router != null) swallowException(router::stop);
    }

    private CrankerConnector startConnector(String targetServiceName) {
        return BaseEndToEndTest.startConnectorAndWaitForRegistration(crankerRouter, targetServiceName, target, router);
    }


}
