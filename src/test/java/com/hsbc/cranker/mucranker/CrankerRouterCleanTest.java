package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.ContentTypes;
import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.*;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.Action.swallowException;
import static scaffolding.AssertUtils.assertEventually;
import static scaffolding.ClientUtils.client;
import static scaffolding.ClientUtils.request;

public class CrankerRouterCleanTest {

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
    public void willCleanRoutesAfterKeepTime(RepetitionInfo repetitionInfo) throws Exception {


        crankerRouter = crankerRouter()
            .withConnectorMaxWaitInMillis(5000L)
            .withRoutesKeepTime(300, TimeUnit.MILLISECONDS) // setup route keep time
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .start();

        router = httpsServerForTest()
            .addHandler(Method.GET, "health/connectors", (request, response, pathParams) -> {
                response.contentType(ContentTypes.APPLICATION_JSON);
                response.write(new JSONObject().put("services", crankerRouter.collectInfo().toMap()).toString(2));
            })
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();

        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("OK");
                return true;
            })
            .start();

        final List<String> preferredProtocols = preferredProtocols(repetitionInfo);
        connector = startConnectorAndWaitForRegistration(crankerRouter, "*", target, preferredProtocols, "something", router);

        // assert route is working
        for (int i = 0; i < 10; i++) {
            try (Response resp = client.newCall(request(router.uri().resolve("/something/hello")).build()).execute()) {
                assertThat(resp.code(), is(200));
                assert resp.body() != null;
                assertThat(resp.body().string(), is("OK"));
            }
        }

        // assert route info clear before de-register
        assertEventually(() -> {
            try (Response resp = client.newCall(request(router.uri().resolve("/health/connectors")).build()).execute()) {
                assertThat(resp.code(), is(200));
                assert resp.body() != null;
                final JSONObject servicesJson = new JSONObject(resp.body().string()).getJSONObject("services");
                return servicesJson.has("something");
            }
        }, is(true));

        // shutdown connector, which causing WebSocketFarm do retry
        assertThat(connector.stop(5, TimeUnit.SECONDS), is(true));

        // assert route info clear after de-register
        assertEventually(() -> {
            try (Response resp = client.newCall(request(router.uri().resolve("/health/connectors")).build()).execute()) {
                assertThat(resp.code(), is(200));
                assert resp.body() != null;
                final JSONObject servicesJson = new JSONObject(resp.body().string()).getJSONObject("services");
                return servicesJson.has("something");
            }
        }, is(false));

        // assert client will get 404
        for (int i = 0; i < 10; i++) {
            try (Response resp = client.newCall(request(router.uri().resolve("/something/hello")).build()).execute()) {
                assertThat(resp.code(), is(404));
            }
        }
    }

}
