package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.httpsServerForTest;
import static com.hsbc.cranker.mucranker.BaseEndToEndTest.preferredProtocols;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.Action.swallowException;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class CrankerRouterRegistrationTest {

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
    public void canNotMapRouteWithStashWhenUsingDefaultRouteResolver(RepetitionInfo repetitionInfo) throws IOException {

        crankerRouter = crankerRouter()
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .start();

        router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();

        target = httpServer()
            .addHandler(Method.GET, "/my-service/api/instance", (request, response, pathParams) -> {
                response.write("/my-service/instance");
            })
            .start();

        connector = startConnector("my-service/api", preferredProtocols(repetitionInfo));

        try (Response resp = call(request(router.uri().resolve("/my-service/api/instance")))) {
            assertThat(resp.code(), is(404));
        }
    }

    @RepeatedTest(3)
    public void canMapRouteWithStashWhenUsingLongFirstRouteResolver(RepetitionInfo repetitionInfo) throws IOException {

        crankerRouter = crankerRouter()
            .withRouteResolver(new LongestFirstRouteResolver())
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .start();

        router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();

        target = httpServer()
            .addHandler(Method.GET, "/my-service/api/instance", (request, response, pathParams) -> {
                response.write("/my-service/api/instance");
            })
            .start();
        connector = startConnector("my-service/api", preferredProtocols(repetitionInfo));

        try (Response resp = call(request(router.uri().resolve("/my-service/api/instance")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("/my-service/api/instance"));
        }
        try (Response resp = call(request(router.uri().resolve("/my-service")))) {
            assertThat(resp.code(), is(404));
        }

    }

    private CrankerConnector startConnector(String targetServiceName, List<String> preferredProtocols) {
        return BaseEndToEndTest.startConnectorAndWaitForRegistration(crankerRouter, "*", target, preferredProtocols, targetServiceName, router);
    }
}
