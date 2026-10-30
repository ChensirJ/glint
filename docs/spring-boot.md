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
- Sessions and multiple `Set-Cookie` lines
- Multipart uploads (`spring.servlet.multipart.*`)
- `/_vt` diagnostics (unless disabled)

Known limits: servlet `Filter`s (for example `characterEncodingFilter`) are registered so startup succeeds, but the Glint adapter does not yet invoke a filter chain; Spring MVC handlers still run through `DispatcherServlet`.

## Property reference

| Property | Default | Wired? | Notes |
|----------|---------|:------:|-------|
| `server.port` | `8080` | yes | Standard Spring Boot listen port (use `0` for ephemeral). |
| `server.address` | all interfaces | yes | Honored via `AbstractServletWebServerFactory`. |
| `spring.servlet.multipart.*` | Boot defaults | yes | Size limits apply through Spring's multipart resolver. |
| `glint.diagnostics` | `true` | yes | Mount `/_vt` diagnostics UI and JSON APIs. |
| Virtual threads | always on | n/a | Every accepted connection runs on a virtual thread. Not a toggle. |
| Static mounts | - | no | Use standalone `Glint.staticFiles(...)` / `classpathFiles(...)`. Not exposed as Boot properties yet. |
| Access log / JSON access log | - | no | Standalone `Middlewares.accessLog()` / `accessLogJson()`. |
| CORS | - | no | Standalone `Middlewares.cors(...)` / `CorsOptions`. Prefer Spring's `CorsRegistry` for MVC apps. |
| Rate limit | - | no | Standalone `Middlewares.rateLimit(...)`. |

Example `application.yml`:

```yaml
server:
  port: 8080

glint:
  diagnostics: true

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