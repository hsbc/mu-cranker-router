package com.hsbc.cranker.mucranker;

import io.muserver.Mutils;

import java.net.InetAddress;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A host that does not have requests forwarded to it.
 * <p>Putting a host in dark mode is useful when needing to take a host out of an environment
 * temporarily, e.g. for patching etc.</p>
 */
public interface DarkHost {

    /**
     * The address of the host
     * @return The address of the host
     */
    InetAddress address();

    /**
     * The time that dark mode was turned on for this host
     * @return Returns the time that dark mode was turned on for this host
     */
    Instant dateEnabled();

    /**
     * An optional description of why this host is in dark mode.
     * @return A human-readable string, or null.
     */
    String reason();

    /**
     * Creates a new dark host
     * @param address The address of the host, for example <code>InetAddress.getByName("127.0.0.1")</code>
     * @param dateEnabled The date it was disabled
     * @param reason An optional reason of why this was enabled
     * @return A new DarkHost object
     */
    static DarkHost create(InetAddress address, Instant dateEnabled, String reason) {
        return new DarkHostImpl(address, dateEnabled, reason);
    }

    /**
     * Returns true if the given address matches this host
     * @param address A host to check
     * @return True if the address is the same (generally if the IP addresses match)
     */
    boolean sameHost(InetAddress address);

    /**
     * Creates a map object holding the address, date and reason. This can be used to easily
     * expose the data in JSON endpoints etc.
     * @return A map of name-value pairs.
     */
    Map<String, Object> toMap();
}

class DarkHostImpl implements DarkHost {

    private final InetAddress address;
    private final Instant dateEnabled;
    private final String reason;

    DarkHostImpl(InetAddress address, Instant dateEnabled, String reason) {
        Mutils.notNull("address", address);
        Mutils.notNull("dateEnabled", dateEnabled);
        this.address = address;
        this.dateEnabled = dateEnabled;
        this.reason = reason;
    }

    @Override
    public InetAddress address() {
        return address;
    }

    @Override
    public Instant dateEnabled() {
        return dateEnabled;
    }

    @Override
    public String reason() {
        return reason;
    }

    @Override
    public boolean sameHost(InetAddress address) {
        Mutils.notNull("address", address);
        return this.address.equals(address);
    }

    @Override
    public Map<String, Object> toMap() {
        HashMap<String, Object> m = new HashMap<>();
        m.put("address", address.getHostAddress());
        m.put("dateEnabled", dateEnabled);
        m.put("reason", reason);
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DarkHostImpl darkHost = (DarkHostImpl) o;
        return address.equals(darkHost.address);
    }

    @Override
    public int hashCode() {
        return Objects.hash(address);
    }

    @Override
    public String toString() {
        return "DarkHostImpl{" +
            "address=" + address +
            ", dateEnabled=" + dateEnabled +
            ", reason='" + reason + '\'' +
            '}';
    }
}
