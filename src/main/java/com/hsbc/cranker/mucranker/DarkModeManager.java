package com.hsbc.cranker.mucranker;

import java.net.InetAddress;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

/**
 * <p>This class allows you to block certain hosts from receiving requests.</p>
 * <p>If a host is in "dark mode" then cranker connectors can still register with it,
 * however no requests will be forwarded to those connectors.</p>
 * <p>You can acquire an instance of this class by creating a cranker router object and
 * then calling {@link CrankerRouter#darkModeManager()}</p>
 */
public interface DarkModeManager {

    /**
     * Specifies that the given target destination should not have any requests sent to it.
     * <p>Does nothing if the host was already in dark mode.</p>
     * @param host The host to block, created with {@link DarkHost#create(InetAddress, Instant, String)}
     */
    void enableDarkMode(DarkHost host);

    /**
     * Removes the target from the set of blocked hosts.
     * <p>Does nothing if the host was not already in dark mode.</p>
     * @param host An IP address previously added to {@link #enableDarkMode(DarkHost)}
     */
    void disableDarkMode(DarkHost host);

    /**
     * The current dark hosts.
     * @return A readonly set of hosts that are currently in dark mode.
     */
    Set<DarkHost> darkHosts();

    /**
     * Finds the host associated with the given address, if it is in dark mode.
     * @param address The address of the host, e.g. <code>InetAddress.getByName("127.0.0.1")</code>
     * @return The host if it is currently in dark mode; otherwise an empty optional object.
     */
    Optional<DarkHost> findHost(InetAddress address);
}

class DarkModeManagerImpl implements DarkModeManager {

    private final WebSocketFarm webSocketFarm;

    DarkModeManagerImpl(WebSocketFarm webSocketFarm) {
        this.webSocketFarm = webSocketFarm;
    }

    @Override
    public void enableDarkMode(DarkHost host) {
        webSocketFarm.enableDarkMode(host);
    }


    @Override
    public void disableDarkMode(DarkHost host) {
        webSocketFarm.disableDarkMode(host);
    }


    @Override
    public Set<DarkHost> darkHosts() {
        return webSocketFarm.getDarkHosts();
    }

    @Override
    public Optional<DarkHost> findHost(InetAddress address) {
        return darkHosts().stream().filter(host -> host.sameHost(address)).findFirst();
    }
}
