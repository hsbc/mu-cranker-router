package com.hsbc.cranker.mucranker;

import io.muserver.HeaderNames;
import io.muserver.MuResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static com.hsbc.cranker.mucranker.WebSocketFarm.ThrowingFunction.logIfFail;

class WebSocketFarmV3 {

    private static final Logger log = LoggerFactory.getLogger(WebSocketFarmV3.class);

    private final AtomicInteger idleCount = new AtomicInteger(0);
    private final RouteResolver routeResolver;
    private final Map<String, List<RouterSocketV3>> sockets = new ConcurrentHashMap<>();
    private final Map<String, Long> routeLastRemovalTimes = new ConcurrentHashMap<>();
    private final Map<String, Integer> indexMap = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable ->
        new Thread(runnable, "websocket-farm-v3-execution"));

    public WebSocketFarmV3(RouteResolver routeResolver) {
        this.routeResolver = routeResolver;
    }

    public void start() {
    }

    public void stop() {
        executor.shutdown();
        for (List<RouterSocketV3> sockets : sockets.values()) {
            for (RouterSocketV3 RouterSocketV3 : sockets) {
                RouterSocketV3.socketSessionClose();
            }
        }
        sockets.clear();
    }

    public void cleanRoutes(long routesKeepTimeMillis) {
        final long cutoffTime = System.currentTimeMillis() - routesKeepTimeMillis;
        this.sockets.entrySet().stream()
            .filter(entry -> entry.getValue() != null
                && entry.getValue().size() == 0
                && routeLastRemovalTimes.containsKey(entry.getKey())
                && routeLastRemovalTimes.get(entry.getKey()) < cutoffTime)
            .forEach(entry -> {
                log.info("removing registration info for {}, consequence requests to {} will receive 404", entry.getKey(), entry.getKey());
                this.sockets.remove(entry.getKey());
                this.routeLastRemovalTimes.remove(entry.getKey());
            });
    }

    public boolean canHandle(String target, boolean useCatchAll) {
        final String routeKey = resolveRouteKey(target, useCatchAll);
        if (routeKey == null) return false;
        final List<RouterSocketV3> routeSockets = sockets.get(routeKey);
        return routeSockets != null && routeSockets.size() > 0;
    }

    private String resolveRouteKey(String target, boolean useCatchAll) {
        String resolved = routeResolver.resolve(sockets.keySet(), target);
        if (!useCatchAll && (resolved == null || "*".equals(resolved))) {
            return null;
        } else {
            return Objects.requireNonNullElse(resolved, "*");
        }
    }

    public Map<String, List<RouterSocketV3>> getSockets() {
        Map<String, List<RouterSocketV3>> clone = new HashMap<>();
        for (Map.Entry<String, List<RouterSocketV3>> routeEntry : sockets.entrySet()) {
            // clone to avoid ConcurrentModificationException on ArrayList
            clone.put(routeEntry.getKey(), new ArrayList<>(routeEntry.getValue()));
        }
        return clone;
    }

    public CompletableFuture<RouterSocketV3> removeWebSocket(RouterSocketV3 socket) {
        final CompletableFuture<RouterSocketV3> future = new CompletableFuture<>();
        executor.submit(() -> logIfFail(() -> {
            routeLastRemovalTimes.put(socket.route, System.currentTimeMillis());
            List<RouterSocketV3> RouterSocketV3s = sockets.get(socket.route);
            if (RouterSocketV3s != null && RouterSocketV3s.remove(socket)) {
                idleCount.decrementAndGet();
                future.complete(socket);
                return;
            }
            future.complete(null);
        }));
        return future;
    }

    public CompletableFuture<Boolean> addWebSocket(String route, RouterSocketV3 socket) {
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        executor.submit(() -> logIfFail(() -> {
            sockets.putIfAbsent(route, new ArrayList<>());
            final List<RouterSocketV3> routeSockets = sockets.get(route);
            if (!routeSockets.contains(socket)) {
                routeSockets.add(socket);
                idleCount.incrementAndGet();
            }
            future.complete(true);
        }));
        return future;
    }

    public CompletableFuture<RouterSocketV3> getWebSocket(String target, boolean useCatchAll) {

        final CompletableFuture<RouterSocketV3> future = new CompletableFuture<>();

        executor.submit(() -> {
            try {
                final String routeKey = resolveRouteKey(target, useCatchAll);
                if (routeKey == null) {
                    log.warn("failed to get available websocket for target={}, useCatchAll={}", target, useCatchAll);
                    future.completeExceptionally(new IllegalStateException("failed to get available websocket for target "
                        + target + ", useCatchAll=" + useCatchAll));
                    return;
                }
                List<RouterSocketV3> routeSockets = sockets.get(routeKey);

                if (routeSockets == null || routeSockets.size() == 0) {
                    future.complete(null);
                    return;
                }

                int indexNext = indexMap.getOrDefault(routeKey, -1) + 1;
                if (indexNext > routeSockets.size() - 1) {
                    indexNext = 0;
                }
                indexMap.put(routeKey, indexNext);

                final RouterSocketV3 socket = routeSockets.get(indexNext);
                future.complete(socket);
            } catch (Throwable throwable) {
                log.warn("failed to get available websocket for " + target, throwable);
                future.completeExceptionally(throwable);
            }
        });

        return future;
    }

    public void deRegisterSocket(String target, String remoteAddr, String connectorInstanceID) {
        log.info("Going to deregister targetName=" + target + " and the targetAddr=" + remoteAddr + " and the connectorInstanceID=" + connectorInstanceID);
        List<RouterSocketV3> routerSocketV3s = sockets.get(target);
        if (routerSocketV3s != null) {
            // avoid ConcurrentModificationException
            for (RouterSocketV3 socket : new LinkedList<>(routerSocketV3s)) {
                if (socket.connectorInstanceID().equals(connectorInstanceID)) {
                    removeWebSocket(socket).whenComplete((routerSocketV3, throwable) -> logIfFail(() -> {
                        if (routerSocketV3 != null) routerSocketV3.socketSessionClose();
                    }));
                }
            }
        }
    }

    public int idleCount() {
        return this.idleCount.get();
    }

    public static boolean isSSE(MuResponse response) {
        return response != null && "text/event-stream".equals(response.headers().get(HeaderNames.CONTENT_TYPE));
    }

    public Map<String, Object> getRouteMap() {
        Map<String, Object> result = new HashMap<>();
        for (Map.Entry<String, List<RouterSocketV3>> routeEntry : getSockets().entrySet()) {
            result.put(routeEntry.getKey(), routeEntry.getValue().stream()
                .sorted(Comparator.comparing(a -> a.serviceAddress().getHostString()))
                .map(item -> Map.of(
                    "componentName", item.componentName,
                    "connectorInstanceID", item.connectorInstanceID(),
                    "ip", item.serviceAddress().getHostString(),
                    "port", item.serviceAddress().getPort(),
                    "inflightCount", item.getContextMap().size(),
                    "inflightRequests", item.getContextMap().values().stream()
                        .map(context -> {
                            if (context.request != null) {
                                return Map.of(
                                    "id", context.requestId,
                                    "isSSE", isSSE(context.response),
                                    "startTime", Instant.ofEpochMilli(context.request.startTime()),
                                    "duration", System.currentTimeMillis() - context.request.startTime(),
                                    "requestsMethod", context.request.method(),
                                    "requestsPath", context.request.relativePath()
                                );
                            } else {
                                return Map.of(
                                    "id", context.requestId
                                );
                            }
                        })
                        .collect(Collectors.toList())
                ))
                .collect(Collectors.toList()));
        }
        return result;
    }
}
