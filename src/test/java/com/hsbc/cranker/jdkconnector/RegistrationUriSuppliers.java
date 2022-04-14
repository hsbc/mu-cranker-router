package com.hsbc.cranker.jdkconnector;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.*;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static java.util.Objects.requireNonNull;

/**
 * This provides some helper methods to define the cranker router URLs the connection should connect to.
 * <p>As the list of routers may change over time, a supplier is used which is called periodically to update
 * the list of URIs to connect to.</p>
 * <p>The return values of the static methods in this class should be passed to {@link CrankerConnectorBuilder#withRouterUris(Supplier)}</p>
 */
public class RegistrationUriSuppliers {

    /**
     * Creates a supplier that always returns a fixed set of URIs
     * @param uris The URIs to return, in the format <code>wss://crankerrouter.example.org</code>
     * @return A registration URI supplier
     */
    public static Supplier<Collection<URI>> fixedUris(Collection<URI> uris) {
        return new FixedUrls(uris);
    }

    /**
     * Creates a supplier that always returns a fixed set of URIs
     * @param uris The URIs to return, in the format <code>wss://crankerrouter.example.org</code>
     * @return A registration URI supplier
     */
    public static Supplier<Collection<URI>> fixedUris(URI... uris) {
        return fixedUris(asList(uris));
    }

    /**
     * Creates a supplier that generates registration URIs based on a DNS name.
     * <p>This allows the managing of routers to be done via A-records in DNS. For example, one DNS name may point
     * to multiple routers by having multiple A records. When using this supplier, a DNS lookup is performed to convert
     * the given hostnames into a list of IP addresses.</p>
     * <p>When changing the A records of a DNS record, this supplier will (after a lag, typically a number of minutes)
     * return the updated list of routers to connect to.</p>
     * <p>Note: because this converts a hostname URL into a collection of IP addresses, hostname checking when using
     * <code>wss</code> will fail if the SSL certificate does not include the IP address of the router being connected to (e.g. as a SAN).</p>
     * <p>If the routers do not include IP addresses in their SSL certs, you may consider disabling hostname verification for
     * the JDK HTTP clients by putting <code>System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");</code>
     * before any <code>java.net</code> classes are loaded (typically as the first line of your <code>public static void main</code> method).</p>
     * @param registrationUris The URI(s) to return, in the format <code>wss://crankerrouter.example.org</code>
     * @return A registration URI supplier
     */
    public static Supplier<Collection<URI>> dnsLookup(Collection<URI> registrationUris) {
        return new DnsLookupSupplier(registrationUris);
    }

    /**
     * Creates a supplier that generates registration URIs based on a DNS name.
     * <p>This allows the managing of routers to be done via A-records in DNS. For example, one DNS name may point
     * to multiple routers by having multiple A records. When using this supplier, a DNS lookup is performed to convert
     * the given hostnames into a list of IP addresses.</p>
     * <p>When changing the A records of a DNS record, this supplier will (after a lag, typically a number of minutes)
     * return the updated list of routers to connect to.</p>
     * <p>Note: because this converts a hostname URL into a collection of IP addresses, hostname checking when using
     * <code>wss</code> will fail if the SSL certificate does not include the IP address of the router being connected to (e.g. as a SAN).</p>
     * <p>If the routers do not include IP addresses in their SSL certs, you may consider disabling hostname verification for
     * the JDK HTTP clients by putting <code>System.setProperty("jdk.internal.httpclient.disableHostnameVerification", "true");</code>
     * before any <code>java.net</code> classes are loaded (typically as the first line of your <code>public static void main</code> method).</p>
     * @param registrationUris The URI(s) to return, in the format <code>wss://crankerrouter.example.org</code>
     * @return A registration URI supplier
     */
    public static Supplier<Collection<URI>> dnsLookup(URI... registrationUris) {
        return new DnsLookupSupplier(asList(registrationUris));
    }


    private static class FixedUrls implements Supplier<Collection<URI>> {
        private final Collection<URI> urls;

        FixedUrls(Collection<URI> urls) {
            this.urls = Set.copyOf(requireNonNull(urls, "urls"));
        }

        @Override
        public Collection<URI> get() {
            return urls;
        }
    }

    private static class DnsLookupSupplier implements Supplier<Collection<URI>> {
        private final Collection<URI> registrationAddresses;

        private DnsLookupSupplier(Collection<URI> registrationAddresses) {
            if (registrationAddresses == null || registrationAddresses.isEmpty()) {
                throw new IllegalArgumentException("No registration URLs were specified");
            }
            for (URI dns : registrationAddresses) {
                if (!List.of("ws", "wss").contains(dns.getScheme())) {
                    throw new IllegalArgumentException("The registration DNS URI should have a 'wss' or 'ws' scheme. It was '" + dns + "'");
                }
            }
            this.registrationAddresses = registrationAddresses;

        }

        @Override
        public Collection<URI> get() {
            List<URI> resolved = new ArrayList<>();
            for (URI uri : registrationAddresses) {
                InetAddress[] machines;
                try {
                    machines = InetAddress.getAllByName(uri.getHost());
                } catch (UnknownHostException e) {
                    throw new RuntimeException("Error running DNS lookup for " + uri, e);
                }
                resolved.addAll(Arrays.stream(machines)
                    .map(address -> {
                        try {
                            return new URI(uri.getScheme(), null, address.getHostAddress(), uri.getPort(), null, null, null);
                        } catch (URISyntaxException e) {
                            throw new RuntimeException("Error generating registration URI based on " + uri, e);
                        }
                    })
                    .collect(Collectors.toList()));
            }
            return resolved;

        }

    }

}
