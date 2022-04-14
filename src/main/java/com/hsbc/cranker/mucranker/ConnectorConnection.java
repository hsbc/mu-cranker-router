package com.hsbc.cranker.mucranker;

import java.util.HashMap;

/**
 * Information about one of the connector sockets connected to this router.
 * <p>This is accessible by iterating the services returned in {@link CrankerRouter#collectInfo()}</p>
 */
public interface ConnectorConnection {

    /**
     * @return A unique ID of this socket
     */
    String socketID();

    /**
     * @return The port the socket is connected on
     */
    int port();

    /**
     * @return Returns the data in this object as a map
     */
    HashMap<String, Object> toMap();
}

class ConnectorConnectionImpl implements ConnectorConnection {

    private final int port;
    private final String socketID;

    ConnectorConnectionImpl(int port, String socketID) {
        this.port = port;
        this.socketID = socketID;
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
        return m;
    }

    @Override
    public String toString() {
        return "ConnectorConnectionImpl{" +
            "socketID='" + socketID + '\'' +
            '}';
    }
}
