mu-cranker-router
=================

This is a library which allows you to easily create your own Cranker Router. 

Background
----------

Cranker is a load-balancing reverse proxy that is designed for systems with many HTTP services that need to
be exposed at a single endpoint. It was designed for fast-moving teams that need to deploy early and often
with no central configuration needed when a new service is introduced or an existing one is upgraded.

The key difference with other reverse proxies and load balancers is that each service connects via a "connector"
to one or more routers. This connection between connector and router is a websocket, and crucially it means
the service is self-configuring; the router knows where a service is and if it is available by the fact that
it has an active websocket connection for a service.

The direction that connections are made between the load balancer and the services is also reversed: it is the
service that makes an HTTP (websocket) connection to the router, rather than the other way around. This allows
patterns where services can be deployed on private networks, bound to localhost on ephemeral ports, with no
opened incoming TCP ports into the network needed.

This library is an implementation of Cranker router that uses [mu-server](https://muserver.io) as the web server
for handling incoming requests.

Usage
-----

Add a dependency on `mu-cranker-router` and `mu-server`:

````xml
<dependency>
    <groupId>com.hsbc.cranker</groupId>
    <artifactId>mu-cranker-router</artifactId>
    <version>RELEASE</version>
</dependency>
<dependency>
    <groupId>io.muserver</groupId>
    <artifactId>mu-server</artifactId>
    <version>RELEASE</version>
</dependency>
````

Cranker Routers consist of two parts: an HTTPS server which clients send requests to, and a Web Socket server
that connectors register to.

In mu-cranker-router, you are responsible for creating both servers with mu-server (or a single server that does both).

Because you create your own Mu Server, you have full control over which HTTP or HTTPS port you open, the SSL config,
HTTP2 config, authentication, and you can add handlers and filters etc. You can see Mu-Server documentation at <https://muserver.io/> 
but in a simple case, a server could look like the following:

````java
public static void main(String[] args) {
    
    // Use the mucranker library to create a router object - this creates handlers
    CrankerRouter router = CrankerRouterBuilder.crankerRouter()
        .start();
    
    // Start a server which will listen to connector registrations on a websocket
    MuServer registrationServer = MuServerBuilder.muServer()
        .withHttpsPort(8444)
        .addHandler(router.createRegistrationHandler())
        .start();
    
    // Start the server that clients will send HTTP requests to
    MuServer httpServer = MuServerBuilder.muServer()
        .withHttpsPort(8443)
        .addHandler(router.createHttpHandler())
        .start();
    
    System.out.println("Cranker is available at " + httpServer.uri() + 
                       " with registration at " + registrationServer.uri());
}
````

To try this, you can clone this repo and run the `RunLocal.java` class in `src/test/java`.

