package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.callAndGroupByBody;
import static com.hsbc.cranker.mucranker.BaseEndToEndTest.httpsServerForTest;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static scaffolding.Action.swallowException;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class MultiConnectorTest {

    private CrankerRouter crankerRouter;
    private MuServer router;
    private MuServer targetV1_1;
    private MuServer targetV1_2;
    private MuServer targetV3_1;
    private MuServer targetV3_2;
    private CrankerConnector connectorV1_1;
    private CrankerConnector connectorV1_2;
    private CrankerConnector connectorV3_1;
    private CrankerConnector connectorV3_2;

    @AfterEach
    public void cleanup() {
        if (connectorV1_1 != null) swallowException(() -> connectorV1_1.stop(5, TimeUnit.SECONDS));
        if (connectorV1_2 != null) swallowException(() -> connectorV1_2.stop(5, TimeUnit.SECONDS));
        if (connectorV3_1 != null) swallowException(() -> connectorV3_1.stop(5, TimeUnit.SECONDS));
        if (connectorV3_2 != null) swallowException(() -> connectorV3_2.stop(5, TimeUnit.SECONDS));
        if (targetV1_1 != null) swallowException(targetV1_1::stop);
        if (targetV1_2 != null) swallowException(targetV1_2::stop);
        if (targetV3_1 != null) swallowException(targetV3_1::stop);
        if (targetV3_2 != null) swallowException(targetV3_2::stop);
        if (crankerRouter != null) swallowException(crankerRouter::stop);
        if (router != null) swallowException(router::stop);
    }

    @BeforeEach
    void setUp() {
        crankerRouter = crankerRouter()
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .start();

        router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V1() {
        // start v1 connector
        targetV1_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello",
                (request, response, pathParams) -> response.write("targetV1_1"))
            .start();
        connectorV1_1 = startConnector("*", "my-service", targetV1_1, List.of("cranker_1.0"));

        // start another v1 connector
        targetV1_2 = httpServer()
            .addHandler(Method.GET, "/my-service/hello",
                (request, response, pathParams) -> response.write("targetV1_2"))
            .start();
        connectorV1_2 = startConnector("*", "my-service", targetV1_2, List.of("cranker_1.0"));

        // traffic proxied to both connector and target
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20);
        assertThat(bodyMap.get("targetV1_1").get(), greaterThan(5));
        assertThat(bodyMap.get("targetV1_2").get(), greaterThan(5));
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V1_catchAllRouteTakeLowerPriority() throws IOException {
        // start v1 connector with "*" route
        targetV1_1 = httpServer()
            .addHandler((request, response) -> {
                response.write("targetV1_1");
                return true;
            })
            .start();
        connectorV1_1 = startConnector("*", "*", targetV1_1, List.of("cranker_1.0"));

        // start another v1 connector with specific route
        targetV1_2 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV1_2"))
            .start();
        connectorV1_2 = startConnector("*", "my-service", targetV1_2, List.of("cranker_1.0"));

        // specific route take higher priority
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20);
        assertThat(bodyMap.get("targetV1_2").get(), is(20));

        // catch all still work
        try (Response resp = call(request(router.uri().resolve("/something")))) {
            assertThat(resp.code(), is(200));
            assert resp.body() != null;
            assertThat(resp.body().string(), is("targetV1_1"));
        }
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V3() {
        // start v3 connector
        targetV3_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_1"))
            .start();
        connectorV3_1 = startConnector("*", "my-service", targetV3_1, List.of("cranker_3.0"));

        // start another v3 connector
        targetV3_2 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_2"))
            .start();
        connectorV3_2 = startConnector("*", "my-service", targetV3_2, List.of("cranker_3.0"));

        // traffic proxied to both connector and target
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20);
        assertThat(bodyMap.get("targetV3_1").get(), greaterThan(5));
        assertThat(bodyMap.get("targetV3_2").get(), greaterThan(5));
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V3_catchAllRouteTakeLowerPriority() throws IOException {
        // start v1 connector with "*" route
        targetV3_1 = httpServer()
            .addHandler((request, response) -> {
                response.write("targetV3_1");
                return true;
            })
            .start();
        connectorV3_1 = startConnector("*", "*", targetV3_1, List.of("cranker_3.0"));

        // start another v1 connector with specific route
        targetV3_2 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_2"))
            .start();
        connectorV3_2 = startConnector("*", "my-service", targetV3_2, List.of("cranker_3.0"));

        // specific route take higher priority
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20);
        assertThat(bodyMap.get("targetV3_2").get(), is(20));

        // catch all still work
        try (Response resp = call(request(router.uri().resolve("/something")))) {
            assertThat(resp.code(), is(200));
            assert resp.body() != null;
            assertThat(resp.body().string(), is("targetV3_1"));
        }
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V1_V3() {
        // start v1 connector
        targetV1_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV1_1"))
            .start();
        connectorV1_1 = startConnector("*", "my-service", targetV1_1, List.of("cranker_1.0"));

        // start another v3 connector
        targetV3_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_1"))
            .start();
        connectorV3_1 = startConnector("*", "my-service", targetV3_1, List.of("cranker_3.0"));

        // traffic proxied to both connector and target
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20);
        assertThat(bodyMap.get("targetV1_1").get(), greaterThan(1));
        assertThat(bodyMap.get("targetV3_1").get(), greaterThan(1));
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V1useCatchAll_V3useSpecificRouteTakeHigherPriority() {
        // start v1 connector
        targetV1_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV1_1"))
            .start();
        connectorV1_1 = startConnector("*", "*", targetV1_1, List.of("cranker_1.0"));

        // start another v3 connector
        targetV3_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_1"))
            .start();
        connectorV3_1 = startConnector("*", "my-service", targetV3_1, List.of("cranker_3.0"));

        // specific route take higher priority
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20, 1);
        assertThat(bodyMap.get("targetV3_1").get(), is(20));
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V3useCatchAll_V1useSpecificRouteTakeHigherPriority() {
        // start v1 connector
        targetV1_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV1_1"))
            .start();
        connectorV1_1 = startConnector("*", "my-service", targetV1_1, List.of("cranker_1.0"));

        // start another v3 connector
        targetV3_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_1"))
            .start();
        connectorV3_1 = startConnector("*", "*", targetV3_1, List.of("cranker_3.0"));

        // specific route take higher priority
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20, 1);
        assertThat(bodyMap.get("targetV1_1").get(), is(20));
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V1useSpecificRouteTakeHigherPriority_V3useCatchAll() {
        // start v1 connector
        targetV1_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV1_1"))
            .start();
        connectorV1_1 = startConnector("*", "my-service", targetV1_1, List.of("cranker_1.0"));

        // start another v3 connector
        targetV3_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_1"))
            .start();
        connectorV3_1 = startConnector("*", "*", targetV3_1, List.of("cranker_3.0"));

        // specific route take higher priority
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20, 20L);
        assertThat(bodyMap.get("targetV1_1").get(), is(20));
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V1useCatchAll_V3useCatchAll() {
        // start v1 connector
        targetV1_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV1_1"))
            .start();
        connectorV1_1 = startConnector("*", "*", targetV1_1, List.of("cranker_1.0"));

        // start another v3 connector
        targetV3_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_1"))
            .start();
        connectorV3_1 = startConnector("*", "*", targetV3_1, List.of("cranker_3.0"));

        // traffic proxied to both connector and target
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20, 2);
        assertThat(bodyMap.get("targetV1_1").get(), greaterThan(1));
        assertThat(bodyMap.get("targetV3_1").get(), greaterThan(1));
    }

    @Test
    void connectorCanDistributedToDifferentConnector_V1useSpecificRoute_V3useSpecificRoute() {
        // start v1 connector
        targetV1_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV1_1"))
            .start();
        connectorV1_1 = startConnector("*", "my-service", targetV1_1, List.of("cranker_1.0"));

        // start another v3 connector
        targetV3_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_1"))
            .start();
        connectorV3_1 = startConnector("*", "my-service", targetV3_1, List.of("cranker_3.0"));

        // traffic proxied to both connector and target
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(router.uri().resolve("/my-service/hello"), 20, 1);
        assertThat(bodyMap.get("targetV1_1").get(), greaterThan(1));
        assertThat(bodyMap.get("targetV3_1").get(), greaterThan(1));
    }



    @Test
    void connectorCanDistributedToV3_V3RegisteredWithDomainTakeHigherPriority() {
        // start v1 connector
        targetV1_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV1_1"))
            .start();
        connectorV1_1 = startConnector("*", "my-service", targetV1_1, List.of("cranker_1.0"));

        // start another v3 connector
        targetV3_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_1"))
            .start();
        connectorV3_1 = startConnector("127.0.0.1", "my-service", targetV3_1, List.of("cranker_3.0"));

        // all proxied to v3 as it's registered with domain "127.0.0.1" take higher priority
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(
            URI.create("https://127.0.0.1:{port}/my-service/hello"
                .replace("{port}", String.valueOf(router.uri().getPort()))),
            20);
        assertThat(bodyMap.get("targetV3_1").get(), is(20));
    }

    @Test
    void connectorCanDistributedToV3_V3RegisteredWithDomainTakeHigherPriorityEvanUsingCatchAllRoute() {
        // start v1 connector
        targetV1_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV1_1"))
            .start();
        connectorV1_1 = startConnector("*", "my-service", targetV1_1, List.of("cranker_1.0"));

        // start another v3 connector
        targetV3_1 = httpServer()
            .addHandler(Method.GET, "/my-service/hello", (request, response, pathParams) -> response.write("targetV3_1"))
            .start();
        connectorV3_1 = startConnector("127.0.0.1", "*", targetV3_1, List.of("cranker_3.0"));

        // all proxied to v3 as it's registered with domain "127.0.0.1" take higher priority
        final HashMap<String, AtomicInteger> bodyMap = callAndGroupByBody(
            URI.create("https://127.0.0.1:{port}/my-service/hello"
                .replace("{port}", String.valueOf(router.uri().getPort()))),
            20);
        assertThat(bodyMap.get("targetV3_1").get(), is(20));
    }

    private CrankerConnector startConnector(String domain, String route, MuServer target, List<String> preferredProtocols) {
        return BaseEndToEndTest.startConnectorAndWaitForRegistration(crankerRouter, domain, target, preferredProtocols, route, router);
    }
}
