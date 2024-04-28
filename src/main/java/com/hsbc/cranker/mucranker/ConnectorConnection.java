package com.hsbc.cranker.mucranker;

import java.util.HashMap;

/**
 * Information about one of the connector sockets connected to this router.
 * <p>This is accessible by iterating the services returned in {@link CrankerRouter#collectInfo()}</p>
 */
public interface ConnectorConnection {

    /**
     * A unique ID of this socket
     * @return A unique ID of this socket
     */
    String socketID();

    /**
     * The port the socket is connected on
     * @return The port the socket is connected on
     */
    int port();

    /**
     * the data in this object as a map
     * @return Returns the data in this object as a map
     */
    HashMap<String, Object> toMap();
}

class ConnectorConnectionImpl implements ConnectorConnection {

    private final String domain;
    private final int port;
    private final String socketID;
    private final String protocol;
    private final int inflight;

    ConnectorConnectionImpl(String domain, int port, String socketID, String protocol, int inflight) {
        this.domain = domain;
        this.port = port;
        this.socketID = socketID;
        this.protocol = protocol;
        this.inflight = inflight;
    }

    @Override
    public String socketID() {
        return socketID;
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public HashMap<String, Object> toMap() {
        HashMap<String, Object> m = new HashMap<>();
        m.put("socketID", socketID);
        m.put("port", port);
        m.put("protocol", protocol);
        if (domain != null && !"*".equals(domain)) {
            m.put("domain", domain);
        }
        if ("cranker_3.0".equals(protocol)) {
            m.put("inflight", inflight);
        }
        return m;
    }

    @Override
    public String toString() {
        return "ConnectorConnectionImpl{" +
            "socketID='" + socketID + '\'' +
            '}';
    }
}
