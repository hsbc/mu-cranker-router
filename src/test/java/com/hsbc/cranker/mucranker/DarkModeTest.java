package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.CrankerConnector;
import io.muserver.MuServer;
import io.muserver.handlers.ResourceHandlerBuilder;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import scaffolding.StringUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static com.hsbc.cranker.mucranker.BaseEndToEndTest.httpsServerForTest;
import static com.hsbc.cranker.mucranker.BaseEndToEndTest.preferredProtocols;
import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.IsEqual.equalTo;
import static scaffolding.Action.swallowException;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class DarkModeTest {

    private MuServer targetServer;
    private CrankerRouter cranker;
    private DarkModeManager darkModeManager;
    private MuServer crankerServer;
    private CrankerConnector connector;

    @BeforeEach
    void setUp(RepetitionInfo repetitionInfo) {
        targetServer = httpServer()
            .addHandler(ResourceHandlerBuilder.classpathHandler("/web"))
            .start();
        cranker = crankerRouter()
            .withConnectorMaxWaitInMillis(2000)
            .withSupportedCrankerProtocols(List.of("cranker_1.0", "cranker_3.0"))
            .start();
        darkModeManager = cranker.darkModeManager();
        crankerServer = httpsServerForTest()
            .addHandler(cranker.createRegistrationHandler())
            .addHandler(cranker.createHttpHandler())
            .start();
        // cranker V3 protocol not plan to support darkMode
        connector = BaseEndToEndTest.startConnectorAndWaitForRegistration(cranker, "*", targetServer, preferredProtocols(repetitionInfo), "*", crankerServer);
    }

    @AfterEach
    public void stop() {
        swallowException(() -> connector.stop(30, TimeUnit.SECONDS));
        swallowException(targetServer::stop);
        swallowException(crankerServer::stop);
        swallowException(cranker::stop);
    }

    @AfterEach
    public void cleanUpDarkMode() {
        for (DarkHost darkHost : darkModeManager.darkHosts()) {
            darkModeManager.disableDarkMode(darkHost);
        }
    }

    @RepeatedTest(1)
    public void darkModeStopsRequestsGoingToATargetServer() throws Exception {
        darkModeManager.enableDarkMode(darkHost("127.0.0.2")); // does not exist, so nothing blocked by this
        try (Response response = call(request(crankerServer.uri().resolve("/static/hello.html")))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), equalTo(StringUtils.HELLO_HTML));
        }
        darkModeManager.enableDarkMode(darkHost("127.0.0.1"));
        try (Response response = call(request(crankerServer.uri().resolve("/static/hello.html")))) {
            assertThat(response.code(), is(503));
            assertThat(response.body().string(), containsString("503 Service Unavailable"));
        }
        darkModeManager.disableDarkMode(darkHost("127.0.0.1"));
        try (Response response = call(request(crankerServer.uri().resolve("/static/hello.html")))) {
            assertThat(response.code(), is(200));
            assertThat(response.body().string(), equalTo(StringUtils.HELLO_HTML));
        }
    }

    @RepeatedTest(1)
    public void findByIPWorks() throws UnknownHostException {
        InetAddress _127_0_0_2 = InetAddress.getByName("127.0.0.2");
        assertThat(darkModeManager.findHost(_127_0_0_2), is(Optional.empty()));
        darkModeManager.enableDarkMode(darkHost("127.0.0.1"));
        darkModeManager.enableDarkMode(darkHost("127.0.0.2"));
        darkModeManager.enableDarkMode(darkHost("127.0.0.3"));

        Optional<DarkHost> found = darkModeManager.findHost(_127_0_0_2);
        assertThat(found.isPresent(), is(true));
        assertThat(found.get().address(), is(_127_0_0_2));

        darkModeManager.disableDarkMode(darkHost("127.0.0.2"));
        assertThat(darkModeManager.findHost(_127_0_0_2), is(Optional.empty()));
    }

    @RepeatedTest(1)
    public void theDarkHostsAreAvailableToQuery() throws Exception {
        assertThat(darkModeManager.darkHosts(), hasSize(0));
        DarkHost host = darkHost("127.0.0.2");
        darkModeManager.enableDarkMode(host);
        assertThat(darkModeManager.darkHosts(), contains(host));

        darkModeManager.enableDarkMode(host);
        darkModeManager.enableDarkMode(DarkHost.create(InetAddress.getByName("127.0.0.2"), Instant.parse("2019-11-19T03:04:06.329Z"), "ignored"));

        assertThat(darkModeManager.darkHosts(), contains(host));

        DarkHost localhost = darkHost("127.0.0.1");
        darkModeManager.enableDarkMode(localhost);

        assertThat(darkModeManager.darkHosts(), containsInAnyOrder(host, localhost));

        darkModeManager.disableDarkMode(DarkHost.create(InetAddress.getByName("127.0.0.2"), Instant.parse("2019-11-19T04:04:06.329Z"), "Umm, some reason"));

        assertThat(darkModeManager.darkHosts(), contains(localhost));
        darkModeManager.disableDarkMode(host);
        assertThat(darkModeManager.darkHosts(), contains(localhost));
    }

    @NotNull
    private static DarkHost darkHost(String ipAddress) throws UnknownHostException {
        return DarkHost.create(InetAddress.getByName(ipAddress), Instant.now(), null);
    }

}
