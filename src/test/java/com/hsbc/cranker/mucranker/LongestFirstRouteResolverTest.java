package com.hsbc.cranker.mucranker;


import com.hsbc.cranker.connector.CrankerConnector;
import com.hsbc.cranker.connector.CrankerConnectorBuilder;
import io.muserver.MuServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.*;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class LongestFirstRouteResolverTest {

    private HttpClient client = CrankerConnectorBuilder.createHttpClient(true).build();

    private CrankerRouter cranker;
    private MuServer router;

    private MuServer serverA;
    private MuServer serverB;
    private MuServer serverC;

    private CrankerConnector connectorA;
    private CrankerConnector connectorB;
    private CrankerConnector connectorC;

    @AfterEach
    void tearDown() {
        if (connectorA != null) connectorA.stop(10, TimeUnit.SECONDS);
        if (connectorB != null) connectorB.stop(10, TimeUnit.SECONDS);
        if (connectorC != null) connectorC.stop(10, TimeUnit.SECONDS);

        if (serverA != null) serverA.stop();
        if (serverB != null) serverB.stop();
        if (serverC != null) serverC.stop();

        if (cranker != null) cranker.stop();
        if (router != null) router.stop();
    }

    @RepeatedTest(3)
    void testResolve(RepetitionInfo repetitionInfo) throws InterruptedException, IOException {

        cranker = crankerRouter()
            .withRouteResolver(new LongestFirstRouteResolver()) // specify customized resolver
            .withConnectorMaxWaitInMillis(2000)
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .start();

        router = httpsServerForTest()
            .withHttpsPort(12302)
            .addHandler(cranker.createRegistrationHandler())
            .addHandler(cranker.createHttpHandler())
            .start();

        String responseA = "a";
        serverA = httpServer()
            .addHandler((request, response) -> {
                response.status(200);
                response.write(responseA);
                return true;
            })
            .start();
        connectorA = startConnectorAndWaitForRegistration(cranker, "*", serverA, preferredProtocols(repetitionInfo), responseA, router);

        String responseB = "b";
        serverB = httpServer()
            .addHandler((request, response) -> {
                response.status(200);
                response.write(responseB);
                return true;
            })
            .start();
        connectorB = startConnectorAndWaitForRegistration(cranker, "*", serverB, preferredProtocols(repetitionInfo), "a/b", router);

        String responseC = "c";
        serverC = httpServer()
            .addHandler((request, response) -> {
                response.status(200);
                response.write(responseC);
                return true;
            })
            .start();
        connectorC = startConnectorAndWaitForRegistration(cranker, "*", serverC, preferredProtocols(repetitionInfo), "a/b/c", router);

        httpTest(router, "/a", responseA);
        httpTest(router, "/a/", responseA);
        httpTest(router, "/a/b", responseB);
        httpTest(router, "/a/b/", responseB);
        httpTest(router, "/a/b/c", responseC);
        httpTest(router, "/a/b/c/", responseC);
    }

    private void httpTest(MuServer router, String uri, String expectBody) throws IOException, InterruptedException {
        URI target = router.uri().resolve(uri);
        HttpRequest request = HttpRequest.newBuilder().uri(target).build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.body(), is(expectBody));
    }

    @Test
    public void testResolve() {

        final Set<String> routes = Set.of(
            "a",
            "a/b",
            "a/b/c"
        );

        LongestFirstRouteResolver resolver = new LongestFirstRouteResolver();

        assertThat(resolver.resolve(routes, "/a"), is("a"));
        assertThat(resolver.resolve(routes, "/a/"), is("a"));
        assertThat(resolver.resolve(routes, "/a/hello"), is("a"));
        assertThat(resolver.resolve(routes, "a/hello"), is("a"));

        assertThat(resolver.resolve(routes, "/a/b"), is("a/b"));
        assertThat(resolver.resolve(routes, "/a/b/"), is("a/b"));
        assertThat(resolver.resolve(routes, "/a/b/hello"), is("a/b"));

        assertThat(resolver.resolve(routes, "/a/b/c"), is("a/b/c"));
        assertThat(resolver.resolve(routes, "/a/b/c/"), is("a/b/c"));
        assertThat(resolver.resolve(routes, "/a/b/c/hello"), is("a/b/c"));

        assertThat(resolver.resolve(routes, "/ab"), is("*"));
        assertThat(resolver.resolve(routes, "/"), is("*"));
        assertThat(resolver.resolve(routes, "/non-exist/b/test"), is("*"));
        assertThat(resolver.resolve(routes, "/non-exist"), is("*"));

    }
}
