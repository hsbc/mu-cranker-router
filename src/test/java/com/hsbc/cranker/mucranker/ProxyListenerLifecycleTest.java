package com.hsbc.cranker.mucranker;

import io.muserver.Headers;
import io.muserver.MuHandler;
import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.MuServer;
import jakarta.ws.rs.WebApplicationException;
import okhttp3.Response;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Timeout(30)
class ProxyListenerLifecycleTest extends BaseEndToEndTest {
    private static final String STATE = "test.proxy-request-state";

    @ParameterizedTest
    @ValueSource(strings = {"cranker_1.0", "cranker_3.0"})
    void receivesRequestBeforeAsyncAndAcquisitionOnOriginalThread(String protocol) throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        AtomicReference<Thread> handlerThread = new AtomicReference<>();
        AtomicReference<MuRequest> receivedRequest = new AtomicReference<>();
        CountDownLatch handlerReturned = new CountDownLatch(1);
        CountDownLatch acquisitionDeferred = new CountDownLatch(1);
        CountDownLatch releaseAcquisition = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(1);
        Object state = new Object();
        RouteResolver resolver = new RouteResolver() {
            @Override
            public String resolve(Set<String> routes, String target) {
                assertNotNull(receivedRequest.get(), "route selection preceded the early hook");
                assertTrue(receivedRequest.get().isAsync(), "route selection preceded async handling");
                if (Thread.currentThread() != handlerThread.get()) {
                    acquisitionDeferred.countDown();
                    await(releaseAcquisition);
                }
                return RouteResolver.super.resolve(routes, target);
            }
        };
        ProxyListener first = new ProxyListener() {
            @Override
            public void onRequestReceived(MuRequest request, MuResponse response) {
                assertEquals(List.of("handler"), events);
                assertSame(handlerThread.get(), Thread.currentThread());
                assertFalse(request.isAsync(), "early hook ran after async handling began");
                request.attribute(STATE, state);
                receivedRequest.set(request);
                events.add("received-first");
            }

            @Override
            public void onBeforeProxyToTarget(ProxyInfo info, Headers headers) {
                acquisitionDeferred.countDown();
                await(releaseAcquisition);
                assertSame(receivedRequest.get(), info.request());
                assertSame(state, info.request().attribute(STATE));
                assertTrue(info.request().isAsync());
                assertNotSame(handlerThread.get(), Thread.currentThread());
                assertEquals(0, handlerReturned.getCount(), "dispatch was not deferred");
                events.add("before-first");
                headers.set("X-Captured-State", "available");
            }

            @Override
            public void onComplete(ProxyInfo info) {
                assertSame(state, info.request().attribute(STATE));
                events.add("complete");
                completed.countDown();
            }
        };
        ProxyListener second = new ProxyListener() {
            @Override
            public void onRequestReceived(MuRequest request, MuResponse response) {
                assertSame(state, request.attribute(STATE));
                assertFalse(request.isAsync(), "early hook ran after async handling began");
                events.add("received-second");
            }

            @Override
            public void onBeforeProxyToTarget(ProxyInfo info, Headers headers) {
                assertEquals("available", headers.get("X-Captured-State"));
                events.add("before-second");
            }
        };
        targetServer = httpServer().withInterface("127.0.0.1")
            .addHandler((request, response) -> {
                response.write(request.headers().get("X-Captured-State"));
                return true;
            }).start();
        crankerRouter = crankerRouter().withSupportedCrankerProtocols(List.of(protocol))
            .withRouteResolver(resolver).addProxyListener(null, first).addProxyListener(null, second).start();
        MuHandler proxy = crankerRouter.createHttpHandler();
        router = httpServer().withInterface("127.0.0.1")
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler((request, response) -> {
                handlerThread.set(Thread.currentThread());
                events.add("handler");
                try {
                    return proxy.handle(request, response);
                } finally {
                    handlerReturned.countDown();
                }
            }).start();
        connector = startConnectorAndWaitForRegistration(crankerRouter, "*", targetServer, List.of(protocol), "*", router);
        assertTrue(events.isEmpty(), "connector registration must not invoke the early hook");
        ExecutorService client = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<String> result = CompletableFuture.supplyAsync(() -> {
                try (Response response = call(request(router.uri()))) {
                    assertEquals(200, response.code());
                    return response.body().string();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, client);
            await(acquisitionDeferred);
            await(handlerReturned);
            assertEquals(List.of("handler", "received-first", "received-second"), events);
            assertTrue(receivedRequest.get().isAsync(), "request must be async after the handler returns");
            releaseAcquisition.countDown();
            assertEquals("available", result.get(10, TimeUnit.SECONDS));
            await(completed);
            assertEquals(1, events.stream().filter("received-first"::equals).count());
            assertTrue(events.indexOf("before-first") < events.indexOf("before-second"));
        } finally {
            releaseAcquisition.countDown();
            client.shutdownNow();
            assertTrue(client.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cranker_1.0", "cranker_3.0"})
    void concurrentRequestsKeepCapturedStateIsolated(String protocol) throws Exception {
        int count = 4;
        CountDownLatch received = new CountDownLatch(count);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch completed = new CountDownLatch(count);
        AtomicInteger earlyCalls = new AtomicInteger();
        List<String> completedIds = new CopyOnWriteArrayList<>();
        targetServer = httpServer().withInterface("127.0.0.1").addHandler((request, response) -> {
            response.write(request.headers().get("X-Request-State"));
            return true;
        }).start();
        startRouterAndConnector(crankerRouter().withSupportedCrankerProtocols(List.of(protocol))
            .addProxyListener(null, new ProxyListener() {
                @Override
                public void onRequestReceived(MuRequest request, MuResponse response) {
                    request.attribute(STATE, request.query().get("id"));
                    earlyCalls.incrementAndGet();
                    received.countDown();
                    await(release);
                }

                @Override
                public void onBeforeProxyToTarget(ProxyInfo info, Headers headers) {
                    String id = (String) info.request().attribute(STATE);
                    assertEquals(info.request().query().get("id"), id);
                    headers.set("X-Request-State", id);
                }

                @Override
                public void onComplete(ProxyInfo info) {
                    completedIds.add((String) info.request().attribute(STATE));
                    completed.countDown();
                }
            }), List.of(protocol));
        ExecutorService clients = Executors.newFixedThreadPool(count);
        try {
            List<CompletableFuture<String>> results = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                String id = "request-" + i;
                results.add(CompletableFuture.supplyAsync(() -> {
                    try (Response response = call(request(router.uri().resolve("/?id=" + id)))) {
                        assertEquals(200, response.code());
                        return response.body().string();
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }, clients));
            }
            await(received);
            release.countDown();
            for (int i = 0; i < count; i++) {
                assertEquals("request-" + i, results.get(i).get(10, TimeUnit.SECONDS));
            }
            await(completed);
            assertEquals(count, earlyCalls.get());
            assertEquals(Set.of("request-0", "request-1", "request-2", "request-3"), Set.copyOf(completedIds));
            assertEquals(count, completedIds.size());
        } finally {
            release.countDown();
            clients.shutdownNow();
            assertTrue(clients.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @CsvSource({
        "cranker_1.0, web", "cranker_3.0, web",
        "cranker_1.0, runtime", "cranker_3.0, runtime"
    })
    void earlyFailureUsesServerExceptionHandlingWithoutDispatch(String protocol, String kind) throws Exception {
        RuntimeException failure = "web".equals(kind) ? new WebApplicationException("rejected", 409)
            : new IllegalStateException("private diagnostic");
        AtomicInteger routeSelections = new AtomicInteger();
        AtomicInteger targetHits = new AtomicInteger();
        AtomicInteger earlyCalls = new AtomicInteger();
        AtomicInteger laterCalls = new AtomicInteger();
        targetServer = httpServer().withInterface("127.0.0.1").addHandler((request, response) -> {
            targetHits.incrementAndGet();
            response.write("must not be called");
            return true;
        }).start();
        startRouterAndConnector(crankerRouter().withSupportedCrankerProtocols(List.of(protocol))
            .withRouteResolver(new RouteResolver() {
                @Override
                public String resolve(Set<String> routes, String target) {
                    routeSelections.incrementAndGet();
                    return RouteResolver.super.resolve(routes, target);
                }
            })
            .addProxyListener(null, new ProxyListener() {
                @Override
                public void onRequestReceived(MuRequest request, MuResponse response) {
                    earlyCalls.incrementAndGet();
                    assertFalse(request.isAsync());
                    throw failure;
                }
            })
            .addProxyListener(null, new ProxyListener() {
                @Override
                public void onRequestReceived(MuRequest request, MuResponse response) {
                    laterCalls.incrementAndGet();
                }

                @Override
                public void onBeforeProxyToTarget(ProxyInfo info, Headers headers) {
                    laterCalls.incrementAndGet();
                }

                @Override
                public void onFailureToAcquireProxySocket(ProxyInfo info) {
                    laterCalls.incrementAndGet();
                }

                @Override
                public void onComplete(ProxyInfo info) {
                    laterCalls.incrementAndGet();
                }
            }), List.of(protocol));
        try (Response response = call(request(router.uri()))) {
            assertEquals("web".equals(kind) ? 409 : 500, response.code());
            response.body().string();
        }
        assertEquals(1, earlyCalls.get());
        assertEquals(0, laterCalls.get());
        assertEquals(0, routeSelections.get());
        assertEquals(0, targetHits.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cranker_1.0", "cranker_3.0"})
    void shortCircuitsAndRegistrationFallThroughDoNotCaptureButMissingRoutesDo(String protocol) throws Exception {
        AtomicInteger earlyCalls = new AtomicInteger();
        AtomicInteger afterProxyCalls = new AtomicInteger();
        AtomicReference<ProxyInfo> failure = new AtomicReference<>();
        CountDownLatch failed = new CountDownLatch(1);
        crankerRouter = crankerRouter().withSupportedCrankerProtocols(List.of(protocol)).addProxyListener(null, new ProxyListener() {
            @Override
            public void onRequestReceived(MuRequest request, MuResponse response) {
                earlyCalls.incrementAndGet();
                request.attribute(STATE, "missing");
            }

            @Override
            public void onFailureToAcquireProxySocket(ProxyInfo info) {
                failure.set(info);
                failed.countDown();
            }
        }).start();
        router = httpServer().withInterface("127.0.0.1")
            .addHandler((request, response) -> {
                if (!request.uri().getPath().equals("/health")) return false;
                response.status(204);
                return true;
            })
            .addHandler(crankerRouter.createHttpHandler())
            .addHandler((request, response) -> {
                afterProxyCalls.incrementAndGet();
                return true;
            }).start();
        MuServer registration = httpServer().withInterface("127.0.0.1")
            .addHandler(crankerRouter.createRegistrationHandler())
            .addHandler((request, response) -> {
                response.status(204);
                return true;
            }).start();
        try {
            try (Response response = call(request(registration.uri().resolve("/not-registration")))) {
                assertEquals(204, response.code());
            }
            try (Response response = call(request(registration.uri().resolve("/register")))) {
                assertNotEquals(200, response.code());
            }
        } finally {
            registration.stop();
        }
        try (Response response = call(request(router.uri().resolve("/health")))) {
            assertEquals(204, response.code());
        }
        try (Response response = call(request(router.uri()).method("TRACE", null))) {
            assertEquals(405, response.code());
            assertEquals("Method not supported", response.body().string());
        }
        assertEquals(0, earlyCalls.get());
        try (Response response = call(request(router.uri().resolve("/missing")))) {
            assertEquals(404, response.code());
            response.body().string();
        }
        await(failed);
        assertEquals(1, earlyCalls.get());
        assertEquals(0, afterProxyCalls.get());
        assertEquals("missing", failure.get().request().attribute(STATE));
        assertNull(failure.get().errorIfAny());
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS), "timed out awaiting lifecycle event");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
