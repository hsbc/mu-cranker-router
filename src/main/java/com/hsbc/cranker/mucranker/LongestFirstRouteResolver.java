package com.hsbc.cranker.mucranker;

import java.util.Set;

/**
 * A route resolver which using the longest route to match from the existing routes.
 */
public class LongestFirstRouteResolver implements RouteResolver{

    /**
     * Constructor for LongestFirstRouteResolver
     */
    public LongestFirstRouteResolver() {}

    /**
     * Algorithm: using the longest route to match from the existing routes.
     *
     * <p>e.g. if request route is "/my-service/api/test" , it will try below mapping one by one,
     * If no matching found, use "*" </p>
     *
     * <ol>
     *     <li>"my-service/api/test"</li>
     *     <li>"my-service/api"</li>
     *     <li>"my-service"</li>
     * </ol>
     * @return route
     */
    @Override
    public String resolve(Set<String> routes, String target) {
        if (routes.contains(target)) {
            return target;
        }

        // remove the heading "/"
        StringBuilder builder = new StringBuilder(target);
        if (builder.charAt(0) == '/') builder.delete(0, 1);


        // try matching from the longest path one by one
        int lastIndex;
        while ((lastIndex = builder.lastIndexOf("/")) >= 0) {
            builder.delete(lastIndex, builder.length());
            if (routes.contains(builder.toString())) {
                return builder.toString();
            }
        }

        return "*";
    }


}
