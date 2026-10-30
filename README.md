# Glint

[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven](https://img.shields.io/badge/Maven-multi--module-brightgreen)](pom.xml)
[![Build](https://img.shields.io/badge/build-passing-success)](https://github.com/ChensirJ/glint)

> **Drop-in virtual-thread server with built-in diagnostics — see why your request is slow.**

Glint is a JDK 21+ HTTP server that runs every connection on a **virtual thread**, ships a live **`/_vt` diagnostics dashboard**, and can replace **Tomcat** in Spring Boot 3.2+ with a single starter dependency.

<p align="center">
  <img src="docs/images/diagnostics-overview.png" alt="Glint diagnostics overview" width="820"/>
</p>

## Why Glint?

| Pain with classic servlet containers | What Glint does |
|--------------------------------------|-----------------|
| Platform thread pools need careful sizing | `Executors.newVirtualThreadPerTaskExecutor()` — one VT per connection |
| Slow requests are opaque | Built-in Trace spans + P50/P99 histograms |
| JDK 21 pinning is silent | JFR `jdk.VirtualThreadPinned` watcher correlates stacks to request id/path |
| Tomcat swap is heavy | Spring Boot starter implements `WebServer` / `ServletWebServerFactory` |

<p align="center">
  <img src="docs/images/pinning-alert.png" alt="Glint pinning alert panel" width="820"/>
</p>

## Architecture

```mermaid
flowchart LR
  Client -->|HTTP/1.1| Acceptor[Platform acceptor thread]
  Acceptor -->|accept| VTPool[Virtual thread per connection]
  VTPool --> Router
  Router --> Middleware
  Middleware --> Handler[Route / DispatcherServlet]
  Handler --> Trace[Trace + Metrics]
  Trace --> Dashboard["/_vt dashboard"]
  JFR[JFR PinningWatcher] -.correlate.-> Trace
```

## Modules

| Module | Description |
|--------|-------------|
| `glint-core` | HTTP parser, VT server, router, middleware, Trace, Metrics, PinningWatcher (**zero** third-party deps) |
| `glint-dashboard` | Static UI for `/_vt` |
| `glint-servlet` | Jakarta Servlet 6 adapter (enough for Spring MVC Hello World) |
| `glint-spring-boot-starter` | Drop-in Tomcat replacement |
| `glint-examples` | Standalone + Spring Boot demos |

## Quick start — standalone

```java
import io.github.glint.Glint;
import io.github.glint.obs.Trace;

public class App {
    public static void main(String[] args) throws Exception {
        Glint.create(8080)
            .get("/hello", (req, resp) -> resp.text("Hello, virtual threads!"))
            .get("/users/{id}", (req, resp) -> {
                String json = Trace.span("db.findUser", () ->
                    "{\"id\":\"" + req.pathParam("id") + "\"}");
                resp.json(json);
            })
            .diagnostics("/_vt")
            .start();
    }
}
```

```bash
mvn -pl glint-examples -am -q exec:java \
  -Dexec.mainClass=io.github.glint.examples.StandaloneDemo
# open http://127.0.0.1:8080/_vt
```

## Quick start — Spring Boot 3.2+

Exclude Tomcat and add the Glint starter:

```xml
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-web</artifactId>
  <exclusions>
    <exclusion>
      <groupId>org.springframework.boot</groupId>
      <artifactId>spring-boot-starter-tomcat</artifactId>
    </exclusion>
  </exclusions>
</dependency>
<dependency>
  <groupId>io.github.glint</groupId>
  <artifactId>glint-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @RestController
    static class Hello {
        @GetMapping("/hello")
        String hello() { return "Hello from Spring Boot on Glint!"; }
    }
}
```

Then open `http://localhost:8081/_vt`.

## JDK 21 notes

- Uses **`ThreadLocal`** for request context (not preview `ScopedValue`).
- Hot paths avoid **`synchronized`** — prefer `ReentrantLock` / concurrent collections so virtual threads are not pinned.
- `PinningWatcher` listens for `jdk.VirtualThreadPinned` via `RecordingStream`.

## Build & test

```bash
mvn -q verify
```

Requires **JDK 21+** and **Maven 3.9+**.

## Roadmap

- [x] Virtual-thread HTTP/1.1 core + keep-alive
- [x] Path params, middleware, diagnostics JSON
- [x] Embedded `/_vt` dashboard
- [x] Servlet adapter + Spring Boot starter (Hello World)
- [ ] OpenTelemetry export
- [ ] Chunked transfer encoding & multipart
- [ ] Broader Servlet 6 coverage (session, async, WebSocket)
- [ ] Published JMH vs Tomcat / Undertow / Jetty
- [ ] GraalVM native-image recipe for standalone mode

## Out of scope (0.1)

Full Servlet 6, HTTP/2/3, TLS termination, DI/ORM frameworks.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues tagged `good first issue` are a great place to start.

## License

[Apache License 2.0](LICENSE)
