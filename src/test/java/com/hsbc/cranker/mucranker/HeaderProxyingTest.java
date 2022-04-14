package com.hsbc.cranker.mucranker;

import io.muserver.*;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Response;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import scaffolding.ClientUtils;
import scaffolding.InMemCookieJar;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class HeaderProxyingTest extends BaseEndToEndTest {

    @Test
    public void viaNameIsSetCorrectly() throws IOException {
        Assume.assumeTrue("This version of the JDK HTTP client does not allow the Via header to be set so skipping test", ClientUtils.jdkHttpClientSupportsHeader("via"));
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write("via: " + request.headers().getAll("via"));
            })
            .start();
        startRouterAndConnector(crankerRouter().withViaName("some-host.name:1234"));
        try (Response resp = call(request(router.uri()))) {
            assertThat(resp.body().string(), is("via: [HTTP/1.1 some-host.name:1234]"));
        }
    }

    @Test
    public void viaNamesMustBeHttpHeaderTokensOrHostsOnly() {
        String[] invalid = {"a space", "\"quoted\"", "whereu@"};
        for (String via : invalid) {
            try {
                crankerRouter().withViaName(via);
                Assert.fail(via + " should not work as a via name");
            } catch (IllegalArgumentException e) {
                // good!
            }
        }
    }

    @Test
    public void multipleCookiesCanBeSentAndReceived() throws IOException {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/make", (request, response, pathParams) -> {
                response.addCookie(CookieBuilder.newSecureCookie().withName("one").withValue("1").build());
                response.addCookie(CookieBuilder.newSecureCookie().withName("two").withValue("2").build());
                response.write("done");
            })
            .addHandler(Method.GET, "/check", (request, response, pathParams) -> {
                response.contentType(ContentTypes.TEXT_PLAIN_UTF8);
                for (io.muserver.Cookie cookie : request.cookies()) {
                    response.sendChunk(cookie.name() + "=" + cookie.value() + "; ");
                }
                response.sendChunk("cookie-header-count: " + request.headers().getAll("cookie").size());
            })
            .start();
        startRouterAndConnector(crankerRouter());
        OkHttpClient client = ClientUtils.client.newBuilder()
            .cookieJar(new InMemCookieJar())
            .build();
        client.newCall(request(router.uri().resolve("/make")).build()).execute().close();
        List<Cookie> cookies = client.cookieJar().loadForRequest(HttpUrl.get(router.uri()));
        assertThat(cookies.size(), is(2));
        assertThat(cookies.get(0).value(), is("1"));
        assertThat(cookies.get(1).value(), is("2"));

        try (Response resp = client.newCall(request(router.uri().resolve("/check")).build()).execute()) {
            assertThat(resp.body().string(), is("one=1; two=2; cookie-header-count: 1"));
        }
        try (Response resp = call(request(router.uri().resolve("/check"))
            .addHeader("cookie", "cookie3=3")
            .addHeader("cookie", "cookie4=4") // if multiple cookie headers exist, they should be combined into a single cookie header as per https://tools.ietf.org/html/rfc7540#section-8.1.2.5
        )) {
            assertThat(resp.body().string(), is("cookie3=3; cookie4=4; cookie-header-count: 1"));
        }
    }

    @Test
    public void forwardedHeadersSentFromTheClientCanBeDiscarded() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                Headers h = request.headers();
                response.write(h.get("X-Forwarded-Proto") + " " + h.get("X-Forwarded-Host") + " " + h.get("X-Forwarded-For") + " " + h.forwarded().size());
            })
            .start();

        startRouterAndConnector(crankerRouter().withDiscardClientForwardedHeaders(true));
        try (Response resp = call(request(router.uri())
            .header("Forwarded", new ForwardedHeader("125.0.0.0", "126.0.0.0", "forwarded.example.org", "http", null).toString())
            .header("X-Forwarded-Proto", "http")
            .header("X-Forwarded-Host", "example.org")
            .header("X-Forwarded-For", "123.0.0.0")
        )) {
            assertThat(resp.body().string(), is("null null null 1"));
        }
    }

    @Test
    public void hostIsProxiedByForwardHeader() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                // forwarded:by=10.0.0.4;for=127.0.0.1;host=example.org;proto=https
                final String forwarded = request.headers().get("forwarded");
                final Optional<String> firstHost = Stream
                    .of(forwarded.split(";"))
                    .filter(line -> line.contains("host="))
                    .map(line -> line.replace("host=", ""))
                    .findFirst();
                response.write(firstHost.orElse(""));
            })
            .start();
        startRouterAndConnector(crankerRouter());
        try (Response resp = call(request(router.uri()).header("Host", "example.org"))) {
            assertThat(resp.body().string(), is("example.org"));
        }
    }

    @Test
    public void hostIsProxiedByForwardHeaderAndLegacyForwardedHeaders() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                // forwarded:by=10.0.0.4;for=127.0.0.1;host=example.org;proto=https
                response.write(
                    String.join("\n",
                        String.format("forward:%s", request.headers().get("forwarded")),
                        String.format("x-forwarded-host:%s", request.headers().get("x-forwarded-host")),
                        String.format("host:%s", request.headers().get("host"))
                    )
                );
            })
            .start();
        startRouterAndConnector(crankerRouter().proxyHostHeader(false).withSendLegacyForwardedHeaders(true));
        try (Response resp = call(request(router.uri()).header("Host", "example.org"))) {
            final String[] split = resp.body().string().split("\n");
            // forward:by=10.0.0.10;for=127.0.0.1;host=example.org;proto=https
            assertThat(split[0], containsString("host=example.org;proto=https"));
            // x-forwarded-host:example.org
            assertThat(split[1], is("x-forwarded-host:example.org"));
            // host:localhost:56492
            assertThat(split[2], containsString("host:localhost:"));
        }
    }


    @Test
    public void hostIsProxiedByForwardHeaderAndLegacyForwardedHeaders_IfForwardHeaderAlreadyExist() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                // forwarded:by=10.0.0.4;for=127.0.0.1;host=example.org;proto=https
                response.write(
                    String.join("\n",
                        String.format("forward:[%s]", String.join(",", request.headers().getAll("forwarded"))),
                        String.format("x-forwarded-host:%s", request.headers().get("x-forwarded-host")),
                        String.format("host:%s", request.headers().get("host"))
                    )
                );
            })
            .start();

        final CrankerRouterBuilder crankerBuilder = crankerRouter()
            .withDiscardClientForwardedHeaders(false)
            .proxyHostHeader(false)
            .withSendLegacyForwardedHeaders(true);
        startRouterAndConnector(crankerBuilder);

        try (Response resp = call( request(router.uri())
            .header("Host", "example.org")
            .header("Forwarded", "for=originating.example.org"))) {
            final String[] split = resp.body().string().split("\n");

            // x-forwarded-host:null
            assertThat(split[1], is("x-forwarded-host:null"));
            // host:localhost:56492
            assertThat(split[2], containsString("host:localhost:"));
            // forward:[for=originating.example.org,by=10.0.0.10;for=127.0.0.1;host=example.org;proto=https]
            assertThat(split[0], containsString("host=example.org;proto=https"));
            assertThat(split[0], containsString(",")); // size is 2
        }
    }



    @Test
    public void proxyingOfHostHeaderCanBeTurnedOff() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                response.write(request.headers().get("host"));
            })
            .start();
        startRouterAndConnector(crankerRouter().proxyHostHeader(false));
        try (Response resp = call(request(router.uri())
            .header("Host", "example.org")
        )) {
            assertThat(resp.body().string(), is(targetServer.uri().getAuthority()));
        }
    }

    @Test
    public void legacyForwardedHeadersAreNotSentByDefault() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                Headers h = request.headers();
                response.write(h.get("X-Forwarded-Proto") + " " + h.get("X-Forwarded-Host") + " " + h.get("X-Forwarded-For") + " " + h.forwarded().size());
            })
            .start();
        startRouterAndConnector(crankerRouter());
        try (Response resp = call(request(router.uri()))) {
            assertThat(resp.body().string(), is("null null null 1"));
        }
    }

    @Test
    public void legacyForwardedHeadersCanBeSent() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                Headers h = request.headers();
                response.write(h.get("X-Forwarded-Proto") + " " + h.get("X-Forwarded-Host") + " " + h.get("X-Forwarded-For") + " " + h.forwarded().size());
            })
            .start();
        startRouterAndConnector(crankerRouter().withSendLegacyForwardedHeaders(true));
        try (Response resp = call(request(router.uri()))) {
            assertThat(resp.body().string(), is("https " + router.uri().getAuthority() + " 127.0.0.1 1"));
        }
    }

    @Test
    public void legacyForwardedHeadersCanBeSentAlsoWhenSomeProvidedByRequester() throws Exception {
        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> {
                Headers h = request.headers();
                response.write(h.get("X-Forwarded-Proto") + " " + h.get("X-Forwarded-Host") + " " + h.get("X-Forwarded-For") + " " + h.forwarded().size() + " " + h.forwarded().get(0).forValue());
            })
            .start();
        startRouterAndConnector(crankerRouter().withSendLegacyForwardedHeaders(true));
        try (Response resp = call(request(router.uri())
            // only one of the x-forwarded-* headers provided by requester
            .header("x-forwarded-for", "123.0.0.0"))) {
            assertThat(resp.body().string(), is("null null 123.0.0.0 2 123.0.0.0"));
        }
    }

}
