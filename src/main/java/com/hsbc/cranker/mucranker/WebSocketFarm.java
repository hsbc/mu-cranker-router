package com.hsbc.cranker.mucranker;

import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.Mutils;
import io.netty.util.HashedWheelTimer;
import io.netty.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.ObjLongConsumer;
import java.util.stream.Collectors;

class WebSocketFarm {
    private static final Logger log = LoggerFactory.getLogger(WebSocketFarm.class);

    private static final String MU_ID = "muid";

    private final RouteResolver routeResolver;

    private final Map<String, Queue<RouterSocket>> sockets = new ConcurrentHashMap<>();
    private final Map<String, Queue<WaitingSocketTask>> waitingTasks = new ConcurrentHashMap<>();
    private final Map<String, Long> routeLastRemovalTimes = new ConcurrentHashMap<>();

    private final AtomicInteger idleCount = new AtomicInteger(0);
    private final AtomicInteger waitingTaskCount = new AtomicInteger(0);

    private final ConcurrentHashMap.KeySetView<DarkHost, Boolean> darkHosts = ConcurrentHashMap.newKeySet();
    private volatile boolean hasCatchAll = false;
    private final long maxWaitInMillis;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> new Thread(runnable, "websocket-farm-execution"));
    private final HashedWheelTimer timer = new HashedWheelTimer(runnable -> new Thread(runnable, "websocket-farm-timer"));

    public WebSocketFarm(RouteResolver routeResolver, long maxWaitInMillis) {
        this.routeResolver = routeResolver;
        this.maxWaitInMillis = maxWaitInMillis;
    }

    public void start() {
        timer.start();
    }

    public void stop() {
        timer.stop();
        executor.shutdown();
        for (Queue<RouterSocket> queue : sockets.values()) {
            for (RouterSocket routerSocket : queue) {
                routerSocket.socketSessionClose();
            }
        }
        sockets.clear();
        waitingTasks.clear();
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
        final Queue<RouterSocket> routerSockets = sockets.get(routeKey);
        return routerSockets != null && routerSockets.size() > 0;
    }

    public int idleCount() {
        return this.idleCount.get();
    }

    public void removeWebSocketAsync(String route, RouterSocket socket, Runnable onRemoveSuccess) {
        executor.submit(() -> ThrowingFunction.logIfFail(() -> {

            routeLastRemovalTimes.put(route, System.currentTimeMillis());

            boolean removed = false;
            Queue<RouterSocket> routerSockets = sockets.get(route);
            if (routerSockets != null) {
                removed = routerSockets.remove(socket);
            }
            if (removed) {
                idleCount.decrementAndGet();
                onRemoveSuccess.run();
            }
        }));
    }

    public void addWebSocketAsync(String route, RouterSocket socket) {
        // for catchAll route, route="*"
        executor.submit(() -> ThrowingFunction.logIfFail(() -> addWebSocketSync(route, socket)));
    }

    private void addWebSocketSync(String route, RouterSocket socket) {

        if (socket.isCatchAll()) {
            hasCatchAll = true;
        }

        // if there are requests waiting for a socket to this route, then immediately pass the socket to the request
        final Queue<WaitingSocketTask> waiting = waitingTasks.get(route);
        if (waiting != null && waiting.size() > 0) {
            final WaitingSocketTask waitTask = waiting.poll();
            if (waitTask != null) {
                waitTask.notifySuccess(socket);
                return;
            }
        }

        // There are no pending client requests for this socket's route, so add it to the idle queue
        sockets.putIfAbsent(route, new ConcurrentLinkedQueue<>());
        Queue<RouterSocket> queue = sockets.get(route);
        if (queue.offer(socket)) {
            idleCount.incrementAndGet();
        }
    }

    private RouterSocket getRouterSocket(String routeKey) {
        Queue<RouterSocket> routerSockets = sockets.get(routeKey);
        RouterSocket socket;
        if (darkHosts.isEmpty()) {
            socket = routerSockets.poll();
        } else {
            socket = getNonDarkSocket(routerSockets, this.darkHosts);
        }
        return socket;
    }

    private long peekTime(long start) {
        return System.currentTimeMillis() - start;
    }

    /**
     * Attempts to get a websocket to send a request on.
     *
     * @param target         The full path of the request, for example <code>/some-service/blah</code> (in which a socket
     *                       for the <code>some-service</code> route would be looked up)
     * @param useCatchall    true mean fallback to "*" route when can't find specific route, otherwise just reject
     * @param clientRequest  {@link MuRequest} for providing info while acquiring socket
     * @param clientResponse {@link MuResponse} for providing info while acquiring socket
     * @param onSuccess      A callback if this is successful. If there is a socket already waiting then this is executed
     *                       immediately on the same thread. If no sockets are available for the given target, then it will
     *                       wait for notification in another thread until it's timeout
     *                       (based on the value set with {@link CrankerRouterBuilder#withConnectorMaxWaitInMillis(long)})
     * @param onFailure      A callback if this is failed, e.g. wait till timeout and no socket available
     */
    public void acquireSocket(String target, boolean useCatchall, MuRequest clientRequest, MuResponse clientResponse,
                              ObjLongConsumer<RouterSocket> onSuccess, SocketAcquireFailedListener onFailure) {

        // do nothing if response already ended
        if (clientResponse.responseState().endState()) {
            log.info("client response state is {}, skip processing, muid={}",
                clientResponse.responseState(), clientRequest.attribute(MU_ID));
            return;
        }

        final String routeKey = resolveRouteKey(target, useCatchall);
        if (routeKey == null) {
            onFailure.accept(404, 0L, "404 Not Found", "Page not found");
            return;
        }

        // 404 if no catchAll routes
        if ("*".equals(routeKey) && !hasCatchAll) {
            onFailure.accept(404, 0L, "404 Not Found", "Page not found");
            return;
        }

        // handle route, it might get available socket immediately, or wait for notification
        final long[] startTime = new long[]{System.currentTimeMillis()};
        executor.submit(() -> ThrowingFunction.logIfFail(() -> {

            final RouterSocket routerSocket = getRouterSocket(routeKey);
            if (routerSocket != null) {
                idleCount.decrementAndGet();
                routeLastRemovalTimes.put(routeKey, System.currentTimeMillis());
                onSuccess.accept(routerSocket, peekTime(startTime[0]));
                return;
            }

            waitingTasks.putIfAbsent(routeKey, new ConcurrentLinkedQueue<>());
            final Queue<WaitingSocketTask> waiting = waitingTasks.get(routeKey);

            final WaitingSocketTask waitingSocketTask = new WaitingSocketTask(target);

            final Timeout timeoutHandle = timer.newTimeout((timeout) -> executor.submit(() -> ThrowingFunction.logIfFail(() -> {
                if (timeout.isCancelled()) {
                    return;
                }
                waiting.remove(waitingSocketTask);
                waitingTaskCount.decrementAndGet();
                onFailure.accept(503, peekTime(startTime[0]), "503 Service Unavailable",
                    String.format("No cranker connectors available within %s ms", maxWaitInMillis));
            })), this.maxWaitInMillis, TimeUnit.MILLISECONDS);

            waitingSocketTask
                .onSuccess((socket) -> {
                    // caller run it with executor (single thread)
                    waiting.remove(waitingSocketTask);
                    waitingTaskCount.decrementAndGet();
                    timeoutHandle.cancel();
                    if (clientResponse.responseState().endState()) {
                        log.info("Connector available, but client response state is {}, skip processing, muid={}",
                            clientResponse.responseState(), clientRequest.attribute(MU_ID));

                        addWebSocketSync(routeKey, socket); // return the socket back
                    } else {
                        routeLastRemovalTimes.put(routeKey, System.currentTimeMillis());
                        onSuccess.accept(socket, peekTime(startTime[0]));
                    }
                });


            if (waiting.offer(waitingSocketTask)) {
                waitingTaskCount.incrementAndGet();
            }

        }));
    }

    private String resolveRouteKey(String target, boolean useCatchAll) {
        String resolved = routeResolver.resolve(sockets.keySet(), target);
        if (!useCatchAll && (resolved == null || "*".equals(resolved))) {
            return null;
        } else {
            return Objects.requireNonNullElse(resolved, "*");
        }
    }

    @FunctionalInterface
    public interface ThrowingFunction {
        void run() throws Throwable;

        static void logIfFail(ThrowingFunction f) {
            try {
                f.run();
            } catch (Throwable e) {
                log.warn("Exception", e);
            }
        }
    }

    private class WaitingSocketTask {

        private final String target;
        private Consumer<RouterSocket> successListener;

        public WaitingSocketTask(String target) {
            this.target = target;
        }

        public void notifySuccess(RouterSocket socket) {
            if (this.successListener != null) {
                this.successListener.accept(socket);
            }
        }

        public WaitingSocketTask onSuccess(Consumer<RouterSocket> successListener) {
            this.successListener = successListener;
            return this;
        }

        public String getTarget() {
            return target;
        }
    }


    private RouterSocket getNonDarkSocket(Queue<RouterSocket> routerSockets, ConcurrentHashMap.KeySetView<DarkHost, Boolean> darkHosts) {
        for (RouterSocket candidate : routerSockets) {
            if (candidate.isDarkModeOn(darkHosts)) {
                continue;
            }
            boolean removed = routerSockets.remove(candidate);
            if (removed) {
                return candidate;
            }
        }
        return null;
    }

    public void deRegisterSocket(String target, String remoteAddr, String connectorInstanceID) {
        log.info("Going to deregister targetName=" + target + " and the targetAddr=" + remoteAddr + " and the connectorInstanceID=" + connectorInstanceID);
        Queue<RouterSocket> routerSockets = sockets.get(target);
        if (routerSockets != null) {
            routerSockets.forEach(a -> removeSockets(connectorInstanceID, a));
        }
    }

    private void removeSockets(String connectorInstanceID, RouterSocket routerSocket) {
        String currentConnectorInstanceID = routerSocket.connectorInstanceID();
        if (currentConnectorInstanceID.equals(connectorInstanceID)) {
            removeWebSocketAsync(routerSocket.route, routerSocket, routerSocket::socketSessionClose);
        }
    }

    Map<String, Queue<RouterSocket>> getSockets() {
        return sockets;
    }

    Map<String, List<String>> getWaitingTasks() {
        Map<String, List<String>> result = new HashMap<>();
        waitingTasks.forEach((key, value) -> result.put(
            key,
            value.stream()
                .map(WaitingSocketTask::getTarget)
                .collect(Collectors.toList())
        ));
        return result;
    }

    void enableDarkMode(DarkHost darkHost) {
        Mutils.notNull("darkHost", darkHost);
        boolean added = darkHosts.add(darkHost);
        if (added) {
            log.info("Enabled dark mode for " + darkHost);
        } else {
            log.info("Requested dark mode for " + darkHost + " but it was already in dark mode, so doing nothing.");
        }
    }

    void disableDarkMode(DarkHost darkHost) {
        Mutils.notNull("darkHost", darkHost);
        boolean removed = darkHosts.remove(darkHost);
        if (removed) {
            log.info("Disabled dark mode for " + darkHost);
        } else {
            log.info("Requested to disable dark mode for " + darkHost + " but it was not in dark mode, so doing nothing.");
        }
    }

    Set<DarkHost> getDarkHosts() {
        return Set.copyOf(darkHosts);
    }

    interface SocketAcquireFailedListener {
        void accept(int returnCode, long waitTimeInMillis, String header, String body);
    }


}
