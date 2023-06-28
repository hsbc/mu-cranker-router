package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hsbc.cranker.connector.CrankerConnectorBuilder.CRANKER_PROTOCOL_1;
import static com.hsbc.cranker.connector.CrankerConnectorBuilder.CRANKER_PROTOCOL_3;
import static com.hsbc.cranker.mucranker.BaseEndToEndTest.preferredProtocols;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.Action.swallowException;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class RouterInfoTest {

    private CrankerRouter router;
    private MuServer routerServer;
    private CrankerConnector connector;
    private CrankerConnector connector2;
    private MuServer target;

    @AfterEach
    public void cleanup() {
        if (connector != null) swallowException(() -> connector.stop(5, TimeUnit.SECONDS));
        if (connector2 != null) swallowException(() -> connector2.stop(5, TimeUnit.SECONDS));
        if (target != null) swallowException(target::stop);
        if (routerServer != null) swallowException(routerServer::stop);
        if (router != null) swallowException(router::stop);
    }

    @RepeatedTest(3)
    public void connectorInfoIsAvailableViaCollectInfo(RepetitionInfo repetitionInfo) throws Exception {
        router = crankerRouter().withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0")).start();

        routerServer = httpsServer()
            .addHandler(router.createRegistrationHandler())
            .addHandler(router.createHttpHandler())
            .start();

        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("Got " + req.method() + " " + req.uri().getRawPath() + " and query " + req.query().get("this thing"));
                return true;
            })
            .start();

        assertThat(router.collectInfo().services(), hasSize(0));

        connector = startConnector("my-target-server", preferredProtocols(repetitionInfo));
        connector2 = startConnector("another-target-server", preferredProtocols(repetitionInfo));

        try (Response resp = call(request(routerServer.uri().resolve("/my-target-server/blah%20blah?this%20thing=some%20value")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Got GET /my-target-server/blah%20blah and query some value"));
        }
        try (Response resp = call(request(routerServer.uri().resolve("/another-target-server/blah%20blah?this%20thing=some%20value")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Got GET /another-target-server/blah%20blah and query some value"));
        }

        RouterInfo info = router.collectInfo();
        assertThat(info.services(), hasSize(2));
        ConnectorService connectorService = info.services().get(0);
        assertThat(connectorService.route(), is(oneOf("my-target-server", "another-target-server")));
        assertThat(connectorService.connectors(), hasSize(1));
        ConnectorInstance ci = connectorService.connectors().get(0);
        assertThat(ci.ip(), is("127.0.0.1"));
        assertThat(ci.connections().size(), is(oneOf(1, 2))); // 1 if it's not been replaced
    }

    @RepeatedTest(3)
    public void infoIsExposedAsAMapForSimpleHealthReporting(RepetitionInfo repetitionInfo) throws IOException {
        router = crankerRouter().withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0")).start();
        routerServer = httpsServer()
            .addHandler(Method.GET, "/health",
                (req, resp, pathParams) -> {
                    resp.contentType("application/json");
                    JSONObject health = new JSONObject()
                        .put("isAvailable", true)
                        .put("mucrankerVersion", CrankerRouter.muCrankerVersion())
                        .put("services", router.collectInfo().toMap());
                    resp.write(health.toString(2));
                })
            .addHandler(router.createRegistrationHandler())
            .addHandler(router.createHttpHandler())
            .start();

        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("Got " + req.method() + " " + req.uri().getRawPath() + " and query " + req.query().get("this thing"));
                return true;
            })
            .start();

        connector = startConnector("my-target-server", preferredProtocols(repetitionInfo));
        connector2 = startConnector("another-target-server", preferredProtocols(repetitionInfo));

        call(request(routerServer.uri().resolve("/my-target-server/"))).close();

        try (Response resp = call(request(routerServer.uri().resolve("/health")))) {
            JSONObject health = new JSONObject(resp.body().string());
            JSONObject services = health.getJSONObject("services");
            assertThat(services.has("my-target-server"), is(true));
            assertThat(services.has("another-target-server"), is(true));

            JSONObject mts = services.getJSONObject("my-target-server");
            assertThat(mts.getString("name"), is("my-target-server"));
            assertThat(mts.getString("componentName"), is("junit"));
            assertThat(mts.getBoolean("isCatchAll"), is(false));
            JSONArray connectors = mts.getJSONArray("connectors");
            JSONObject connector = (JSONObject) connectors.get(0);
            assertThat(connector.has("connectorInstanceID"), is(true));
            assertThat(connector.getBoolean("darkMode"), is(false));
            assertThat(connector.getString("ip"), is("127.0.0.1"));

            JSONArray connections = connector.getJSONArray("connections");
            JSONObject connection = (JSONObject) connections.get(0);
            assertThat(connection.has("port"), is(true));
            assertThat(connection.has("socketID"), is(true));
        }
    }

    @Test
    public void infoIsExposedAsAMapForSimpleHealthReportingForBothV1AndV3() throws IOException {
        router = crankerRouter().withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0")).start();
        routerServer = httpsServer()
            .addHandler(Method.GET, "/health",
                (req, resp, pathParams) -> {
                    resp.contentType("application/json");
                    JSONObject health = new JSONObject()
                        .put("isAvailable", true)
                        .put("mucrankerVersion", CrankerRouter.muCrankerVersion())
                        .put("services", router.collectInfo().toMap());
                    resp.write(health.toString(2));
                })
            .addHandler(router.createRegistrationHandler())
            .addHandler(router.createHttpHandler())
            .start();

        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("Got " + req.method() + " " + req.uri().getRawPath() + " and query " + req.query().get("this thing"));
                return true;
            })
            .start();

        connector = startConnector("my-target-server", List.of(CRANKER_PROTOCOL_1));
        connector2 = startConnector("my-target-server", List.of(CRANKER_PROTOCOL_3));

        call(request(routerServer.uri().resolve("/my-target-server/"))).close();

        try (Response resp = call(request(routerServer.uri().resolve("/health")))) {
            JSONObject health = new JSONObject(resp.body().string());
            JSONObject services = health.getJSONObject("services");
            assertThat(services.has("my-target-server"), is(true));

            JSONObject mts = services.getJSONObject("my-target-server");
            assertThat(mts.getString("name"), is("my-target-server"));
            assertThat(mts.getString("componentName"), is("junit"));
            assertThat(mts.getBoolean("isCatchAll"), is(false));

            JSONArray connectors = mts.getJSONArray("connectors");
            assertThat(connectors.length(), is(2));

            JSONObject connector1 = (JSONObject) connectors.get(0);
            assertThat(connector1.has("connectorInstanceID"), is(true));
            assertThat(connector1.getBoolean("darkMode"), is(false));
            assertThat(connector1.getString("ip"), is("127.0.0.1"));
            JSONArray connections1 = connector1.getJSONArray("connections");
            JSONObject connections1_1 = (JSONObject) connections1.get(0);
            assertThat(connections1_1.get("protocol"), is("cranker_1.0"));
            assertThat(connections1_1.has("port"), is(true));
            assertThat(connections1_1.has("socketID"), is(true));

            JSONObject connector2 = (JSONObject) connectors.get(1);
            assertThat(connector2.has("connectorInstanceID"), is(true));
            assertThat(connector2.getBoolean("darkMode"), is(false));
            assertThat(connector2.getString("ip"), is("127.0.0.1"));
            JSONArray connections2 = connector2.getJSONArray("connections");
            JSONObject connections2_1 = (JSONObject) connections2.get(0);
            assertThat(connections2_1.get("protocol"), is("cranker_3.0"));
            assertThat(connections2_1.has("port"), is(true));
            assertThat(connections2_1.has("socketID"), is(true));
        }
    }

    private CrankerConnector startConnector(String targetServiceName, List<String> preferredProtocols) {
        return BaseEndToEndTest.startConnectorAndWaitForRegistration(router, "*", target, preferredProtocols, targetServiceName, routerServer);
    }

}
