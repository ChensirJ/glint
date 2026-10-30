# Glint

<p align="center">
  <img src="docs/images/logo.png" alt="Glint logo" width="128"/>
</p>

[![Java](https://img.shields.io/badge/Java-21%2B-orange)](https://openjdk.org/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)
[![Maven](https://img.shields.io/badge/Maven-multi--module-brightgreen)](pom.xml)
[![CI](https://github.com/ChensirJ/glint/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/ChensirJ/glint/actions/workflows/ci.yml)

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
| `glint-bench` | Glint vs Tomcat harness: `/hello`, static files, and a realistic suite (blocking I/O sweep, JSON + DB wait, mixed traffic, keep-alive vs short connections, uploads) |

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
- **Static mounts**: `Glint.staticFiles(prefix, Path[, StaticOptions])` and `classpathFiles(prefix, root)` with MIME, HEAD, `Range`, path-traversal checks, **ETag** / **Last-Modified**, conditional **304**, **small-file cache** plus a per-path metadata cache (no filesystem calls on hot hits; edits visible within `StaticOptions.revalidateMillis`, default 1000 ms), **zero-copy** `FileChannel.transferTo` for large files on plain TCP, **precompressed** `.br`/`.gz` siblings (`Content-Encoding` + `Vary`), and **immutable** `Cache-Control` for fingerprinted names
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

All numbers below come from `glint-bench` on one machine (AMD Ryzen 7 7840HS, 16 logical cores, 31 GiB RAM, Windows 11, OpenJDK 21.0.8) against **Tomcat embed 10.1.24** with default settings (NIO connector, `maxThreads` 200). Full tables, method, and caveats: [`docs/bench-results.md`](docs/bench-results.md). Reproduce everything with:

```powershell
powershell -ExecutionPolicy Bypass -File scripts/run-bench.ps1   # -Rounds 3 -Warmup 3 -Duration 8
```

Numbers are machine-local, loopback-only, and not a guarantee of production ranking. Re-run before quoting.

### Realistic suite (same handlers on both servers, separate JVMs, median of 3 rounds)

Captured **2026-10-30 15:56 CST** (Asia/Shanghai). Each server runs in its own JVM (`-Xms256m -Xmx1g`); handlers are the same `BenchApp` code; the load generator is a separate JVM using raw HTTP/1.1 sockets on virtual threads. Every cell finished with 0 errors, 0 connect errors, and 0 timeouts.

| Scenario | Clients | Glint req/s | Tomcat req/s | Glint p99 ms | Tomcat p99 ms | Winner |
|----------|--------:|------------:|-------------:|-------------:|--------------:|--------|
| Blocking downstream (50 ms) | 100 | 1,588 | 1,588 | 64.7 | 67.9 | Tie |
| Blocking downstream (50 ms) | 500 | 7,925 | 3,181 | 64.3 | 188.0 | Glint |
| Blocking downstream (50 ms) | 1000 | 15,875 | 3,124 | 64.6 | 316.3 | Glint |
| Blocking downstream (50 ms) | 2000 | 31,788 | 2,997 | 66.2 | 631.5 | Glint |
| Blocking downstream (50 ms) | 4000 | 56,031 | 2,738 | 81.4 | 1,262.8 | Glint |
| JSON parse + 15 ms DB wait + CPU | 200 | 6,421 | 11,848 | 35.7 | 21.5 | **Tomcat** |
| JSON parse + 15 ms DB wait + CPU | 1000 | 24,436 | 12,035 | 68.5 | 100.9 | Glint |
| Mixed: 70% API / 20% 4 KiB / 10% 1 MiB static | 500 | 24,520 | 18,401 | 74.1 | 49.6 | Glint on req/s, **Tomcat** on p99 |
| Keep-alive tiny JSON | 64 | 84,771 | 85,244 | 2.1 | 2.3 | Tie |
| New connection per request | 64 | 20,714 | 17,759 | 4.5 | 5.2 | Glint |
| 1 MiB upload (read + CRC32) | 64 | 1,018 | 3,653 | 113.5 | 42.4 | **Tomcat** |

Peak platform threads: Glint 33-41 vs Tomcat 212 in every scenario. Memory goes both ways. For example, at JSON 1000 clients Glint peaked at 618 MiB heap / 766 MiB RSS against Tomcat's 361 / 516 MiB, at twice the throughput.

What this shows, including where Tomcat wins:

- **Blocking I/O is the Glint case.** Tomcat is capped near `maxThreads / latency` (200 / ~62 ms = ~3.2k req/s), and its queueing latency grows with clients. Glint keeps one virtual thread per connection, so throughput scales with clients and p50 stays at the downstream latency until the CPU saturates.
- **Tomcat wins uploads by ~3.6x.** Glint buffers the whole request body in a `byte[]` before the handler runs (a 1 MiB humongous allocation per request in G1). Tomcat streams it through small buffers. A streaming request-body API is on the roadmap.
- **Tomcat wins JSON + DB wait at 200 clients.** At this load Tomcat's 200 platform threads are enough. In Glint, the simulated DB wait is `Thread.sleep` on a virtual thread. On Windows with JDK 21 that is coarser under load: a micro-check with 200 staggered sleepers measured `sleep(15)` at 21.05 ms on virtual threads vs 15.48 ms on platform threads. That explains part of the gap, not all of it. Above 200 clients Glint pulls ahead.
- **Mixed traffic:** Glint serves more requests, but its API p99 is worse than Tomcat's (76 vs 50 ms) in the median round, and Glint's round-to-round spread was wider (22.6k-28.9k req/s).
- Keep-alive tiny responses are a tie. With a new connection per request, Glint is ~17% ahead.

### `/hello` and static files (in-process microbench, 64 clients, warmup 2s + 5s)

Captured **2026-10-30 15:55 CST** (Asia/Shanghai). Java `HttpClient` load in the same JVM as the server under test.

| Case | Glint req/s | Tomcat req/s | Glint p99 ms | Tomcat p99 ms |
|------|------------:|-------------:|-------------:|--------------:|
| `/hello` text | 94,430 | 50,971 | 2.07 | 4.50 |
| Static 1 KiB | 50,039 | 17,155 | 5.04 | 8.12 |
| Static 100 KiB | 10,637 | 5,734 | 24.91 | 19.26 |
| Static 2 MiB | 282 | 243 | 401.88 | 421.08 |

Static 1 KiB before this release: Glint 9,214 vs Tomcat 20,081 req/s. Profiling showed about five filesystem metadata calls per request (`isDirectory`, `isRegularFile`, two sibling probes for `.gz`/`.br`, `readAttributes`), which are expensive on Windows, plus a second write syscall for the body. Glint now caches the resolved path, siblings, and precomputed ETag / Last-Modified strings per mount for `revalidateMillis` (default 1000 ms; Tomcat's resource cache uses a 5 s TTL by default), and it writes header + small body in one gathering write. Run-to-run variance on this laptop is large. A standalone run at 15:44 CST measured 1 KiB at 88,823 vs 19,966 req/s, 100 KiB at 14,842 vs 7,144, and 2 MiB at 358 vs 395, with Tomcat ahead on 2 MiB. So treat 2 MiB as a tie, and note that Tomcat's 100 KiB p99 was better in the run above.

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
- [x] Benchmark module (`glint-bench`): microbench, static files, realistic Glint vs Tomcat suite
- [x] GitHub Actions CI (`mvn -B clean verify`, JDK 21) and tag-driven release workflow
- [x] Servlet adapter + Spring Boot starter (Hello World)
- [ ] Full OTLP trace export / SDK bridge module
- [x] Servlet sessions (`JSESSIONID`), multipart `Part`s, async `startAsync`/`complete` stub
- [x] WebSocket HTTP/1.1 Upgrade: text, binary, ping/pong, close codes, fragmented messages (Glint.websocket)
- [x] Static file mounts (directory + classpath) with MIME / HEAD / Range
- [x] Middleware helpers: CORS options, accessLog, token-bucket rateLimit, gzip compress
- [x] Static ETag / Last-Modified / 304
- [x] Rate-limit counters on `/_vt` (`rateLimit.allowed` / `rejected`)
- [x] Multi `Set-Cookie` header support (`HttpResponse.cookie`)
- [ ] Streaming request bodies (large uploads without full buffering)
- [ ] Linux re-run of the realistic suite and an open-loop (wrk2-style) generator
- [ ] Published JMH vs Tomcat / Undertow / Jetty with CI numbers
- [ ] GraalVM native-image recipe for standalone mode

## Out of scope (0.1)

Full Servlet 6 surface, HTTP/2/3, DI/ORM frameworks.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Keep **`glint-demo`** (`D:\glint-demo`) in sync when adding user-visible features.

## License

[Apache License 2.0](LICENSE)
