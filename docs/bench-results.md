# Glint vs Tomcat microbench

- Captured: **2026-10-30 15:21:06 CST** (Asia/Shanghai)
- Method: Java `HttpClient` (HTTP/1.1) virtual-thread workers, same machine, warmup 2s + measure 5s, concurrency 64, path `/hello` body `ok`
- Glint: in-process `Glint.create(0)` / Tomcat: `tomcat-embed-core` ephemeral port
- Re-run: `mvn -pl glint-bench -am exec:java -Dexec.mainClass=io.github.glint.bench.ComparisonBench`

| Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |
|--------|------:|-------:|---------:|---------:|---------:|
| Glint | 58887 | 0 | 1.01 | 2.85 | 9.27 |
| Tomcat embed | 58084 | 0 | 1.03 | 2.79 | 26.29 |

Numbers are machine-local and not a guarantee of production ranking.

## Static files (Glint vs Tomcat DefaultServlet)

- Captured: **2026-10-30 15:21:18 CST** (Asia/Shanghai)
- Method: Java `HttpClient` (HTTP/1.1) virtual-thread workers, warmup 2s + measure 5s, concurrency 64
- Glint: `staticFiles` with default StaticOptions (small-file cache + zero-copy for large files)
- Tomcat: embed 10.1.24.0 `DefaultServlet` / `initWebappDefaults`, docBase = temp dir
- Re-run: `mvn -pl glint-bench -am exec:java -Dexec.mainClass=io.github.glint.bench.StaticBench`

| Size | Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |
|------|--------|------:|-------:|---------:|---------:|---------:|
| 1 KiB | Glint | 9214 | 0 | 6.61 | 10.51 | 14.33 |
| 1 KiB | Tomcat DefaultServlet | 20081 | 0 | 3.08 | 5.43 | 48.10 |
| 100 KiB | Glint | 7012 | 0 | 9.02 | 12.02 | 15.46 |
| 100 KiB | Tomcat DefaultServlet | 7353 | 0 | 8.35 | 15.74 | 45.34 |
| 2 MiB | Glint | 333 | 0 | 187.02 | 237.31 | 249.93 |
| 2 MiB | Tomcat DefaultServlet | 371 | 0 | 170.72 | 217.18 | 220.25 |

Numbers are machine-local and not a guarantee of production ranking.
