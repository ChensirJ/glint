# Spring Boot starter

`glint-spring-boot-starter` replaces embedded Tomcat with Glint's virtual-thread
HTTP server for Spring Boot 3.2+.

## Auto-configuration

Class: `io.github.glint.spring.GlintServerAutoConfiguration`

Registered via:

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

It runs **before** `ServletWebServerFactoryAutoConfiguration` and publishes a
`ServletWebServerFactory` bean (`GlintServletWebServerFactory`) when
`io.github.glint.http.VtServer` is on the classpath and no other
`ServletWebServerFactory` bean exists.

The starter already depends on `spring-boot-starter-web` with
`spring-boot-starter-tomcat` excluded. Your app should do the same exclusion
when declaring `spring-boot-starter-web` itself (see the README example).

What works today through the servlet adapter:

- Spring MVC `@RestController` / `@Controller` mappings
- Servlet filter chain: every `Filter` registered through Spring
  (`@Bean Filter`, `FilterRegistrationBean`, `CharacterEncodingFilter`,
  `DelegatingFilterProxy` / Spring Security) runs before `DispatcherServlet`
- Sessions and multiple `Set-Cookie` lines
- Multipart uploads (`spring.servlet.multipart.*`)
- `/_vt` diagnostics (unless disabled)
- Glint static files, access log, CORS, and rate limit via `glint.*` properties

## Filter chain

`GlintServletContext` implements `FilterRegistration.Dynamic` and builds a
chain per request, following the Servlet spec:

- URL patterns: exact (`/api/users`), path prefix (`/api/*`), extension
  (`*.json`), and `/*` / `/` (match everything).
- Servlet-name mappings (`addMappingForServletNames`, or `*` for all servlets).
- Order: URL-pattern mappings before servlet-name mappings; within each group,
  `isMatchAfter=false` mappings in registration order, then
  `isMatchAfter=true` ones. Spring registers beans by `@Order` /
  `Ordered`, so `FilterRegistrationBean.setOrder(...)` controls the order.
  A filter appears at most once per chain.
- Dispatcher types: only `REQUEST` dispatches happen today. Mappings with a
  null or empty set default to `REQUEST`; filters mapped only to `ERROR`,
  `FORWARD`, `INCLUDE`, or `ASYNC` are not invoked.
- A filter that does not call `chain.doFilter(...)` short-circuits: the
  servlet does not run and whatever the filter wrote is sent.
- `Filter.init(FilterConfig)` (with init parameters) runs once at startup,
  before `Servlet.init`; `destroy()` runs when the web server stops.

## Request pipeline

Outermost first:

1. `glint.access-log` (if enabled)
2. `glint.cors` (if enabled; preflight `OPTIONS` answered with 204 by default)
3. `glint.rate-limit` (if enabled; `/_vt` is exempt so the dashboard stays usable)
4. `/_vt` diagnostics routes (if `glint.diagnostics=true`)
5. `glint.static` mount (if enabled; a missing file under the prefix is a 404
   and does not fall through to Spring)
6. Servlet filter chain, then `DispatcherServlet`

Because steps 1 to 5 run before the servlet layer, static files and the
dashboard are not seen by servlet filters (Spring Security does not guard them).
Use Spring's `CorsRegistry` instead of `glint.cors` when you need per-mapping
CORS rules.

Known limits: no `RequestDispatcher` forward/include and no `ERROR` dispatch to
`/error` mappings.

## Property reference

| Property | Default | Wired? | Notes |
|----------|---------|:------:|-------|
| `server.port` | `8080` | yes | Standard Spring Boot listen port (use `0` for ephemeral). |
| `server.address` | all interfaces | yes | Bind to one local address (for example `127.0.0.1`). |
| `spring.servlet.multipart.*` | Boot defaults | yes | Size limits apply through Spring's multipart resolver. |
| `glint.diagnostics` | `true` | yes | Mount `/_vt` diagnostics UI and JSON APIs. |
| `glint.static.enabled` | `false` | yes | Serve static files from Glint before Spring MVC. |
| `glint.static.prefix` | `/static` | yes | URL prefix of the mount. |
| `glint.static.location` | - | yes | Directory path, or `classpath:root` for classpath resources. Required when enabled. |
| `glint.static.cache-max-entry-bytes` | `262144` | yes | Files up to this size are cached in memory (`0` disables). Directory mounts only. |
| `glint.static.cache-max-total-bytes` | `67108864` | yes | Upper bound for cached bytes. Directory mounts only. |
| `glint.static.revalidate-millis` | `1000` | yes | How long file metadata is trusted before re-checking the filesystem. Directory mounts only. |
| `glint.static.cache-control` | unset | yes | `Cache-Control` for non-fingerprinted files; fingerprinted names always get immutable caching. Directory mounts only. |
| `glint.static.precompressed` | `true` | yes | Serve `.br` / `.gz` siblings when accepted. Directory mounts only. |
| `glint.static.directory-listing` | `false` | yes | HTML/JSON listing for directories without `index.html`. Directory mounts only. |
| `glint.access-log.enabled` | `false` | yes | One line per request to stdout. |
| `glint.access-log.format` | `text` | yes | `text` (`METHOD path -> status (ms)`) or `json` (JSON Lines: ts, method, path, status, durationMs, bytes, remote). |
| `glint.cors.enabled` | `false` | yes | Glint-level CORS on every response. |
| `glint.cors.allowed-origin` | `*` | yes | `*` or one exact origin. |
| `glint.cors.allowed-methods` | `GET,POST,PUT,DELETE,PATCH,HEAD,OPTIONS` | yes | Preflight `Access-Control-Allow-Methods`. |
| `glint.cors.allowed-headers` | `Content-Type,Authorization,X-Requested-With,Range` | yes | Preflight `Access-Control-Allow-Headers`. |
| `glint.cors.exposed-headers` | empty | yes | `Access-Control-Expose-Headers`. |
| `glint.cors.allow-credentials` | `false` | yes | `Access-Control-Allow-Credentials: true`. |
| `glint.cors.max-age-seconds` | `86400` | yes | Preflight `Access-Control-Max-Age`. |
| `glint.cors.short-circuit-options` | `true` | yes | Answer preflight `OPTIONS` with 204 without reaching Spring. |
| `glint.rate-limit.enabled` | `false` | yes | Per-client-IP token bucket; 429 + `Retry-After`. `/_vt` is exempt. |
| `glint.rate-limit.permits-per-second` | `100` | yes | Sustained refill rate per IP. |
| `glint.rate-limit.burst` | `50` | yes | Bucket capacity per IP. |
| Virtual threads | always on | n/a | Every accepted connection runs on a virtual thread. Not a toggle. |

IDE completion for all `glint.*` keys comes from
`META-INF/additional-spring-configuration-metadata.json` in the starter jar.

Example `application.yml`:

```yaml
server:
  port: 8080

glint:
  diagnostics: true
  static:
    enabled: true
    prefix: /assets
    location: ./public          # or classpath:public
    cache-control: public, max-age=300
  access-log:
    enabled: true
    format: json
  cors:
    enabled: true
    allowed-origin: https://app.example.com
    exposed-headers: Content-Range
  rate-limit:
    enabled: true
    permits-per-second: 100
    burst: 50

spring:
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 10MB
```
## Maven coordinates

Published groupId is **`io.github.chensirj`** (Java packages remain `io.github.glint.*`):

```xml
<dependency>
  <groupId>io.github.chensirj</groupId>
  <artifactId>glint-spring-boot-starter</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Until Central publish finishes, install from this repo: `mvn -q clean install -DskipTests`.