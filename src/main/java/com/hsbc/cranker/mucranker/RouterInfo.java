package com.hsbc.cranker.mucranker;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Information about a cranker router
 */
public interface RouterInfo {

    /**
     * @return All the services that are registered with this cranker
     */
    List<ConnectorService> services();

    /**
     * Finds the service with the given route
     *
     * @param routeName The route name (or &quot;*&quot; for the catch-all route)
     * @return The service data with the given route name, or <code>Optional.empty()</code> if not found
     */
    Optional<ConnectorService> service(String routeName);

    /**
     * A map containing the state of the router. It is the same data as returned by {@link #services()}
     * but in a form may allow you to more easily expose it (e.g. as JSON) without having to traverse the
     * object model yourself.
     *
     * @return Service info in key-value pairs
     */
    Map<String, Object> toMap();

    /**
     * @return All the hosts that this router currently will not send requests to
     */
    Set<DarkHost> darkHosts();

    /**
     * A map containing the tasks, which are waiting for available connector sockets
     *
     * @return Map, key is the route, value is list of the target urls
     */
    Map<String, List<String>> waitingTasks();
}

class RouterInfoImpl implements RouterInfo {

    private final List<ConnectorService> services;
    private final Set<DarkHost> darkHosts;
    private final Map<String, List<String>> waitingTasks;

    RouterInfoImpl(List<ConnectorService> services,
                   Set<DarkHost> darkHosts,
                   Map<String, List<String>> waitingTasks) {
        this.services = services;
        this.darkHosts = darkHosts;
        this.waitingTasks = waitingTasks;
    }

    @Override
    public List<ConnectorService> services() {
        return services;
    }

    @Override
    public Optional<ConnectorService> service(String routeName) {
        boolean isCatchAll = "*".equals(routeName);
        for (ConnectorService service : services) {
            if (isCatchAll && service.isCatchAll()) return Optional.of(service);
            if (service.route().equals(routeName)) return Optional.of(service);
        }
        return Optional.empty();
    }

    @Override
    public Map<String, Object> toMap() {
        Map<String, Object> i = new HashMap<>();
        for (ConnectorService service : services) {
            i.put(service.route(), service.toMap());
        }
        return i;
    }

    @Override
    public Set<DarkHost> darkHosts() {
        return darkHosts;
    }

    @Override
    public Map<String, List<String>> waitingTasks() {
        return waitingTasks;
    }

    @Override
    public String toString() {
        return "RouterInfoImpl{" +
            "services=" + services +
            '}';
    }


    static List<ConnectorService> getConnectorServiceList(Map<String, Queue<RouterSocket>> socketV1,
                                                          Map<String, Map<String, List<RouterSocketV3>>> domainToSocketV3,
                                                          Set<DarkHost> darkHosts) {

        List<ConnectorService> services = new ArrayList<>();

        Set<String> uniqRoutes = Stream
            .concat(
                socketV1.keySet().stream(),
                domainToSocketV3.values()
                    .stream()
                    .flatMap(item -> item.keySet().stream()))
            .collect(Collectors.toSet());

        for (String route : uniqRoutes) {

            Map<String, ConnectorInstance> instanceMap = new HashMap<>();
            List<ConnectorInstance> instances = new ArrayList<>();
            String componentName = null;

            for (RouterSocket routerSocket : socketV1.getOrDefault(route, new LinkedList<>())) {
                componentName = routerSocket.componentName;
                String connectorInstanceID = routerSocket.connectorInstanceID();
                ConnectorInstance connectorInstance = instanceMap.get(connectorInstanceID);
                if (connectorInstance == null) {
                    connectorInstance = new ConnectorInstanceImpl(
                        routerSocket.serviceAddress().getHostString(),
                        connectorInstanceID,
                        new ArrayList<>(),
                        routerSocket.isDarkModeOn(darkHosts));
                    instanceMap.put(connectorInstanceID, connectorInstance);
                    instances.add(connectorInstance);
                }

                connectorInstance.connections().add(new ConnectorConnectionImpl(
                    "*",
                    routerSocket.serviceAddress().getPort(),
                    routerSocket.routerSocketID,
                    routerSocket.getProtocol(),
                    0));
            }

            for (String domain : domainToSocketV3.keySet()) {

                final Map<String, List<RouterSocketV3>> domainSocketsV3 = domainToSocketV3.get(domain);
                for (RouterSocketV3 routerSocketV3 : domainSocketsV3.getOrDefault(route, new LinkedList<>())) {
                    componentName = routerSocketV3.componentName;
                    String connectorInstanceID = routerSocketV3.connectorInstanceID();
                    ConnectorInstance connectorInstance = instanceMap.get(connectorInstanceID);
                    if (connectorInstance == null) {
                        connectorInstance = new ConnectorInstanceImpl(
                            routerSocketV3.serviceAddress().getHostString(),
                            connectorInstanceID,
                            new ArrayList<>(),
                            false);
                        instanceMap.put(connectorInstanceID, connectorInstance);
                        instances.add(connectorInstance);
                    }

                    connectorInstance.connections().add(new ConnectorConnectionImpl(
                        domain,
                        routerSocketV3.serviceAddress().getPort(),
                        routerSocketV3.routerSocketID,
                        routerSocketV3.getProtocol(),
                        routerSocketV3.getContextMap().size()));
                }

            }



            services.add(new ConnectorServiceImpl(route, componentName, instances));
        }

        return services;
    }
}
