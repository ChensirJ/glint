# Glint

<p align="center">
  <img src="docs/images/logo.png" alt="Glint logo" width="128"/>
</p>

[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven](https://img.shields.io/badge/Maven-multi--module-brightgreen)](pom.xml)
[![Build](https://img.shields.io/badge/build-passing-success)](https://github.com/ChensirJ/glint)

> **Drop-in virtual-thread server with built-in diagnostics 闁?see why your request is slow.**

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
# from Glint root 闁?install artifacts into local .m2
mvn -q clean install -DskipTests
# then
cd ..\glint-demo
.\scripts\run.ps1
```

## Why Glint?

| Pain with classic servlet containers | What Glint does |
|--------------------------------------|-----------------|
| Platform thread pools need careful sizing | `Executors.newVirtualThreadPerTaskExecutor()` 闁?one VT per connection |
| Slow requests are opaque | Built-in Trace spans + P50/P99 histograms + slow-request ring |
| JDK 21 pinning is silent | JFR `jdk.VirtualThreadPinned` watcher with truncated stacks in `/_vt` |
| Tomcat swap is heavy | Spring Boot starter implements `WebServer` / `ServletWebServerFactory` |

## Modules

| Module | Description |
|--------|-------------|
| `glint-core` | HTTP parser (Content-Length + chunked + **multipart**), VT server, router, middleware, Trace, Metrics, PinningWatcher, OTLP JSON export hooks (**zero** third-party deps) |
| `glint-dashboard` | Modern dark `/_vt` UI |
| `glint-servlet` | Jakarta Servlet 6 adapter (sessions, multipart `Part`s, async stub) |
| `glint-spring-boot-starter` | Drop-in Tomcat replacement |
| `glint-examples` | Standalone + Spring Boot demos |
| `glint-bench` | Throughput / latency microbench harness |

## Quick start 闁?standalone

```java
import io.github.glint.Glint;
import io.github.glint.middleware.Middlewares;

Glint.create(8080)
    .before(Middlewares.requestLogging())
    .before(Middlewares.cors())
    .get("/hello", (req, resp) -> resp.text("Hello, virtual threads!"))
    .diagnostics("/_vt")
    .start();
```

```bash
.\scripts\run-standalone.ps1
# Open http://127.0.0.1:8080/_vt
```

## HTTP features (0.1+)

- Methods: GET, HEAD, POST, PUT, DELETE, PATCH, OPTIONS
- Query string, path params `{id}`
- Bodies: `Content-Length`, **chunked**, **`multipart/form-data`** (size-limited)
- Limits / idle timeout / **fast graceful shutdown** (force-closes keep-alive sockets)
- Optional **TLS** via `ServerConfig.builder().sslContext(SSLContext)`
- Diagnostics: `/_vt`, metrics, slow, pinning, **`/_vt/api/otlp/v1/metrics`** (OTLP/HTTP JSON, no SDK dep)
- ObsExporter SPI (`ServiceLoader`) for custom exporters
- Middleware: CORS, request logging, exception 闁?JSON
- WebSocket / HTTP Upgrade: **text echo** via `Glint.websocket(path, handler)` (RFC 6455, HTTP/1.1)

## Benchmarks

Side-by-side harness (`ComparisonBench`) starts **Glint** and **Tomcat embed 10.1.24** on ephemeral ports, then hits `/hello` with the same Java `HttpClient` (HTTP/1.1, virtual-thread workers).

```bash
# from repo root after mvn -pl glint-bench -am package
cd glint-bench
java -cp "target/glint-bench-0.1.0-SNAPSHOT.jar;../glint-core/target/glint-core-0.1.0-SNAPSHOT.jar;$(mvn -q dependency:build-classpath -Dmdep.path)" `
  io.github.glint.bench.ComparisonBench 2 5 64
```

Latest capture on the maintainer machine (Asia/Shanghai) 閳?see [`docs/bench-results.md`](docs/bench-results.md):

| Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |
|--------|------:|-------:|---------:|---------:|---------:|
| Glint | 52281 | 0 | 0.95 | 4.84 | 14.43 |
| Tomcat embed | 46779 | 0 | 1.20 | 4.37 | 12.50 |

Method: warmup 2s + measure 5s, concurrency 64, body `ok`. Numbers are machine-local; re-run before quoting. At lower concurrency Tomcat may lead 閳?do not over-claim.
## OpenTelemetry

- Zero required deps in `glint-core`.
- Pull metrics as OTLP/HTTP JSON: `GET /_vt/api/otlp/v1/metrics`.
- Push via SPI: implement `io.github.glint.obs.ObsExporter` and register in `META-INF/services/`.

## JDK 21 notes

- `ThreadLocal` for request context (not preview `ScopedValue`).
- Hot paths avoid `synchronized` 闁?prefer `ReentrantLock` / concurrent collections.
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
- [x] WebSocket HTTP/1.1 Upgrade + text echo (`Glint.websocket`)
- [ ] Published JMH vs Tomcat / Undertow / Jetty with CI numbers
- [ ] GraalVM native-image recipe for standalone mode

## Out of scope (0.1)

Full Servlet 6 surface, HTTP/2/3, fragmented WebSocket messages, DI/ORM frameworks.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Keep **`glint-demo`** (`D:\glint-demo`) in sync when adding user-visible features.

## License

[Apache License 2.0](LICENSE)
