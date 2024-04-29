import com.hsbc.cranker.mucranker.CrankerRouter;
import com.hsbc.cranker.mucranker.CrankerRouterBuilder;
import com.hsbc.cranker.mucranker.FavIconHandler;
import io.muserver.*;
import org.json.JSONObject;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.muserver.MuServerBuilder.muServer;

public class RunLocal {
    public static void main(String[] args) throws IOException {

        // This is an example of a cranker router implementation.

        // Use the mu-cranker-router builder to create a router object.
        CrankerRouter router = CrankerRouterBuilder.crankerRouter()
            .withSupportedCrankerProtocols(List.of("cranker_3.0", "cranker_1.0"))
            .withIdleTimeout(5, TimeUnit.MINUTES)
            .withRegistrationIpValidator(ip -> true)
            .start();

        // Create a server for connectors to register to. As you create this server, you can control
        // whether it is HTTP or HTTPS, the ports used, and you can add your own handlers to do things
        // like health diagnostics, extra authentication or logging etc.
        // The last handler added is the registration handler that the CrankerRouter object supplies.
        MuServer registrationServer = muServer()
            .withHttpsPort(12001)
            .addHandler(Method.GET, "/health", new HealthHandler(router))
            .addHandler(Method.GET, "/health/connections", (request, response, pathParams) -> {
                response.contentType("text/plain;charset=utf-8");
                for (HttpConnection con : request.server().activeConnections()) {
                    response.sendChunk(con.httpsProtocol() + " " + con.remoteAddress() + "\n");
                    for (MuRequest activeRequest : con.activeRequests()) {
                        response.sendChunk("   " + activeRequest + "\n");
                    }
                    response.sendChunk("\n");
                }
                response.sendChunk("-------");
            })
            .addHandler(Method.GET, "/health/connectors", (request, response, pathParams) -> {
                response.contentType(ContentTypes.APPLICATION_JSON);
                response.write(new JSONObject()
                    .put("services", router.collectInfo().toMap())
                    .toString(2));
            })
            .addHandler(router.createRegistrationHandler())
            .start();

        // Next create the server that HTTP clients will connect to. In this example, HTTP2 is enabled,
        // a favicon is enabled, and then the handler that the CrankerRouter object supplies is added last.
        MuServer httpServer = muServer()
            .withHttpsPort(12000)
            .withHttp2Config(Http2ConfigBuilder.http2EnabledIfAvailable())
            .addHandler(FavIconHandler.fromClassPath("/favicon.ico"))
            .addHandler(router.createHttpHandler())
            .start();

        System.out.println("Registration URL is ws" + registrationServer.uri().toString().substring(4));
        System.out.println("Health diagnostics are at " + registrationServer.uri().resolve("/health"));
        System.out.println("The HTTP endpoint for clients is available at " + httpServer.uri());

        // Now web servers can use a connector to register to the registration URL and they will be
        // exposed on the HTTP endpoint.


        // Shutdown order is HTTP server, then registration server, and finally the router.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            httpServer.stop();
            registrationServer.stop();
            router.stop();
        }));

    }

    private static class HealthHandler implements RouteHandler {
        private final CrankerRouter router;

        public HealthHandler(CrankerRouter router) {
            this.router = router;
        }

        @Override
        public void handle(MuRequest req, MuResponse resp, Map<String, String> pathParams) {
            resp.contentType("application/json");
            MuStats stats = req.server().stats();
            JSONObject health = new JSONObject()
                .put("activeRequests", stats.activeConnections())
                .put("activeConnections", stats.activeRequests().size())
                .put("completedRequests", stats.completedRequests())
                .put("bytesSent", stats.bytesSent())
                .put("bytesReceived", stats.bytesRead())
                .put("invalidRequests", stats.invalidHttpRequests())
                .put("crankerVersion", CrankerRouter.muCrankerVersion())
                .put("services", router.collectInfo().toMap());
            resp.write(health.toString(2));
        }
    }
}
