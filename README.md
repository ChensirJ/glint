# Glint

<p align="center">
  <img src="docs/images/logo.png" alt="Glint logo" width="128"/>
</p>

[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven](https://img.shields.io/badge/Maven-multi--module-brightgreen)](pom.xml)
[![Build](https://img.shields.io/badge/build-passing-success)](https://github.com/ChensirJ/glint)

> **Drop-in virtual-thread server with built-in diagnostics  - see why your request is slow.**

Glint is a JDK 21+ HTTP server that runs every connection on a **virtual thread**, ships a live **`/_vt` diagnostics dashboard**, and can replace **Tomcat** in Spring Boot 3.2+ with a single starter dependency.

<p align="center">
  <!-- Real screenshot: Chrome headless against running demo http://127.0.0.1:8090/_vt (Asia/Shanghai) -->
  <img src="docs/images/diagnostics-overview.png" alt="Glint diagnostics overview" width="820"/>
</p>
<p align="center">
  <img src="docs/images/diagnostics-detail.png" alt="Glint diagnostics detail" width="820"/>
</p>

## Feature gallery demo

A separate living demo lives next to this repo at **`D:\glint-demo`** (sibling folder; not published as part of this git tree).

**Convention:** whenever Glint gains a capability, `glint-demo` is updated in the **same turn** so `/features` stays an accurate gallery of what the server can do.

```bash
# from Glint root  - install artifacts into local .m2
mvn -q clean install -DskipTests
# then
cd ..\glint-demo
.\scripts\run.ps1
```

## Why Glint?

| Pain with classic servlet containers | What Glint does |
|--------------------------------------|-----------------|
| Platform thread pools need careful sizing | `Executors.newVirtualThreadPerTaskExecutor()`  - one VT per connection |
| Slow requests are opaque | Built-in Trace spans + P50/P99 histograms + slow-request ring |
| JDK 21 pinning is silent | JFR `jdk.VirtualThreadPinned` watcher with truncated stacks in `/_vt` |
| Tomcat swap is heavy | Spring Boot starter implements `WebServer` / `ServletWebServerFactory` |

## Modules

| Module | Description |
|--------|-------------|
| `glint-core` | HTTP parser (Content-Length + chunked + **multipart**), static mounts (ETag/304), VT server, router, middleware (CORS/accessLog/rateLimit/compress), Trace, Metrics, PinningWatcher, OTLP JSON export hooks (**zero** third-party deps) |
| `glint-dashboard` | Modern dark `/_vt` UI |
| `glint-servlet` | Jakarta Servlet 6 adapter (sessions, multipart `Part`s, async stub) |
| `glint-spring-boot-starter` | Drop-in Tomcat replacement |
| `glint-examples` | Standalone + Spring Boot demos |
| `glint-bench` | Throughput / latency microbench harness |

## Quick start - standalone

Yes, you can use it that way - but you still need a `main` method (README snippets often omit the class for brevity). Full runnable program:

```java
import io.github.glint.Glint;
import io.github.glint.middleware.Middlewares;

public class Main {
    public static void main(String[] args) throws Exception {
        Glint.create(8080)
            .before(Middlewares.requestLogging())
            .before(Middlewares.cors())
            .get("/hello", (req, resp) -> resp.text("Hello, virtual threads!"))
            .diagnostics("/_vt")
            .startAsync(); // non-blocking start

        Thread.currentThread().join(); // keep the JVM alive
    }
}
```

Bundled entry point with a real `main`: `io.github.glint.examples.StandaloneDemo`.

```bash
.\scripts\run-standalone.ps1
# Open http://127.0.0.1:8080/hello and http://127.0.0.1:8080/_vt/
```


## Quick start - Spring Boot 3.2+ (replace Tomcat)

`glint-spring-boot-starter` brings its own `ServletWebServerFactory` and already excludes
`spring-boot-starter-tomcat`. Point `spring-boot-starter-web` at Glint the same way:

**Maven** (`pom.xml`):

```xml
<dependencies>
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
    <groupId>io.github.chensirj</groupId>
    <artifactId>glint-spring-boot-starter</artifactId>
    <version>0.1.0-SNAPSHOT</version>
  </dependency>
</dependencies>
```

Until the starter is on Maven Central, install this repo locally first:

```bash
mvn -q clean install -DskipTests
```

(`groupId` `io.github.chensirj` is the published coordinate; the in-tree parent POM may still
use a transitional id until Central publish is finished - prefer `io.github.chensirj` in apps.)

**`application.yml`** (optional - defaults are fine):

```yaml
server:
  port: 8080
spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
```

**Full application** (copy-paste `main`):

```java
package com.example.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class Application {

    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }

    @RestController
    static class HelloController {

        @GetMapping("/hello")
        String hello() {
            return "Hello from Spring Boot on Glint!";
        }

        @GetMapping("/api/users/{id}")
        String user(@PathVariable("id") String id) {
            return "{\"id\":\"" + id + "\",\"via\":\"spring-mvc\"}";
        }
    }
}
```

Run:

```bash
mvn spring-boot:run
# Open http://127.0.0.1:8080/hello and http://127.0.0.1:8080/_vt/
```

Bundled twin of this example: `io.github.glint.examples.SpringBootDemo`

```bash
.\scripts\run-spring.ps1
# or: mvn -pl glint-examples -am -Pspring-demo spring-boot:run
```

Auto-configuration class: `io.github.glint.spring.GlintServerAutoConfiguration`
(registered via `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`).
Sessions, cookies, multipart uploads, and Spring MVC mapping work through the
`glint-servlet` adapter. Diagnostics stay at `/_vt/` by default.

## HTTP features (0.1+)

- Methods: GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS
- Query string, path params `{id}`, greedy `{*path}`
- Bodies: `Content-Length`, **chunked**, **`multipart/form-data`** (size-limited)
- **Static mounts**: `Glint.staticFiles(prefix, Path[, StaticOptions])` and `classpathFiles(prefix, root)` with MIME, HEAD, `Range`, path-traversal checks, **ETag** / **Last-Modified**, conditional **304**, **small-file cache**, **zero-copy** `FileChannel.transferTo` for large files on plain TCP, **precompressed** `.br`/`.gz` siblings (`Content-Encoding` + `Vary`), and **immutable** `Cache-Control` for fingerprinted names
- Limits / idle timeout / **fast graceful shutdown** (force-closes keep-alive sockets)
- Optional **TLS** via `ServerConfig.builder().sslContext(SSLContext)`
- Diagnostics: `/_vt`, metrics, slow, pinning, **`/_vt/api/otlp/v1/metrics`** (OTLP/HTTP JSON, no SDK dep)
- ObsExporter SPI (`ServiceLoader`) for custom exporters
- Middleware: **CORS** (`CorsOptions`), **accessLog** / `requestLogging`, **token-bucket rateLimit** (Retry-After + `/_vt` counters), **gzip compress** (`Accept-Encoding`; no Brotli without native deps), exception -> JSON
- Cookies: multiple **`Set-Cookie`** header lines (`resp.cookie(...)` / `addCookie`)
- WebSocket / HTTP Upgrade via `Glint.websocket(path, handler)` (RFC 6455, HTTP/1.1): **text**, **binary**, **ping/pong** (auto-reply), **close codes**, **fragmented message reassembly**

### Static files + middleware (complete `main`)

```java
import io.github.glint.Glint;
import io.github.glint.middleware.CorsOptions;
import io.github.glint.middleware.Middlewares;
import java.nio.file.Path;

public class Main {
    public static void main(String[] args) throws Exception {
        Glint.create(8080)
            .before(Middlewares.accessLog())
            .before(Middlewares.cors(CorsOptions.builder()
                .allowOrigin("*")
                .exposeHeaders("Content-Range")
                .build()))
            .before(Middlewares.rateLimit(100, 50))
            .before(Middlewares.compress())
            .staticFiles("/assets", Path.of("public")) // or StaticOptions.builder()...
            .classpathFiles("/web", "static/web")
            .get("/hello", (req, resp) -> resp.text("ok"))
            .diagnostics("/_vt")
            .startAsync();
        Thread.currentThread().join();
    }
}
```

## Benchmarks

Side-by-side harness (`glint-bench`) starts **Glint** and **Tomcat embed 10.1.24** on ephemeral ports, then hits the same paths with Java `HttpClient` (HTTP/1.1, virtual-thread workers). Defaults: warmup 2s, measure 5s, concurrency 64.

Full tables: [`docs/bench-results.md`](docs/bench-results.md).

### `/hello` (text)

Captured **2026-10-30 15:21:06 CST** (Asia/Shanghai).

| Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |
|--------|------:|-------:|---------:|---------:|---------:|
| Glint | 58,887 | 0 | 1.01 | 2.85 | 9.27 |
| Tomcat embed | 58,084 | 0 | 1.03 | 2.79 | 26.29 |

### Static files (Glint `staticFiles` vs Tomcat `DefaultServlet`)

Captured **2026-10-30 15:21:18 CST** (Asia/Shanghai). Sizes 1 KiB / 100 KiB / 2 MiB.

| Size | Glint req/s | Tomcat req/s | Note |
|------|------------:|-------------:|------|
| 1 KiB | 9,214 | 20,081 | Tomcat ahead on tiny hot files this run |
| 100 KiB | 7,012 | 7,353 | Roughly tied |
| 2 MiB | 333 | 371 | Roughly tied (Glint uses zero-copy `transferTo`) |

> Numbers are machine-local and not a guarantee of production ranking. Re-run before quoting. Static results are honest: Glint trades some tiny-file throughput for ETag/304, precompressed siblings, and `/_vt` cache metrics.

```bash
mvn -pl glint-bench -am -q package
mvn -pl glint-bench -q exec:java -Dexec.mainClass=io.github.glint.bench.ComparisonBench -Dexec.args="2 5 64"
# StaticBench: java -cp glint-bench/target/classes:glint-core/target/classes:... io.github.glint.bench.StaticBench
.\scripts\run-bench.ps1
```


## OpenTelemetry

- Zero required deps in `glint-core`.
- Pull metrics as OTLP/HTTP JSON: `GET /_vt/api/otlp/v1/metrics`.
- Push via SPI: implement `io.github.glint.obs.ObsExporter` and register in `META-INF/services/`.

## JDK 21 notes

- `ThreadLocal` for request context (not preview `ScopedValue`).
- Hot paths avoid `synchronized`  - prefer `ReentrantLock` / concurrent collections.
- `PinningWatcher` listens for `jdk.VirtualThreadPinned` via `RecordingStream`.

## Build & test

```bash
mvn -q verify
mvn -q install   # needed before running ../glint-demo
```

Requires **JDK 21+** and **Maven 3.9+**.

## Roadmap

- [x] Virtual-thread HTTP/1.1 core + keep-alive + fast shutdown
- [x] Chunked + multipart bodies, query params, HEAD/PUT/DELETE/OPTIONS
- [x] Path params, middleware, diagnostics JSON + `/_vt` dashboard
- [x] OTLP/HTTP JSON metrics endpoint + ObsExporter SPI
- [x] Optional TLS via SSLContext
- [x] Microbench module (`glint-bench`)
- [x] Servlet adapter + Spring Boot starter (Hello World)
- [ ] Full OTLP trace export / SDK bridge module
- [x] Servlet sessions (`JSESSIONID`), multipart `Part`s, async `startAsync`/`complete` stub
- [x] WebSocket HTTP/1.1 Upgrade: text, binary, ping/pong, close codes, fragmented messages (Glint.websocket)
- [x] Static file mounts (directory + classpath) with MIME / HEAD / Range
- [x] Middleware helpers: CORS options, accessLog, token-bucket rateLimit, gzip compress
- [x] Static ETag / Last-Modified / 304
- [x] Rate-limit counters on `/_vt` (`rateLimit.allowed` / `rejected`)
- [x] Multi `Set-Cookie` header support (`HttpResponse.cookie`)
- [ ] Published JMH vs Tomcat / Undertow / Jetty with CI numbers
- [ ] GraalVM native-image recipe for standalone mode

## Out of scope (0.1)

Full Servlet 6 surface, HTTP/2/3, DI/ORM frameworks.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Keep **`glint-demo`** (`D:\glint-demo`) in sync when adding user-visible features.

## License

[Apache License 2.0](LICENSE)
