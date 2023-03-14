package com.hsbc.cranker.mucranker;

import com.hsbc.cranker.connector.*;
import io.muserver.MuServer;
import io.muserver.MuServerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static io.muserver.MuServerBuilder.httpsServer;
import static java.util.stream.Collectors.toList;
import static scaffolding.Action.swallowException;

public abstract class BaseEndToEndTest {

    static {
        System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");
    }

    private static final Logger log = LoggerFactory.getLogger(BaseEndToEndTest.class);

    MuServer targetServer;
    protected CrankerRouter crankerRouter;
    protected MuServer router;
    protected CrankerConnector connector;

    void startRouterAndConnector(CrankerRouterBuilder crankerRouterBuilder) {
        this.crankerRouter = crankerRouterBuilder.start();
        this.router = httpsServerForTest()
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler(crankerRouter.createHttpHandler())
            .start();

        if (targetServer != null) {
            this.connector = startConnectorAndWaitForRegistration(crankerRouter, "*", targetServer, router);
        }
    }

    public static CrankerConnector startConnectorAndWaitForRegistration(CrankerRouter crankerRouter, String targetServiceName, MuServer target, MuServer... registrationRouters) {
        CrankerConnector connector = startConnector(targetServiceName, target, registrationRouters);
        waitForRegistration(targetServiceName, crankerRouter);
        return connector;
    }

    public static void waitForRegistration(String targetServiceName, CrankerRouter... crankerRouters) {
        int attempts = 0;
        for (CrankerRouter crankerRouter : crankerRouters) {
            while (!crankerRouter.collectInfo().toMap().containsKey(targetServiceName.isEmpty() ? "*" : targetServiceName)) {
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if (attempts++ == 100) throw new RuntimeException("Failed to register " + targetServiceName);
            }
        }
    }

    public static CrankerConnector startConnector(String targetServiceName, MuServer target, MuServer... registrationRouters) {
        List<URI> uris = Stream.of(registrationRouters)
            .map(s -> URI.create("ws" + s.uri().toString().substring(4)))
            .collect(toList());

        return CrankerConnectorBuilder.connector()
            .withHttpClient(CrankerConnectorBuilder.createHttpClient(true).build())
            .withRouterUris(RegistrationUriSuppliers.fixedUris(uris))
            .withComponentName("junit")
            .withRoute(targetServiceName)
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

    @AfterEach
    public void stop() {
        if (connector != null) swallowException(() -> connector.stop(10, TimeUnit.SECONDS));
        if (targetServer != null) swallowException(targetServer::stop);
        if (crankerRouter != null) swallowException(crankerRouter::stop);
        if (router != null) swallowException(router::stop);
    }

}
