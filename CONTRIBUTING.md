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

## Releasing

Published Maven coordinates use groupId **`io.github.chensirj`** (Java packages stay `io.github.glint.*`).

1. Ensure `main` is green (`mvn clean verify` and CI).
2. Tag a release version and push the tag:

```bash
git tag v0.1.0
git push origin v0.1.0
```

3. The [Release](.github/workflows/release.yml) workflow runs on tags matching `v*`.
   It sets the Maven version from the tag, runs `mvn clean verify`, and attaches
   jars for `glint-core`, `glint-dashboard`, `glint-servlet`, and
   `glint-spring-boot-starter` (plus sources/javadoc when present) to a GitHub
   Release under groupId `io.github.chensirj`.

Do not retag a published `v*` without a clear reason; prefer a new patch tag.