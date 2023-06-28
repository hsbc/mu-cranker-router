package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.ContentTypes;
import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.Util;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import scaffolding.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.preferredProtocols;
import static com.hsbc.cranker.mucranker.BaseEndToEndTest.startConnectorAndWaitForRegistration;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.fail;
import static scaffolding.Action.swallowException;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class CrankerRouterHandlerTest {

    private CrankerRouter router;
    private MuServer routerServer;
    private MuServer registrationServer;
    private CrankerConnector connector;
    private MuServer target;

    @BeforeEach
    void setUp() {
        router = crankerRouter().withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0")).start();
        routerServer = httpsServer()
            .withGzipEnabled(false)
            .addHandler(router.createHttpHandler())
            .start();
        registrationServer = httpsServer()
            .addHandler(router.createRegistrationHandler()).start();
    }

    @AfterEach
    public void cleanup() {
        if (connector != null) swallowException(() -> connector.stop(5, TimeUnit.SECONDS));
        if (target != null) swallowException(target::stop);
        if (registrationServer != null) swallowException(registrationServer::stop);
        if (routerServer != null) swallowException(routerServer::stop);
        if (router != null) swallowException(router::stop);
    }


    @RepeatedTest(3)
    public void getRequestsWithChunksWork(RepetitionInfo repetitionInfo) throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> {
                resp.sendChunk("Got ");
                resp.sendChunk(req.method().name());
                resp.sendChunk(" ");
                resp.sendChunk(req.uri().getRawPath());
                resp.sendChunk(" and query ");
                resp.sendChunk(req.query().get("this thing"));
                return true;
            })
            .start();
        connector = startConnector("my-target-server", preferredProtocols(repetitionInfo));
        try (Response resp = call(request(routerServer.uri().resolve("/my-target-server/blah%20blah?this%20thing=some%20value")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.header("Transfer-Encoding"), is("chunked"));
            assertThat(resp.body().string(), is("Got GET /my-target-server/blah%20blah and query some value"));
        }
    }


    @RepeatedTest(3)
    public void fixedSizeResponsesWork(RepetitionInfo repetitionInfo) throws Exception {
        String body = StringUtils.randomStringOfLength(50000);
        long bodyLength = body.getBytes(StandardCharsets.UTF_8).length;
        target = httpServer()
            .withGzipEnabled(false)
            .addHandler((req, resp) -> {
                resp.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                resp.write(body);
                return true;
            })
            .start();
        connector = startConnector("my-target-server", preferredProtocols(repetitionInfo));
        try (Response resp = call(request(routerServer.uri().resolve("/my-target-server/blah%20blah?this%20thing=some%20value")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Encoding"), is(nullValue()));
            assertThat(resp.header("Transfer-Encoding"), is(nullValue()));
            assertThat(resp.header("Content-Length"), is(String.valueOf(bodyLength)));
            assertThat(resp.body().string(), is(body));
            assertThat(resp.headers("date"), hasSize(1));
        }
    }

    @RepeatedTest(3)
    public void traceRequestsAreBlocked(RepetitionInfo repetitionInfo) throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> true)
            .start();
        connector = startConnector("my-target-server", preferredProtocols(repetitionInfo));

        try (Response resp = call(request(routerServer.uri().resolve("/my-target-server/")).method("TRACE", Util.EMPTY_REQUEST))) {
            assertThat(resp.code(), is(405));
        }
    }

    @RepeatedTest(3)
    public void postsWithBodiesWork(RepetitionInfo repetitionInfo) throws Exception {
        String body = StringUtils.randomStringOfLength(100000);

        target = httpServer()
            .addHandler(Method.POST, "/my-app/upload",
                (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .start();
        connector = startConnector("my-app", preferredProtocols(repetitionInfo));

        try (Response resp = call(request(routerServer.uri().resolve("/my-app/upload"))
            .post(RequestBody.create(body, MediaType.parse("text/plain")))
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is(body));
        }
    }

    @RepeatedTest(3)
    public void catchAllWorksWithAsterisk(RepetitionInfo repetitionInfo) throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("Got " + req.method() + " " + req.uri().getRawPath() + " and query " + req.query().get("this thing"));
                return true;
            })
            .start();
        connector = startConnector("my-target-server", preferredProtocols(repetitionInfo));
        CrankerConnector catchAllConnectorWithEmptyString = startConnector("*", preferredProtocols(repetitionInfo));

        try (Response resp = call(request(routerServer.uri().resolve("/my-target-server/blah%20blah?this%20thing=some%20value")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("Got GET /my-target-server/blah%20blah and query some value"));
        }
        for (int i = 0; i < 5; i++) {
            try (Response resp = call(request(routerServer.uri().resolve("/something-else/blah%20blah?this%20thing=some%20value")))) {
                assertThat(resp.code(), is(200));
                assertThat(resp.body().string(), is("Got GET /something-else/blah%20blah and query some value"));
            }
        }
        catchAllConnectorWithEmptyString.stop(1, TimeUnit.MINUTES);
    }

    @RepeatedTest(3)
    public void catchAllNotWorksWithEmptyString(RepetitionInfo repetitionInfo) throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("Got " + req.method() + " " + req.uri().getRawPath() + " and query " + req.query().get("this thing"));
                return true;
            })
            .start();
        connector = startConnector("my-target-server", preferredProtocols(repetitionInfo));
        CrankerConnector connector = null;
        try {
            connector = startConnector("", preferredProtocols(repetitionInfo));
            fail("it should throw exception");
        } catch (Throwable throwable) {
            assertThat(throwable.getClass().getName(), is("java.lang.IllegalArgumentException"));
            assertThat(throwable.getMessage(), is("Routes must contain only letters, numbers, underscores or hyphens"));
        } finally {
            if (connector != null) connector.stop(5, TimeUnit.SECONDS);
        }
    }

    @RepeatedTest(3)
    public void requestsToRoutesThatNeverExistedReturn404sIfThereIsNoCatchAll(RepetitionInfo repetitionInfo) throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("OK");
                return true;
            })
            .start();
        try (Response resp = call(request(routerServer.uri().resolve("/blah.txt")))) {
            assertThat(resp.code(), is(404));
        }

        final List<String> preferredProtocols = preferredProtocols(repetitionInfo);
        CrankerConnector catchAll = startConnector("*", preferredProtocols);
        try (Response resp = call(request(routerServer.uri().resolve("/blah.txt")))) {
            assertThat(resp.code(), is(200));
        }


        assertThat(catchAll.stop(20, TimeUnit.SECONDS), is(true));
        try (Response resp = call(request(routerServer.uri().resolve("/blah.txt")))) {
            switch (preferredProtocols.get(0)) {
                case "cranker_3.0": {
                    assertThat(resp.code(), is(404));
                    break;
                }
                case "cranker_1.0": {
                    assertThat(resp.code(), is(503));
                    break;
                }
                default: {
                    fail("version not supported");
                }
            }
        }
    }

    @RepeatedTest(3)
    public void requestToRouterThatCatchAllHasLowerPriority(RepetitionInfo repetitionInfo) throws IOException {
        MuServer serverCatchAll = httpServer()
            .addHandler((req, resp) -> {
                resp.write("OK *");
                return true;
            })
            .start();
        final CrankerConnector connectorCatchAll = startConnectorAndWaitForRegistration(router, "*", serverCatchAll, preferredProtocols(repetitionInfo), "*", registrationServer);

        try (Response resp = call(request(routerServer.uri().resolve("/blah/hello.txt")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("OK *"));
        }

        MuServer serverBlah = httpServer()
            .addHandler((req, resp) -> {
                resp.write("OK blah");
                return true;
            })
            .start();
        final CrankerConnector connectorBlah = startConnectorAndWaitForRegistration(router, "*", serverBlah, preferredProtocols(repetitionInfo), "blah", registrationServer);

        try (Response resp = call(request(routerServer.uri().resolve("/blah/hello.txt")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("OK blah"));
        }

        connectorCatchAll.stop(1, TimeUnit.MINUTES);
        connectorBlah.stop(1, TimeUnit.MINUTES);
        serverCatchAll.stop();
        serverBlah.stop();
    }

    @RepeatedTest(3)
    public void requestsToTopLevelPathsReturn404IfNoCatchAll(RepetitionInfo repetitionInfo) throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("OK");
                return true;
            })
            .start();
        connector = startConnector("something", preferredProtocols(repetitionInfo));
        try (Response resp = call(request(routerServer.uri().resolve("/something/blah")))) {
            assertThat(resp.code(), is(200));
        }
        try (Response resp = call(request(routerServer.uri().resolve("/")))) {
            assertThat(resp.code(), is(404));
        }
        try (Response resp = call(request(routerServer.uri().resolve("/hi")))) {
            assertThat(resp.code(), is(404));
        }
    }

    private CrankerConnector startConnector(String targetServiceName, List<String> preferredProtocols) {
        return startConnectorAndWaitForRegistration(router, "*", target, preferredProtocols, targetServiceName, registrationServer);
    }

}
