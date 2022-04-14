package com.hsbc.cranker.mucranker;

import java.util.*;

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
     * @param routeName The route name (or &quot;*&quot; for the catch-all route)
     * @return The service data with the given route name, or <code>Optional.empty()</code> if not found
     */
    Optional<ConnectorService> service(String routeName);

    /**
     * A map containing the state of the router. It is the same data as returned by {@link #services()}
     * but in a form may allow you to more easily expose it (e.g. as JSON) without having to traverse the
     * object model yourself.
     * @return Service info in key-value pairs
     */
    Map<String,Object> toMap();

    /**
     * @return All the hosts that this router currently will not send requests to
     */
    Set<DarkHost> darkHosts();

    /**
     * A map containing the tasks, which are waiting for available connector sockets
     * @return Map, key is the route, value is list of the target urls
     */
    Map<String, List<String>> waitingTasks();
}

class RouterInfoImpl implements RouterInfo {

    private final List<ConnectorService> services;
    private final Set<DarkHost> darkHosts;
    private final Map<String, List<String>> waitingTasks;

    RouterInfoImpl(List<ConnectorService> services, Set<DarkHost> darkHosts, Map<String, List<String>> waitingTasks) {
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
        Map<String,Object> i = new HashMap<>();
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



    static void addSocketData(List<ConnectorService> services, Map<String, Queue<RouterSocket>> sockets, Set<DarkHost> darkHosts) {
        for (Map.Entry<String, Queue<RouterSocket>> entry : sockets.entrySet()) {
            Map<String, ConnectorInstance> map = new HashMap<>();
            List<ConnectorInstance> instances = new ArrayList<>();
            String componentName = null;
            for (RouterSocket routerSocket : entry.getValue()) {
                componentName = routerSocket.componentName;
                String connectorInstanceID = routerSocket.connectorInstanceID();
                ConnectorInstance connectorInstance = map.get(connectorInstanceID);
                if (connectorInstance == null) {
                    String ip = routerSocket.serviceAddress().getHostString();
                    boolean darkMode = routerSocket.isDarkModeOn(darkHosts);
                    connectorInstance = new ConnectorInstanceImpl(ip, connectorInstanceID, new ArrayList<>(), darkMode);
                    map.put(connectorInstanceID, connectorInstance);
                    instances.add(connectorInstance);
                }

                int port = routerSocket.serviceAddress().getPort();
                ConnectorConnection cc = new ConnectorConnectionImpl(port, routerSocket.routerSocketID);
                connectorInstance.connections().add(cc);
            }
            
            services.add(new ConnectorServiceImpl(entry.getKey(), componentName, instances));
        }
    }
}
