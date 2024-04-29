package com.hsbc.cranker.mucranker;

import io.muserver.MuHandler;

/**
 * This class creates {@link MuHandler} instances for receiving HTTP requests from clients, and
 * a handler for receiving websocket registrations from cranker connectors.
 * <p>You are responsible for creating Mu Server instance(s) that the handlers are added to.
 * When shutting down, the {@link #stop()} method should be called after stopping your Mu Server(s).</p>
 * <p>This class is created by using the {@link CrankerRouterBuilder#crankerRouter()} builder.</p>
 */
public interface CrankerRouter {
    /**
     * Creates the endpoint that cranker connectors connect to.
     * @return Returns a MuHandler that you can add to a MuServer.
     */
    MuHandler createRegistrationHandler();

    /**
     * The total number of websocket connections for all routes that are currently connected and ready to receive requests
     * @return The total number of websocket connections for all routes that are currently connected and ready to receive requests
     */
    int idleConnectionCount();

    /**
     * Creates the handler that receives HTTP requests from clients and then forwards them on to connectors.
     * @return A MuHandler that can be added to a MuServer
     */
    MuHandler createHttpHandler();

    /**
     * Gets meta data about the connected services.
     * @return A new object containing service information.
     */
    RouterInfo collectInfo();

    /**
     * Disconnects all sockets and cleans up. This should be called after shutting down the registration
     * server.
     */
    void stop();

    /**
     * A manager that allows you to stop or start requests going to specific hosts.
     * @return A manager that allows you to stop or start requests going to specific hosts.
     */
    DarkModeManager darkModeManager();

    /**
     * The version of mu-cranker-router being used.
     * @return The version of mu-cranker-router being used, e.g. <code>1.0.0</code>
     */
    static String muCrankerVersion() {
        return MuCranker.artifactVersion();
    }
}
