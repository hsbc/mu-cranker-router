package com.hsbc.cranker.mucranker;


import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class LongestFirstRouteResolverTest {

    LongestFirstRouteResolver resolver = new LongestFirstRouteResolver();

    @Test
    public void testResolve() {

        final Set<String> routes = Set.of(
            "my-service",
            "my-service/instance",
            "my-service/instance/api"
        );

        assertThat(resolver.resolve(routes, "/my-service/hello"), is("my-service"));
        assertThat(resolver.resolve(routes, "my-service/hello"), is("my-service"));
        assertThat(resolver.resolve(routes, "/my-service/instance/api/test"), is("my-service/instance/api"));
        assertThat(resolver.resolve(routes, "/my-service/instance/test"), is("my-service/instance"));
        assertThat(resolver.resolve(routes, "/non-exist/instance/test"), is("*"));
        assertThat(resolver.resolve(routes, "/non-exist"), is("*"));
    }
}
