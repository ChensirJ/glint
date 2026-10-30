# Contributing to Glint

Thanks for your interest in Glint!

## Development setup

- JDK 21 or newer
- Apache Maven 3.9+

```bash
mvn -q verify
```

## Project layout

| Module | Role |
|--------|------|
| `glint-core` | Virtual-thread HTTP server, router, metrics, pinning watcher |
| `glint-dashboard` | Embedded `/_vt` diagnostics UI |
| `glint-servlet` | Jakarta Servlet 6 adapter (enough for Spring MVC Hello World) |
| `glint-spring-boot-starter` | Drop-in Tomcat replacement for Spring Boot 3.2+ |
| `glint-examples` | Standalone and Spring Boot demos |

## Coding guidelines

- Target **JDK 21** (no preview APIs: use `ThreadLocal`, not `ScopedValue`).
- Avoid `synchronized` on hot paths; prefer `ReentrantLock` / concurrent collections.
- Keep **English** comments and Javadoc.
- Core module must stay **zero third-party dependencies**.

## Pull requests

1. Open an issue for non-trivial changes when possible.
2. Keep PRs focused; include tests for router / metrics / parser changes.
3. Run `mvn verify` before submitting.
4. Mark good first issues with the `good first issue` label when filing tasks for newcomers.

## License

By contributing, you agree that your contributions are licensed under the Apache License 2.0.
