package com.hsbc.cranker.mucranker;

/**
 * A validator that can be used to secure registration requests.
 * <p>An IPValidator is registered when constructing the router, via {@link CrankerRouterBuilder#withRegistrationIpValidator(IPValidator)}</p>
 */
public interface IPValidator {

    /**
     * Called when a connector attempts to register a route to this router.
     *
     * @param ip The IP address of the connector client
     * @return <code>true</code> if this IP address is allowed to register routers; otherwise <code>false</code>
     */
    boolean allow(String ip);

    /**
     * A validator that allows all IP addresses to connect
     */
    IPValidator AllowAll = ip -> true;
}
