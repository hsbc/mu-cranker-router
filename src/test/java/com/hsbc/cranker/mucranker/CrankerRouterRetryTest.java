package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.MuServer;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.*;
import static com.hsbc.cranker.mucranker.BaseEndToEndTest.startConnectorAndWaitForRegistration;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.fail;
import static scaffolding.Action.swallowException;
import static scaffolding.AssertUtils.assertEventually;
import static scaffolding.ClientUtils.*;

public class CrankerRouterRetryTest {

    private CrankerRouter crankerRouter;
    private MuServer router;
    private MuServer target;
    private CrankerConnector connector;

    @AfterEach
    public void cleanup() {
        if (connector != null) swallowException(() -> connector.stop(5, TimeUnit.SECONDS));
        if (target != null) swallowException(target::stop);
        if (crankerRouter != null) swallowException(crankerRouter::stop);
        if (router != null) swallowException(router::stop);
    }

    @RepeatedTest(3)
    public void willNotCallTargetServiceWhenClientDropEarly(RepetitionInfo repetitionInfo) throws Exception {

        // create a long retry duration router
        crankerRouter = crankerRouter()
            .withConnectorMaxWaitInMillis(5000L)
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
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

        final List<String> preferredProtocols = preferredProtocols(repetitionInfo);
        connector = startConnectorAndWaitForRegistration(crankerRouter, "*", target, preferredProtocols, "something", router);

        // shutdown connector, which causing WebSocketFarm do retry
        assertThat(connector.stop(5, TimeUnit.SECONDS), is(true));

        // client timeout is 200 millis, which smaller than cranker wait timeout which is 1000 ms.
        OkHttpClient client = new OkHttpClient.Builder()
            .hostnameVerifier((hostname, session) -> true)
            .readTimeout(200, TimeUnit.MILLISECONDS)
            .sslSocketFactory(sslContextForTesting(veryTrustingTrustManager).getSocketFactory(), veryTrustingTrustManager)
            .build();

        for (int i = 0; i < 10; i++) {
            final String protocol = preferredProtocols.get(0);
            switch (protocol) {
                case "cranker_3.0": {
                    try (Response response = client.newCall(request(router.uri().resolve("/something/blah")).build()).execute()) {
                        assertThat(response.code(), equalTo(404));
                    } catch (Exception e) {
                        e.printStackTrace();
                        fail("cranker_3.0 should return 404 when connector not available");
                    }
                    break;
                }
                case "cranker_1.0" : {
                    try (Response response = client.newCall(request(router.uri().resolve("/something/blah")).build()).execute()) {
                        fail(String.format("it should timeout, but status=%s, body=%s", response.code(), response.body().string()));
                    } catch (Exception e) {
                        assertThat(e instanceof SocketTimeoutException, is(true));
                    }
                    break;
                }
                default: {
                    fail("it should be either cranker_3.0 or cranker_1.0");
                }
            }
        }

        // start connector again, WebsocketFarm retry will notify the waiting queue
        connector = startConnectorAndWaitForRegistration(crankerRouter, "*", target, preferredProtocols, "something", router);

        // target server should not be called as the request should all ended
        assertThat(counter.get(), is(0));

        // now call 10 time again, target server will be called 10 time
        for (int i = 0; i < 10; i++) {
            try (Response response = client.newCall(request(router.uri().resolve("/something/blah")).build()).execute()) {
                assertThat(response.code(), is(200));
                assertThat(response.body().string(), is("OK"));
            } catch (Exception e) {
                e.printStackTrace();
                fail("it should not throw exception but got " + e);
            }
        }
        assertThat(counter.get(), is(10));

        // sliding window can resume to 2 (default) finally
        assertEventually(() -> crankerRouter.collectInfo().service("something").isPresent(), is(true));
        assertEventually(() -> crankerRouter.collectInfo().service("something").get().connectors().size(), is(1));
        assertEventually(() -> crankerRouter.collectInfo().service("something").get().connectors().get(0).connections().size(), is(2));
    }

}
