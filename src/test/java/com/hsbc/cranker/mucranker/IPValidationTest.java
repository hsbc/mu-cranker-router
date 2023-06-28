package com.hsbc.cranker.mucranker;

import io.muserver.Method;
import okhttp3.Response;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class IPValidationTest extends BaseEndToEndTest {

    @RepeatedTest(3)
    public void registrationServerCanHaveIPWhiteListing(RepetitionInfo repetitionInfo) {

        AtomicBoolean allowThem = new AtomicBoolean(false);

        this.targetServer = httpServer()
            .addHandler(Method.GET, "/", (request, response, pathParams) -> response.write("hello"))
            .start();
        this.crankerRouter = crankerRouter()
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .withRegistrationIpValidator(ip -> allowThem.get())
            .withConnectorMaxWaitInMillis(400)
            .start();
        this.router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();
        this.connector = startConnector("*", "*", preferredProtocols(repetitionInfo), targetServer, router);
        try (Response resp = call(request(router.uri()))) {
            assertThat(resp.code(), is(404));
        }

        allowThem.set(true);
        waitForRegistration("*", "*", 2, new CrankerRouter[]{crankerRouter});

        try (Response resp = call(request(router.uri()))) {
            assertThat(resp.code(), is(200));
        }
    }

}
