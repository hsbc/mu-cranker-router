package com.hsbc.cranker.mucranker;

import io.muserver.Http2ConfigBuilder;
import io.muserver.Method;
import io.muserver.SsePublisher;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Test;
import scaffolding.SseTestClient;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static scaffolding.Action.swallowException;

public class ServerSentEventTest extends BaseEndToEndTest {

    private SseTestClient client;

    @AfterEach
    public void after() {
        if (client != null) swallowException(client::stop);
    }

    @RepeatedTest(3)
    public void MuServer_NormalSseTest(RepetitionInfo repetitionInfo) throws Exception {

        this.targetServer = httpServer()
            .addHandler(Method.GET, "/sse/counter", (request, response, pathParams) -> {
                SsePublisher publisher = SsePublisher.start(request, response);
                publisher.send("Number 0");
                publisher.send("Number 1");
                publisher.send("Number 2");
                publisher.close();
            })
            .start();

        this.crankerRouter = crankerRouter()
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .withConnectorMaxWaitInMillis(400).start();

        this.router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .withHttp2Config(Http2ConfigBuilder.http2Config().enabled(false))
            .start();

        this.connector = startConnectorAndWaitForRegistration(crankerRouter, "*", targetServer, preferredProtocols(repetitionInfo), "*", router);

        this.client = SseTestClient.startSse(targetServer.uri().resolve("/sse/counter"));
        this.client.waitUntilClose(5, TimeUnit.SECONDS);

        assertThat(this.client.getMessages(), contains(
            "onOpen:",
            "onEvent: id=null, type=null, data=Number 0",
            "onEvent: id=null, type=null, data=Number 1",
            "onEvent: id=null, type=null, data=Number 2",
            "onClosed:"
        ));
    }

    @Test
    public void MuServer_TargetServerDownInMiddleTest_ClientTalkToTargetServer() throws Exception {

        this.targetServer = httpServer()
            .addHandler(Method.GET, "/sse/counter", (request, response, pathParams) -> {
                SsePublisher publisher = SsePublisher.start(request, response);
                publisher.send("Number 0");
                publisher.send("Number 1");
                publisher.send("Number 2");
                targetServer.stop();
            })
            .start();

        this.client = SseTestClient.startSse(targetServer.uri().resolve("/sse/counter"));
        this.client.waitUntilError(5, TimeUnit.SECONDS);

        assertThat(this.client.getMessages(), contains(
            "onOpen:",
            "onEvent: id=null, type=null, data=Number 0",
            "onEvent: id=null, type=null, data=Number 1",
            "onEvent: id=null, type=null, data=Number 2",
            "onFailure: message=null"
        ));
    }

    @RepeatedTest(3)
    public void MuServer_TargetServerDownInMiddleTest_ClientTalkToRouter(RepetitionInfo repetitionInfo) throws Exception {

        this.targetServer = httpServer()
            .addHandler(Method.GET, "/sse/counter", (request, response, pathParams) -> {
                SsePublisher publisher = SsePublisher.start(request, response);
                publisher.send("Number 0");
                publisher.send("Number 1");
                publisher.send("Number 2");
                client.waitMessageListSizeGreaterThan(3, 10, TimeUnit.SECONDS);
                targetServer.stop();
            })
            .start();


        this.crankerRouter = crankerRouter()
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .withConnectorMaxWaitInMillis(400).start();

        this.router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .withHttp2Config(Http2ConfigBuilder.http2Config().enabled(false))
            .start();

        this.connector = startConnectorAndWaitForRegistration(crankerRouter, "*", targetServer, preferredProtocols(repetitionInfo), "*", router);

        this.client = SseTestClient.startSse(router.uri().resolve("/sse/counter"));
        this.client.waitUntilError(100, TimeUnit.SECONDS);

        assertThat(this.client.getMessages(), contains(
            "onOpen:",
            "onEvent: id=null, type=null, data=Number 0",
            "onEvent: id=null, type=null, data=Number 1",
            "onEvent: id=null, type=null, data=Number 2",
            "onFailure: message=null"
        ));
    }

}
