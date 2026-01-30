package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.ContentTypes;
import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import org.hamcrest.Matchers;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.startConnector;
import static com.hsbc.cranker.mucranker.BaseEndToEndTest.waitForRegistration;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static scaffolding.Action.swallowException;
import static scaffolding.AssertUtils.assertEventually;
import static scaffolding.ClientUtils.client;
import static scaffolding.ClientUtils.request;

public class CrankerConnectorInfoTest {
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
            .addHandler(Method.GET, "/health/connectors", (request, response, pathParams) -> {
                response.contentType(ContentTypes.APPLICATION_JSON);
                response.write(new JSONObject().put("services", crankerRouter.collectInfo().toMap()).toString(2));
            })
            .addHandler(crankerRouter.createHttpHandler())
            .start();
        registrationServer = httpsServer()
            .addHandler(crankerRouter.createRegistrationHandler())
            .start();
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

    @Test
    public void ableToGetComponentNameFromConnector() {
        targetServer1 = httpServer()
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.write("localhost");
            })
            .start();
        connector1  = startConnector("service-a", "localhost", "*", List.of("cranker_3.0"), targetServer1, registrationServer);
        waitForRegistration("*", connector1.connectorId(), 2, new CrankerRouter[]{crankerRouter});

        targetServer2 = httpServer()
            .addHandler(Method.GET, "/hello", (request, response, pathParams) -> {
                response.status(200);
                response.write("127.0.0.1");
            })
            .start();
        connector2  = startConnector("service-b", "127.0.0.1", "*", List.of("cranker_3.0"), targetServer2, registrationServer);
        waitForRegistration("*", connector2.connectorId(), 2, new CrankerRouter[]{crankerRouter});

        assertEventually(() -> {
            try (Response resp = client.newCall(request(router.uri().resolve("/health/connectors")).build()).execute()) {
                assertThat(resp.code(), Matchers.is(200));
                assert resp.body() != null;
                final JSONObject servicesJson = new JSONObject(resp.body().string()).getJSONObject("services");
                JSONArray connectors = servicesJson.getJSONObject("*").getJSONArray("connectors");
                List<String> componentNames = IntStream.range(0, connectors.length()).mapToObj(i -> connectors.getJSONObject(i).getString("componentName")).collect(Collectors.toList());
                return componentNames.size() == 2 && componentNames.containsAll(List.of("service-a", "service-b"));
            }
        }, Matchers.is(true));
    }
}
