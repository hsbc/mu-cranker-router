package com.hsbc.cranker.mucranker;

import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static io.muserver.MuServerBuilder.httpServer;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

public class FavIconHandlerTest {

    @Test
    public void canUseFromClasspath() throws IOException {
        MuServer server = httpServer()
            .addHandler(FavIconHandler.fromClassPath("/favicon.ico"))
            .start();

        try (Response resp = call(request(server.uri().resolve("/favicon.ico")))) {
            assertThat(resp.code(), is(200));
            assert resp.body() != null;
            assertThat(resp.body().bytes().length, is(15406));
        }
    }

}
