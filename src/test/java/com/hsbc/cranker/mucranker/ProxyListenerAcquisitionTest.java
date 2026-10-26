package com.hsbc.cranker.mucranker;

import io.muserver.Headers;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static io.muserver.MuServerBuilder.httpServer;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Timeout(30)
class ProxyListenerAcquisitionTest {
    @ParameterizedTest
    @ValueSource(strings = {"v1-timeout", "v3-empty", "v3-error", "v3-removed"})
    void capturesOnceWhenAcquisitionFails(String scenario) throws Exception {
        AtomicInteger received = new AtomicInteger();
        AtomicInteger dispatched = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();
        AtomicInteger failureNotifications = new AtomicInteger();
        AtomicReference<ProxyInfo> failure = new AtomicReference<>();
        AtomicReference<MuRequest> capturedRequest = new AtomicReference<>();
        CountDownLatch notified = new CountDownLatch(1);
        CountDownLatch acquiring = new CountDownLatch(1);
        CountDownLatch handlerReturned = new CountDownLatch(1);
        CompletableFuture<RouterSocketV3> selected = new CompletableFuture<>();
        RouteResolver resolver = new RouteResolver() {};
        WebSocketFarm v1 = new WebSocketFarm(resolver, 50);
        v1.getSockets().put("service", new ConcurrentLinkedQueue<>());
        WebSocketFarmV3 v3 = new WebSocketFarmV3(resolver) {
            @Override
            public CompletableFuture<RouterSocketV3> getWebSocket(String target, boolean useCatchAll) {
                assertEquals(1, received.get());
                acquiring.countDown();
                return selected;
            }
        };
        WebSocketFarmV3Holder holder = new WebSocketFarmV3Holder(resolver) {
            @Override
            public boolean canHandle(String domain, String target, boolean useCatchAll) {
                assertEquals(1, received.get());
                return !scenario.equals("v1-timeout");
            }

            @Override
            public WebSocketFarmV3 getWebSocketFarmV3(String domain) {
                return scenario.equals("v3-removed") ? null : v3;
            }
        };
        ProxyListener listener = new ProxyListener() {
            @Override
            public void onRequestReceived(MuRequest request, MuResponse response) {
                assertSame(request.attribute("test.handler-thread"), Thread.currentThread());
                received.incrementAndGet();
                request.attribute("test.capture", scenario);
                capturedRequest.set(request);
            }

            @Override
            public void onBeforeProxyToTarget(ProxyInfo info, Headers headers) {
                dispatched.incrementAndGet();
            }

            @Override
            public void onComplete(ProxyInfo info) {
                completed.incrementAndGet();
            }

            @Override
            public void onFailureToAcquireProxySocket(ProxyInfo info) {
                failureNotifications.incrementAndGet();
                failure.set(info);
            }
        };
        ProxyListener observer = new ProxyListener() {
            @Override
            public void onFailureToAcquireProxySocket(ProxyInfo info) {
                assertSame(failure.get(), info);
                notified.countDown();
            }
        };
        CrankerMuHandler handler = new CrankerMuHandler(v1, holder, false, false, "muc",
            CrankerMuHandler.REPRESSED, List.of(listener, observer));
        MuServer server = null;
        ExecutorService client = Executors.newSingleThreadExecutor();
        v1.start();
        try {
            server = httpServer().withInterface("127.0.0.1").addHandler((request, response) -> {
                request.attribute("test.handler-thread", Thread.currentThread());
                try {
                    return handler.handle(request, response);
                } finally {
                    handlerReturned.countDown();
                }
            }).start();
            URI uri = server.uri().resolve("/service/path");
            CompletableFuture<Integer> responseStatus = CompletableFuture.supplyAsync(() -> {
                try (Response response = call(request(uri))) {
                    response.body().string();
                    return response.code();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, client);
            if (scenario.equals("v3-empty") || scenario.equals("v3-error")) {
                assertTrue(acquiring.await(10, TimeUnit.SECONDS));
                assertEquals(1, received.get());
                if (scenario.equals("v3-error")) {
                    selected.completeExceptionally(new IllegalStateException("connector disappeared"));
                } else {
                    selected.complete(null);
                }
            }
            assertEquals(503, responseStatus.get(10, TimeUnit.SECONDS));
            assertTrue(handlerReturned.await(10, TimeUnit.SECONDS));
            assertEquals(1, received.get());
            assertEquals(0, dispatched.get());
            assertEquals(0, completed.get());
            assertEquals(scenario, capturedRequest.get().attribute("test.capture"));
            if (scenario.equals("v3-removed")) {
                assertEquals(0, failureNotifications.get(), "missing V3 farms retain their original notification behaviour");
                assertNull(failure.get());
            } else {
                assertTrue(notified.await(10, TimeUnit.SECONDS));
                assertEquals(1, failureNotifications.get());
                assertSame(capturedRequest.get(), failure.get().request());
                assertEquals(scenario, failure.get().request().attribute("test.capture"));
                assertNull(failure.get().errorIfAny());
                assertNull(failure.get().connectorInstanceID());
            }
            if (scenario.equals("v1-timeout")) {
                assertTrue(failure.get().socketWaitInMillis() >= 50);
            }
        } finally {
            selected.complete(null);
            client.shutdownNow();
            if (server != null) server.stop();
            v1.stop();
            v3.stop();
            holder.stop();
            assertTrue(client.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
