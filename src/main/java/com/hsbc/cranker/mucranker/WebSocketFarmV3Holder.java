package com.hsbc.cranker.mucranker;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.hsbc.cranker.mucranker.WebSocketFarm.ThrowingFunction.logIfFail;

class WebSocketFarmV3Holder {

    private final Map<String, WebSocketFarmV3> domainToFarmMap;
    private final RouteResolver routeResolver;

    public WebSocketFarmV3Holder(RouteResolver routeResolver) {
        this.routeResolver = routeResolver;
        this.domainToFarmMap = new ConcurrentHashMap<>();
    }

    public void start() {
    }

    public void stop() {
        for (WebSocketFarmV3 farm : domainToFarmMap.values()) {
            logIfFail(farm::stop);
        }
        domainToFarmMap.clear();
    }

    public void cleanRoutes(long routesKeepTimeMillis) {
        for (WebSocketFarmV3 farm : domainToFarmMap.values()) {
            farm.cleanRoutes(routesKeepTimeMillis);
        }
    }

    public WebSocketFarmV3 getOrCreateWebSocketFarmV3(String domain) {
        return domainToFarmMap.computeIfAbsent(domain, k -> new WebSocketFarmV3(routeResolver));
    }

    public WebSocketFarmV3 getWebSocketFarmV3(String domain) {
        return domainToFarmMap.get(domain);
    }

    public Map<String, Map<String, List<RouterSocketV3>>> getSocketMaps() {
        Map<String, Map<String, List<RouterSocketV3>>> clone = new HashMap<>();
        for (Map.Entry<String, WebSocketFarmV3> farmEntry : domainToFarmMap.entrySet()) {
            clone.put(farmEntry.getKey(), farmEntry.getValue().getSockets());
        }
        return clone;
    }

    public int idleCount() {
        return domainToFarmMap.values()
            .stream()
            .mapToInt(WebSocketFarmV3::idleCount)
            .sum();
    }

    public boolean canHandle(String domain, String target, boolean useCatchAll) {
        final WebSocketFarmV3 farm = domainToFarmMap.get(domain);
        return farm != null && farm.canHandle(target, useCatchAll);
    }

    public void deRegisterSocket(String target, String remoteAddr, String connectorInstanceID){
        for (WebSocketFarmV3 webSocketFarmV3 : domainToFarmMap.values()) {
            logIfFail(() -> webSocketFarmV3.deRegisterSocket(target, remoteAddr, connectorInstanceID));
        }
    }
}
