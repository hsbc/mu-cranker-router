package com.hsbc.cranker.mucranker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Information about a service that is connected to this router.
 * <p>A &quot;service&quot; is 1 or more connector instances that register the same route.</p>
 * <p>Get a copy of this data by calling {@link CrankerRouter#collectInfo()}</p>
 */
public interface ConnectorService {

    /**
     * The path prefix of the service.
     * @return The path prefix of the service, or &quot;*&quot; if it is a catch-all service.
     * @see #isCatchAll()
     */
    String route();

    /**
     * The component name that the connector registered
     * @return The component name that the connector registered
     */
    String componentName();

    /**
     * The connectors that serve this route.
     * @return The connectors that serve this route.
     */
    List<ConnectorInstance> connectors();

    /**
     * If this connector serves from the root of the URL path.
     * @return True if this connector serves from the root of the URL path
     */
    boolean isCatchAll();

    /**
     * Gets this data has key-value pairs.
     * @return Gets this data has key-value pairs.
     */
    Map<String,Object> toMap();
}

class ConnectorServiceImpl implements ConnectorService {

    private final String route;
    private final String componentName;
    private final List<ConnectorInstance> instances;

    ConnectorServiceImpl(String route, String componentName, List<ConnectorInstance> instances) {
        this.route = route;
        this.componentName = componentName;
        this.instances = instances;
    }

    @Override
    public String route() {
        return route;
    }

    @Override
    public String componentName() {
        return componentName;
    }

    @Override
    public List<ConnectorInstance> connectors() {
        return instances;
    }

    @Override
    public boolean isCatchAll() {
        return "*".equals(route);
    }

    @Override
    public Map<String, Object> toMap() {
        HashMap<String, Object> m = new HashMap<>();
        m.put("name", route);
        m.put("componentName", componentName);
        m.put("isCatchAll", isCatchAll());
        List<Map<String,Object>> cons = new ArrayList<>();
        for (ConnectorInstance instance : instances) {
            cons.add(instance.toMap());
        }
        m.put("connectors", cons);
        return m;
    }

    @Override
    public String toString() {
        return "ConnectorServiceImpl{" +
            "route='" + route + '\'' +
            "componentName='" + componentName + '\'' +
            ", instances=" + instances +
            '}';
    }
}
