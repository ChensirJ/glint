# Glint vs Tomcat microbench

- Captured: **2026-10-30 14:17:49 CST** (Asia/Shanghai)
- Method: Java `HttpClient` (HTTP/1.1) virtual-thread workers, same machine, warmup 2s + measure 5s, concurrency 64, path `/hello` body `ok`
- Glint: in-process `Glint.create(0)` / Tomcat: `tomcat-embed-core` ephemeral port
- Re-run: `mvn -pl glint-bench -am exec:java -Dexec.mainClass=io.github.glint.bench.ComparisonBench`

| Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |
|--------|------:|-------:|---------:|---------:|---------:|
| Glint | 52281 | 0 | 0.95 | 4.84 | 14.43 |
| Tomcat embed | 46779 | 0 | 1.20 | 4.37 | 12.50 |

Numbers are machine-local and not a guarantee of production ranking.
