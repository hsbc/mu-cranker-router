package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.Method;
import io.muserver.MuServer;
import io.muserver.Mutils;
import io.muserver.handlers.ResourceHandlerBuilder;
import okhttp3.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import scaffolding.ClientUtils;
import scaffolding.RawClient;
import scaffolding.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.preferredProtocols;
import static io.muserver.MuServerBuilder.httpServer;
import static io.muserver.MuServerBuilder.httpsServer;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static scaffolding.Action.swallowException;
import static scaffolding.AssertUtils.assertEventually;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class HttpTest {

    private MuServer targetServer ;

    private CrankerRouter crankerRouter;
    private MuServer registrationServer;
    private MuServer router;

    private CrankerConnector connector;
    private CrankerConnector connector2;

    @BeforeEach
    void setUp(RepetitionInfo repetitionInfo) {
        targetServer = httpServer()
            .addHandler(Method.GET, "/echo-headers", (request, response, pathParams) -> {
                response.headers().set("Server", "mu");
                for (Map.Entry<String, String> entry : request.headers()) {
                    response.headers().add(entry.getKey(), entry.getValue());
                }
            })
            .addHandler(ResourceHandlerBuilder.classpathHandler("/web"))
            .start();
        crankerRouter = CrankerRouterBuilder.crankerRouter()
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .withSendLegacyForwardedHeaders(true)
            .start();
        router = httpsServer()
            .withHttpPort(0)
            .withGzipEnabled(false)
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();
        registrationServer = httpsServer().addHandler(crankerRouter.createRegistrationHandler()).start();
        connector = BaseEndToEndTest.startConnectorAndWaitForRegistration(crankerRouter, "*", targetServer, preferredProtocols(repetitionInfo), "*", router);
    }

    @AfterEach
    public void stop() {
        swallowException(() -> {if (connector != null) connector.stop(30, TimeUnit.SECONDS);});
        swallowException(() -> {if (connector2 != null) connector2.stop(30, TimeUnit.SECONDS);});
        assertEventually(() -> crankerRouter.idleConnectionCount(), is(0));
        swallowException(targetServer::stop);
        swallowException(registrationServer::stop);
        swallowException(router::stop);
        swallowException(crankerRouter::stop);
    }

    private static byte[] decompress(byte[] compressedContent) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Mutils.copy(new GZIPInputStream(new ByteArrayInputStream(compressedContent)), out, 8192);
            return out.toByteArray();
        }
    }

    @RepeatedTest(3)
    public void canMakeGETRequestsWithFixedSizeResponses() throws Exception {
        String text = StringUtils.LARGE_TXT;
        try (Response response =
                 call(request(router.uri().resolve("/static/large-txt-file.txt"))
                     .header("accept-encoding", "none"))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), equalTo(text));
            Map<String, List<String>> headers = response.headers().toMultimap();
            assertThat(headers.get("Content-Type"), contains("text/plain;charset=utf-8"));
            assertThat(headers.get("Content-Length"), contains(String.valueOf(text.getBytes(UTF_8).length)));
            assertThat(headers.get("Transfer-Encoding"), is(nullValue()));
            assertThat(headers.get("Content-Encoding"), is(nullValue()));
        }
    }

    @RepeatedTest(3)
    public void canMakeGETRequestsWithChunkedResponses() throws Exception {
        String text = StringUtils.LARGE_TXT;
        try (Response response = call(request(router.uri().resolve("/static/large-txt-file.txt")))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), equalTo(text));
            Map<String, List<String>> headers = response.headers().toMultimap();
            assertThat(headers.get("Content-Type"), contains("text/plain;charset=utf-8"));
            assertThat(headers.get("Content-Length"), is(nullValue()));
            assertThat(headers.get("Transfer-Encoding"), contains("chunked"));
        }
    }


    @RepeatedTest(3)
    public void theRouterObjectReflectsNumberOfIdleConnections(RepetitionInfo repetitionInfo) throws Exception {
        assertEventually(() -> crankerRouter.idleConnectionCount(), is(2));

        Thread.sleep(1000L);
        assertEventually(() -> crankerRouter.idleConnectionCount(), is(2));
        connector2 = BaseEndToEndTest.startConnectorAndWaitForRegistration(crankerRouter, "*", targetServer, preferredProtocols(repetitionInfo), "*", router);
        assertEventually(() -> crankerRouter.idleConnectionCount(), is(4));

        connector2.stop(20, TimeUnit.SECONDS);
        assertEventually(() -> crankerRouter.idleConnectionCount(), is(2));

        call(request(router.uri().resolve("/static/hello.html"))).close();
        assertEventually(() -> crankerRouter.idleConnectionCount(), is(2));
    }

    @RepeatedTest(3)
    public void cantMakeTraceRequests() throws Exception {
        try (Response resp = call(request(router.uri().resolve("/static/hello.html")).method("TRACE", null))) {
            assertThat(resp.code(), is(405));
        }
    }

    @RepeatedTest(3)
    public void cantMakeTraceRequestsOnWebSocketPort() throws Exception {
        try (Response resp = call(request(registrationServer.uri().resolve("/static/hello.html")).method("TRACE", null))) {
            assertThat(resp.code(), is(405));
        }
    }

    @RepeatedTest(3)
    public void invalidRequestsWithBadQueryAreRejected() throws Exception {
        try (RawClient client = RawClient.create(router.httpUri())) {
            client.sendStartLine("GET", "/sw000.asp?|-|0|404_Object_Not_Found")
                .sendHeader("Host", router.httpUri().getAuthority())
                .endHeaders()
                .flushRequest();
            assertEventually(client::responseString, containsString("HTTP/1.1 400 Bad Request"));
        }
    }

    @RepeatedTest(3)
    public void invalidRequestsWithBadPathAreRejected() throws Exception {
        try (RawClient client = RawClient.create(router.httpUri())) {
            client.sendStartLine("GET", "/ca/..\\\\..\\\\..\\\\..\\\\..\\\\..\\\\..\\\\..\\\\winnt/\\\\win.ini")
                .sendHeader("Host", router.httpUri().getAuthority())
                .endHeaders()
                .flushRequest();
            assertEventually(client::responseString, containsString("HTTP/1.1 400 Bad Request"));
        }
    }

    @RepeatedTest(3)
    public void ifTheTargetGZipsThenItComesBackGZipped() throws Exception {
        try (Response response = call(request(router.uri().resolve("/static/large-txt-file.txt"))
            .header("Accept-Encoding", "gzip"))) {
            assertThat(response.code(), is(200));
            String respText = new String(decompress(response.body().bytes()), UTF_8);
            assertEquals(StringUtils.LARGE_TXT, respText);
        }
    }

    @RepeatedTest(3)
    public void headersAreCorrect() throws Exception {
        // based on stuff in https://www.mnot.net/blog/2011/07/11/what_proxies_must_do

        Map<String, List<String>> rh;
        try (Response response = call(request(router.uri().resolve("/echo-headers"))
            .header("Proxy-Authorization", "Blah")
            .header("Proxy-Authenticate", "Yeah")
            .header("Foo", "Yo man")
            .header("Connection", "Foo")
            .header("User-Agent", "the-agent-specified-by-the-client")
        )) {
            rh = response.headers().toMultimap();
        }
        assertThat(rh.containsKey("Proxy-Authorization"), is(false));
        assertThat(rh.containsKey("Proxy-Authenticate"), is(false));
        assertThat(rh.containsKey("Foo"), is(false));
        if (ClientUtils.jdkHttpClientSupportsHeader("host")) {
            assertThat(rh.get("Host"), contains(router.uri().getAuthority()));
        }
        assertThat(rh.get("Forwarded"), hasSize(1));
        assertThat(rh.get("Forwarded").get(0), endsWith(";for=127.0.0.1;host=\"" + router.uri().getAuthority() + "\";proto=https"));
        assertThat(rh.get("X-Forwarded-Proto"), contains("https"));
        assertThat(rh.get("X-Forwarded-For"), contains("127.0.0.1"));
        assertThat(rh.get("X-Forwarded-Host"), contains(router.uri().getAuthority()));
        assertThat(rh.get("User-Agent"), contains("the-agent-specified-by-the-client"));
        if (ClientUtils.jdkHttpClientSupportsHeader("via")) {
            assertThat(rh.get("Via").toString(), rh.get("Via"), contains("HTTP/1.1 muc"));
        }
        assertThat(rh.get("Date"), hasSize(1));
        assertThat(rh.get("Server"), is(nullValue())); // Some say exposing info about the Server is a security risk
    }

}
