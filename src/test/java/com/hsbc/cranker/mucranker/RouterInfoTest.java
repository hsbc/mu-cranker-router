package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

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

    @Test
    public void connectorInfoIsAvailableViaCollectInfo() throws Exception {
        router = crankerRouter().start();

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

        connector = startConnector("my-target-server");
        connector2 = startConnector("another-target-server");

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

    @Test
    public void infoIsExposedAsAMapForSimpleHealthReporting() throws IOException {
        router = crankerRouter().start();
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

        connector = startConnector("my-target-server");
        connector2 = startConnector("another-target-server");

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


    @AfterEach
    public void cleanup() {
        if (connector != null) swallowException(() -> connector.stop(5, TimeUnit.SECONDS));
        if (connector2 != null) swallowException(() -> connector2.stop(5, TimeUnit.SECONDS));
        if (target != null) swallowException(target::stop);
        if (routerServer != null) swallowException(routerServer::stop);
        if (router != null) swallowException(router::stop);
    }

    private CrankerConnector startConnector(String targetServiceName) {
        return BaseEndToEndTest.startConnectorAndWaitForRegistration(router, targetServiceName, target, routerServer);
    }

}
