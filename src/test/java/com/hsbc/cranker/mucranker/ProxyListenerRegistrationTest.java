package com.hsbc.cranker.mucranker;

import io.muserver.MuRequest;
import io.muserver.MuResponse;
import io.muserver.MuServer;
import okhttp3.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.hsbc.cranker.mucranker.CrankerRouterBuilder.crankerRouter;
import static io.muserver.MuServerBuilder.httpServer;
import static org.junit.jupiter.api.Assertions.*;
import static scaffolding.ClientUtils.call;
import static scaffolding.ClientUtils.request;

@Timeout(30)
class ProxyListenerRegistrationTest {
    @Test
    void additiveKeyedAndReplacingRegistrationsHaveIndependentSnapshots() {
        List<String> calls = new CopyOnWriteArrayList<>();
        List<CrankerRouter> routers = new ArrayList<>();
        ProxyListener application = listener("application", calls);
        ProxyListener instrumentation = listener("instrumentation", calls);
        ProxyListener ignored = listener("ignored", calls);
        List<ProxyListener> supplied = new ArrayList<>(List.of(application));
        CrankerRouterBuilder builder = crankerRouter().withProxyListeners(supplied);
        supplied.clear();
        assertSame(builder, builder.addProxyListener(null, instrumentation));
        assertSame(builder, builder.addProxyListener("timing", listener("keyed-first", calls)));
        assertSame(builder, builder.addProxyListener("timing", ignored));
        builder.addProxyListener(null, application);
        try {
            CrankerRouter first = builder.start();
            routers.add(first);
            builder.addProxyListener("timing", ignored);
            CrankerRouter second = builder.start();
            routers.add(second);
            builder.addProxyListener(null, listener("later", calls));
            CrankerRouter third = builder.start();
            routers.add(third);
            builder.withProxyListeners(List.of(listener("replacement", calls)))
                .addProxyListener("timing", listener("key-reused", calls));
            CrankerRouter replaced = builder.start();
            routers.add(replaced);
            CrankerRouter independent = crankerRouter()
                .addProxyListener("timing", listener("independent", calls)).start();
            routers.add(independent);
            assertCallbacks(first, calls, "application", "instrumentation", "keyed-first", "application");
            assertCallbacks(second, calls, "application", "instrumentation", "keyed-first", "application");
            assertCallbacks(third, calls, "application", "instrumentation", "keyed-first", "application", "later");
            assertCallbacks(replaced, calls, "replacement", "key-reused");
            assertCallbacks(independent, calls, "independent");
        } finally {
            routers.forEach(CrankerRouter::stop);
        }
    }

    @Test
    void invalidRegistrationDoesNotReserveItsKeyOrReplaceListeners() {
        List<String> calls = new CopyOnWriteArrayList<>();
        CrankerRouterBuilder builder = crankerRouter().addProxyListener(null, listener("application", calls));
        assertThrows(RuntimeException.class, () -> builder.addProxyListener(null, null));
        assertThrows(RuntimeException.class, () -> builder.addProxyListener("key", null));
        assertThrows(RuntimeException.class, () -> builder.withProxyListeners(null));
        List<ProxyListener> invalid = new ArrayList<>();
        invalid.add(null);
        assertThrows(RuntimeException.class, () -> builder.withProxyListeners(invalid));
        CrankerRouter router = builder.addProxyListener("key", listener("valid", calls)).start();
        try {
            assertCallbacks(router, calls, "application", "valid");
        } finally {
            router.stop();
        }
    }

    private static ProxyListener listener(String name, List<String> calls) {
        return new ProxyListener() {
            @Override
            public void onRequestReceived(MuRequest request, MuResponse response) {
                calls.add(name);
            }
        };
    }

    private static void assertCallbacks(CrankerRouter router, List<String> calls, String... expected) {
        calls.clear();
        MuServer server = httpServer().withInterface("127.0.0.1")
            .addHandler(router.createHttpHandler()).start();
        try {
            try (Response response = call(request(server.uri()))) {
                assertEquals(404, response.code());
            }
            assertEquals(List.of(expected), calls);
        } finally {
            server.stop();
        }
    }
}
