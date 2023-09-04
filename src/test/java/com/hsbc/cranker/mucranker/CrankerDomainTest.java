package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.Method;
import io.muserver.MuServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.*;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.nullValue;
import static scaffolding.Action.swallowException;
import static scaffolding.AssertUtils.assertEventually;

public class CrankerDomainTest {

    private CrankerRouter crankerRouter;
    private MuServer registrationServer;
    private MuServer router;

    private MuServer targetServer1;
    private MuServer targetServer2;

    private CrankerConnector connector1;
    private CrankerConnector connector2;

    @BeforeEach
    void setUp() {
        crankerRouter = CrankerRouterBuilder.crankerRouter()
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .withSendLegacyForwardedHeaders(true)
            .start();
        router = httpsServer()
            .withHttpPort(0)
            .withGzipEnabled(false)
            .addHandler(crankerRouter.createHttpHandler())
            .start();
        registrationServer = httpsServer()
            .addHandler(crankerRouter.createRegistrationHandler())
            .start();
    }

    @RepeatedTest(3)
    void testRegisterWithDomainRouteAs(RepetitionInfo repetitionInfo) throws InterruptedException {

        List<String> preferredProtocols = preferredProtocols(repetitionInfo);

        targetServer1 = httpServer()
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.write("localhost");
            })
            .start();
        connector1  = startConnector("localhost", "*", preferredProtocols, targetServer1, registrationServer);
        waitForRegistration("*", connector1.connectorId(), 2, new CrankerRouter[]{crankerRouter});

        targetServer2 = httpServer()
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.write("127.0.0.1");
            })
            .start();
        connector2  = startConnector("127.0.0.1", "*", preferredProtocols, targetServer2, registrationServer);
        waitForRegistration("*", connector2.connectorId(), 2, new CrankerRouter[]{crankerRouter});

        final HashMap<String, AtomicInteger> localhostResult = callAndGroupByBody(URI.create("https://localhost:%s/hello".replace("%s", String.valueOf(router.uri().getPort()))), 20);
        final HashMap<String, AtomicInteger> loopbackIpResult = callAndGroupByBody(URI.create("https://127.0.0.1:%s/hello".replace("%s", String.valueOf(router.uri().getPort()))), 20);

        final int localTotal = localhostResult.values().stream().mapToInt(AtomicInteger::get).sum();
        assertThat(localTotal, is(20));

        final int loopbackTotal = loopbackIpResult.values().stream().mapToInt(AtomicInteger::get).sum();
        assertThat(loopbackTotal, is(20));

        final int currentRepetition = repetitionInfo.getCurrentRepetition();
        if (currentRepetition == 1) {
            // for cranker v1, it's just distributes randomly to any connector
            assertThat(localhostResult.get("localhost").get(), greaterThan(5));
            assertThat(localhostResult.get("127.0.0.1").get(), greaterThan(5));

            assertThat(loopbackIpResult.get("localhost").get(), greaterThan(5));
            assertThat(loopbackIpResult.get("127.0.0.1").get(), greaterThan(5));

        } else {
            // for cranker v3, it distributes by domain precisely
            assertThat(localhostResult.get("localhost").get(), is(20));
            assertThat(localhostResult.get("127.0.0.1"), is(nullValue()));

            assertThat(loopbackIpResult.get("localhost"), is(nullValue()));
            assertThat(loopbackIpResult.get("127.0.0.1").get(), is(20));
        }

    }

    @AfterEach
    public void stop() {
        swallowException(() -> {if (connector1 != null) connector1.stop(30, TimeUnit.SECONDS);});
        swallowException(() -> {if (connector2 != null) connector2.stop(30, TimeUnit.SECONDS);});
        assertEventually(() -> crankerRouter.idleConnectionCount(), is(0));
        swallowException(registrationServer::stop);
        swallowException(router::stop);
        swallowException(targetServer1::stop);
        swallowException(targetServer2::stop);
        swallowException(crankerRouter::stop);
    }

}
