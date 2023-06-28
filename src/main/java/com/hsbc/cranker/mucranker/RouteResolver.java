package com.hsbc.cranker.mucranker;

import java.util.Set;

/**
 * Algorithm for resolving route, which will decide which connector socket to be used.
 */
public interface RouteResolver {

    /**
     * resolve the route which will decide which connector socket to be used
     *
     * <p> the default implementation using the first segment of the target and do an exact match.
     * If no matching found, returning &quot;*&quot;</p>
     *
     * @param routes all the existing routes in cranker
     * @param target requests uri path, e.g. /my-service/api
     * @return route, return &quot;*&quot; or null will result in using the &quot;catchAll&quot; route in cranker
     */
    default String resolve(Set<String> routes, String target) {
        final String[] split = target.split("/");
        if (split.length >= 2 && routes.contains(split[1])) {
            return split[1];
        } else {
            // It's either root target, or blank target
            return "*";
        }
    };
}
