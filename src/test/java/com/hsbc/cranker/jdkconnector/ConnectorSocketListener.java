package com.hsbc.cranker.jdkconnector;

interface ConnectorSocketListener {

    /**
     * Called when the socket is being used for a request
     */
    void onConnectionAcquired(ConnectorSocket socket);

    /**
     * Called when a socket unexpectedly gets disconnected
     */
    void onError(ConnectorSocket socket, Throwable error);
}
