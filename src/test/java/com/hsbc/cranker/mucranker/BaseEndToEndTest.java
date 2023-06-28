package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.*;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import okhttp3.Response;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepetitionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import scaffolding.AssertUtils;

import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.hsbc.cranker.connector.CrankerConnectorBuilder.CRANKER_PROTOCOL_1;
import static com.hsbc.cranker.connector.CrankerConnectorBuilder.CRANKER_PROTOCOL_3;
import static io.muserver.MuServerBuilder.httpsServer;
import static java.util.stream.Collectors.toList;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.Action.swallowException;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public abstract class BaseEndToEndTest {

    static {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    private static final Logger log = LoggerFactory.getLogger(BaseEndToEndTest.class);

    MuServer targetServer;
    protected CrankerRouter crankerRouter;
    protected MuServer router;
    protected CrankerConnector connector;

    @AfterEach
    public void stop() {
        if (connector != null) swallowException(() -> connector.stop(10, TimeUnit.SECONDS));
        if (targetServer != null) swallowException(targetServer::stop);
        if (crankerRouter != null) swallowException(crankerRouter::stop);
        if (router != null) swallowException(router::stop);
    }

    void startRouterAndConnector(CrankerRouterBuilder crankerRouterBuilder, List<String> preferredProtocols) {
        this.crankerRouter = crankerRouterBuilder.start();
        this.router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();

        if (targetServer != null) {
            this.connector = startConnectorAndWaitForRegistration(crankerRouter, "*", targetServer, preferredProtocols, "*", router);
        }
    }

    public static CrankerConnector startConnectorAndWaitForRegistration(CrankerRouter crankerRouter, String domain, MuServer target, List<String> preferredProtocols, String route, MuServer... registrationRouters) {
        CrankerConnector connector = startConnector(domain, route, preferredProtocols, target, registrationRouters);
        waitForRegistration(route, connector.connectorId(), 2, new CrankerRouter[]{crankerRouter});
        return connector;
    }

    public static void waitForRegistration(String targetServiceName, String connectorInstanceId, int slidingWindow, CrankerRouter[] crankerRouters) {
        final String serviceKey = targetServiceName.isEmpty() ? "*" : targetServiceName;
        for (CrankerRouter crankerRouter : crankerRouters) {
            AssertUtils.assertEventually(() -> {
                final List<ConnectorInstance> matchedConnectors = crankerRouter.collectInfo().services()
                    .stream()
                    .filter(service -> service.route().equals(serviceKey))
                    .filter(service -> service.connectors().size() > 0)
                    .flatMap(service -> service.connectors().stream())
                    .filter(connector -> {
                        if ("*".equals(connectorInstanceId)) {
                            return true;
                        } else {
                            return connector.connectorInstanceID().equals(connectorInstanceId);
                        }
                    })
                    .collect(Collectors.toUnmodifiableList());
                return matchedConnectors.size() > 0 && matchedConnectors
                    .stream()
                    .allMatch(connector1 -> connector1.connections().size() >= slidingWindow);
            }, is(true));
        }
    }

    @NotNull
    public static CrankerConnector startConnector(String domain, String route, List<String> preferredProtocols, MuServer target, MuServer... registrationRouters) {
        List<URI> uris = Stream.of(registrationRouters)
            .map(s -> URI.create("ws" + s.uri().toString().substring(4)))
            .collect(toList());

        return CrankerConnectorBuilder.connector()
            .withPreferredProtocols(preferredProtocols)
            .withHttpClient(CrankerConnectorBuilder.createHttpClient(true).build())
            .withDomain(domain)
            .withRouterUris(RegistrationUriSuppliers.fixedUris(uris))
            .withComponentName("junit")
            .withRoute(route)
            .withTarget(target.uri())
            .withRouterRegistrationListener(new RouterEventListener() {
                public void onRegistrationChanged(ChangeData data) {
                    log.debug("Router registration changed: " + data);
                }

                public void onSocketConnectionError(RouterRegistration router1, Throwable exception) {
                    log.debug("Error connecting to " + router1, exception);
                }
            })
            .start();
    }

    public static MuServerBuilder httpsServerForTest() {
        return httpsServer();
    }

    public static List<String> preferredProtocols(RepetitionInfo repetitionInfo) {
        final int currentRepetition = repetitionInfo.getCurrentRepetition();
        switch (currentRepetition) {
            case 1:
                return List.of(CRANKER_PROTOCOL_1);
            case 2:
                return List.of(CRANKER_PROTOCOL_3);
            default:
                return List.of(CRANKER_PROTOCOL_3, CRANKER_PROTOCOL_1);
        }
    }

    public static HashMap<String, AtomicInteger> callAndGroupByBody(URI uri, int count) {
        return callAndGroupByBody(uri, count, 0);
    }

    public static HashMap<String, AtomicInteger> callAndGroupByBody(URI uri, int count, long sleep) {
        var localhostCallResult = new HashMap<String, AtomicInteger>();
        var total = new AtomicInteger(0);
        for (int i = 0; i < count; i++) {
            try (Response resp = call(request(uri))) {
                assertThat(resp.code(), is(200));
                assert resp.body() != null;
                final String key = resp.body().string();
                localhostCallResult.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
                total.incrementAndGet();
                if (sleep > 0) {
                    Thread.sleep(sleep);
                }
            } catch (IOException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        assert total.get() == count;
        return localhostCallResult;
    }
}
