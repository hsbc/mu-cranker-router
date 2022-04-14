package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.jdkconnector.CrankerConnector;
import io.muserver.ContentTypes;
import io.muserver.Method;
import io.muserver.MuServer;
import okhttp3.MediaType;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.Util;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import scaffolding.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.startConnectorAndWaitForRegistration;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static scaffolding.Action.swallowException;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class CrankerRouterHandlerTest {

    private CrankerRouter router = crankerRouter().start();
    private MuServer routerServer = httpsServer()
        .withGzipEnabled(false)
        .addHandler(router.createHttpHandler())
        .start();
    private MuServer registrationServer = httpsServer()
        .addHandler(router.createRegistrationHandler()).start();
    private CrankerConnector connector;
    private MuServer target;


    @Test
    public void getRequestsWithChunksWork() throws Exception {
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
        connector = startConnector("my-target-server");
        try (Response resp = call(request(routerServer.uri().resolve("/my-target-server/blah%20blah?this%20thing=some%20value")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Length"), is(nullValue()));
            assertThat(resp.header("Transfer-Encoding"), is("chunked"));
            assertThat(resp.body().string(), is("Got GET /my-target-server/blah%20blah and query some value"));
        }
    }


    @Test
    public void fixedSizeResponsesWork() throws Exception {
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
        connector = startConnector("my-target-server");
        try (Response resp = call(request(routerServer.uri().resolve("/my-target-server/blah%20blah?this%20thing=some%20value")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.header("Content-Encoding"), is(nullValue()));
            assertThat(resp.header("Transfer-Encoding"), is(nullValue()));
            assertThat(resp.header("Content-Length"), is(String.valueOf(bodyLength)));
            assertThat(resp.body().string(), is(body));
            assertThat(resp.headers("date"), hasSize(1));
        }
    }

    @Test
    public void traceRequestsAreBlocked() throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> true)
            .start();
        connector = startConnector("my-target-server");

        try (Response resp = call(request(routerServer.uri().resolve("/my-target-server/")).method("TRACE", Util.EMPTY_REQUEST))) {
            assertThat(resp.code(), is(405));
        }
    }

    @Test
    public void postsWithBodiesWork() throws Exception {
        String body = StringUtils.randomStringOfLength(100000);

        target = httpServer()
            .addHandler(Method.POST, "/my-app/upload",
                (request, response, pathParams) -> response.write(request.readBodyAsString()))
            .start();
        connector = startConnector("my-app");

        try (Response resp = call(request(routerServer.uri().resolve("/my-app/upload"))
            .post(RequestBody.create(body, MediaType.parse("text/plain")))
        )) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is(body));
        }
    }

    @Test
    public void catchAllWorksWithAsterisk() throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("Got " + req.method() + " " + req.uri().getRawPath() + " and query " + req.query().get("this thing"));
                return true;
            })
            .start();
        connector = startConnector("my-target-server");
        CrankerConnector catchAllConnectorWithEmptyString = startConnector("*");

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
        catchAllConnectorWithEmptyString.stop();
    }

    @Test
    public void catchAllNotWorksWithEmptyString() throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("Got " + req.method() + " " + req.uri().getRawPath() + " and query " + req.query().get("this thing"));
                return true;
            })
            .start();
        connector = startConnector("my-target-server");
        CrankerConnector connector = null;
        try {
            connector = startConnector("");
            Assert.fail("it should throw exception");
        } catch (Throwable throwable) {
            assertThat(throwable.getClass().getName(), is("java.lang.IllegalArgumentException"));
            assertThat(throwable.getMessage(), is("Routes must contain only letters, numbers, underscores or hyphens"));
        } finally {
            if (connector != null) connector.stop().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    public void requestsToRoutesThatNeverExistedReturn404sIfThereIsNoCatchAll() throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("OK");
                return true;
            })
            .start();
        try (Response resp = call(request(routerServer.uri().resolve("/blah.txt")))) {
            assertThat(resp.code(), is(404));
        }

        CrankerConnector catchAll = startConnector("*");
        try (Response resp = call(request(routerServer.uri().resolve("/blah.txt")))) {
            assertThat(resp.code(), is(200));
        }

        final CompletableFuture<Void> stopFuture = catchAll.stop();
        stopFuture.get(20, TimeUnit.SECONDS);
        assertThat(stopFuture.isCompletedExceptionally(), is(false));
        try (Response resp = call(request(routerServer.uri().resolve("/blah.txt")))) {
            assertThat(resp.code(), is(503));
        }
    }

    @Test
    public void requestToRouterThatCatchAllHasLowerPriority() throws IOException {
        MuServer serverCatchAll = httpServer()
            .addHandler((req, resp) -> {
                resp.write("OK *");
                return true;
            })
            .start();
        final CrankerConnector connectorCatchAll = startConnectorAndWaitForRegistration(router, "*", serverCatchAll, registrationServer);

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
        final CrankerConnector connectorBlah = startConnectorAndWaitForRegistration(router, "blah", serverBlah, registrationServer);

        try (Response resp = call(request(routerServer.uri().resolve("/blah/hello.txt")))) {
            assertThat(resp.code(), is(200));
            assertThat(resp.body().string(), is("OK blah"));
        }

        connectorCatchAll.stop();
        connectorBlah.stop();
        serverCatchAll.stop();
        serverBlah.stop();
    }

    @Test
    public void requestsToTopLevelPathsReturn404IfNoCatchAll() throws Exception {
        target = httpServer()
            .addHandler((req, resp) -> {
                resp.write("OK");
                return true;
            })
            .start();
        connector = startConnector("something");
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

    @After
    public void cleanup() {
        if (connector != null) swallowException(() -> connector.stop().get(5, TimeUnit.SECONDS));
        if (target != null) swallowException(target::stop);
        if (registrationServer != null) swallowException(registrationServer::stop);
        if (routerServer != null) swallowException(routerServer::stop);
        if (router != null) swallowException(router::stop);
    }

    private CrankerConnector startConnector(String targetServiceName) {
        return startConnectorAndWaitForRegistration(router, targetServiceName, target, registrationServer);
    }

}
