# Glint vs Tomcat microbench

- Captured: **2026-10-30 15:55:35 CST** (Asia/Shanghai)
- Method: Java `HttpClient` (HTTP/1.1) virtual-thread workers, same machine, warmup 2s + measure 5s, concurrency 64, path `/hello` body `ok`
- Glint: in-process `Glint.create(0)` / Tomcat: `tomcat-embed-core` ephemeral port
- Re-run: `powershell -ExecutionPolicy Bypass -File scripts/run-bench.ps1`

| Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |
|--------|------:|-------:|---------:|---------:|---------:|
| Glint | 94430 | 0 | 0.59 | 2.07 | 5.75 |
| Tomcat embed | 50971 | 0 | 1.10 | 4.50 | 14.28 |

Numbers are machine-local and not a guarantee of production ranking.

## Static files (Glint vs Tomcat DefaultServlet)

- Captured: **2026-10-30 15:55:37 CST** (Asia/Shanghai)
- Method: Java `HttpClient` (HTTP/1.1) virtual-thread workers, warmup 2s + measure 5s, concurrency 64
- Glint: `staticFiles` with default StaticOptions (small-file cache + zero-copy for large files)
- Tomcat: embed 10.1.24.0 `DefaultServlet` / `initWebappDefaults`, docBase = temp dir
- Re-run: `powershell -ExecutionPolicy Bypass -File scripts/run-bench.ps1`

| Size | Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |
|------|--------|------:|-------:|---------:|---------:|---------:|
| 1 KiB | Glint | 50039 | 0 | 1.02 | 5.04 | 22.23 |
| 1 KiB | Tomcat DefaultServlet | 17155 | 0 | 3.27 | 8.12 | 50.19 |
| 100 KiB | Glint | 10637 | 0 | 5.14 | 24.91 | 48.25 |
| 100 KiB | Tomcat DefaultServlet | 5734 | 0 | 10.80 | 19.26 | 62.49 |
| 2 MiB | Glint | 282 | 0 | 216.18 | 401.88 | 416.19 |
| 2 MiB | Tomcat DefaultServlet | 243 | 0 | 275.66 | 421.08 | 445.15 |

Numbers are machine-local and not a guarantee of production ranking.
## Realistic suite (Glint vs Tomcat, same app logic)

- Captured: **2026-10-30 16:22:35 CST** (Asia/Shanghai); upload-1MiB rows re-measured after streaming bodies (other realistic cells kept from 15:56:21 CST)
- CPU: AMD Ryzen 7 7840HS w/ Radeon 780M Graphics
- Logical cores: 16
- RAM: 31.2 GiB
- OS: Windows 11 10.0 amd64
- JDK: OpenJDK 64-Bit Server VM 21.0.8+9-LTS
- Tomcat: 10.1.24.0 (embedded, default NIO connector: maxThreads 200, acceptCount 100)
- Server JVM flags: -Xms256m -Xmx1g -XX:+UseG1GC (same for both, separate child JVMs)
- Load generator: `RealisticBench`, raw HTTP/1.1 over blocking sockets, one virtual thread per client, closed loop, same machine (loopback), separate JVM from both servers
- Each cell: warmup 3s + measure 8s, 3 rounds alternating server order; the median round by req/s is shown with the min-max req/s across rounds
- Handlers: identical `BenchApp` code on both servers; Glint uses default settings except maxRequestBodyBytes 16 MiB (for uploads); Tomcat uses embedded defaults
- Heap peak and platform-thread peak are sampled every 50 ms inside the server JVM during the measured window; RSS is the process working set right after it
- Re-run: `powershell -File scripts/run-bench.ps1` (all suites) or see the script for the single `java -cp ... RealisticBench 3 3 8` command

Scenarios:

- blocking-50ms: `GET /api/slow`, handler sleeps 50 ms (slow downstream), keep-alive
- json+db+cpu: `POST /api/order` with a 7 KiB JSON order (60 lines): parse, price lines, SHA-256 x5 over the body, sleep 15 ms, serialize a ~5 KiB JSON response
- mixed-traffic: 70% `GET /api/item` (10 ms wait + 1 KiB JSON), 20% 4 KiB static CSS, 10% 1 MiB static file
- keep-alive vs conn-per-req: `GET /api/hello` tiny JSON; conn-per-req sends `Connection: close` and opens a new TCP connection for every request
- upload-1MiB: `POST /api/upload` with a 1 MiB body; handler streams via bodyStream() and returns CRC32

| Scenario | Clients | Server | req/s (median) | req/s min-max | p50 ms | p99 ms | p99.9 ms | errors | connect errors | timeouts | heap peak MiB | RSS MiB | platform threads peak |
|----------|--------:|--------|---------------:|---------------|-------:|-------:|---------:|-------:|---------------:|---------:|--------------:|--------:|----------------------:|
| blocking-50ms | 100 | Glint | 1588 | 1581-1588 | 62.42 | 64.69 | 65.82 | 0 | 0 | 0 | 116 | 183 | 33 |
| blocking-50ms | 100 | Tomcat | 1588 | 1588-1596 | 62.69 | 67.85 | 72.68 | 0 | 0 | 0 | 96 | 188 | 118 |
| blocking-50ms | 500 | Glint | 7925 | 7918-7938 | 62.57 | 64.29 | 64.89 | 0 | 0 | 0 | 155 | 233 | 33 |
| blocking-50ms | 500 | Tomcat | 3181 | 3124-3184 | 155.72 | 187.96 | 189.61 | 0 | 0 | 0 | 155 | 243 | 212 |
| blocking-50ms | 1000 | Glint | 15875 | 15875-15875 | 62.42 | 64.58 | 66.26 | 0 | 0 | 0 | 174 | 253 | 33 |
| blocking-50ms | 1000 | Tomcat | 3124 | 3123-3140 | 311.73 | 316.26 | 328.26 | 0 | 0 | 0 | 103 | 292 | 212 |
| blocking-50ms | 2000 | Glint | 31788 | 31770-31814 | 62.37 | 66.17 | 74.71 | 0 | 0 | 0 | 209 | 301 | 33 |
| blocking-50ms | 2000 | Tomcat | 2997 | 2995-3004 | 620.20 | 631.53 | 643.03 | 0 | 0 | 0 | 185 | 310 | 212 |
| blocking-50ms | 4000 | Glint | 56031 | 54657-56505 | 71.67 | 81.37 | 86.52 | 0 | 0 | 0 | 253 | 355 | 33 |
| blocking-50ms | 4000 | Tomcat | 2738 | 2700-2761 | 1235.13 | 1262.78 | 1267.42 | 0 | 0 | 0 | 180 | 317 | 212 |
| json+db+cpu | 200 | Glint | 6421 | 6418-6431 | 31.16 | 35.67 | 37.00 | 0 | 0 | 0 | 240 | 359 | 33 |
| json+db+cpu | 200 | Tomcat | 11848 | 11798-11888 | 16.56 | 21.47 | 23.09 | 0 | 0 | 0 | 285 | 429 | 212 |
| json+db+cpu | 1000 | Glint | 24436 | 24105-24790 | 39.64 | 68.49 | 77.85 | 0 | 0 | 0 | 618 | 766 | 33 |
| json+db+cpu | 1000 | Tomcat | 12035 | 12011-12201 | 81.06 | 100.87 | 107.28 | 0 | 0 | 0 | 361 | 516 | 212 |
| mixed-traffic | 500 | Glint | 24520 | 22649-28936 | 18.90 | 74.14 | 112.69 | 0 | 0 | 0 | 161 | 354 | 41 |
| mixed-traffic | 500 | Tomcat | 18401 | 15562-18625 | 28.19 | 49.56 | 75.00 | 0 | 0 | 0 | 363 | 532 | 212 |
| keep-alive | 64 | Glint | 84771 | 72835-93879 | 0.68 | 2.14 | 3.50 | 0 | 0 | 0 | 157 | 349 | 41 |
| keep-alive | 64 | Tomcat | 85244 | 84267-87511 | 0.68 | 2.27 | 3.89 | 0 | 0 | 0 | 380 | 531 | 212 |
| conn-per-req | 64 | Glint | 20714 | 20062-21162 | 2.98 | 4.54 | 10.08 | 0 | 0 | 0 | 154 | 350 | 41 |
| conn-per-req | 64 | Tomcat | 17759 | 17685-18340 | 3.52 | 5.22 | 6.33 | 0 | 0 | 0 | 382 | 539 | 212 |
| upload-1MiB | 64 | Glint | 5631 | 5602-5892 | 10.74 | 26.96 | 37.79 | 0 | 0 | 0 | 158 | 241 | 33 |
| upload-1MiB | 64 | Tomcat | 7200 | 4986-7213 | 8.14 | 28.84 | 42.09 | 0 | 0 | 0 | 162 | 273 | 79 |

Mixed-traffic breakdown (median round):

| Server | Class | ok requests | p50 ms | p99 ms | p99.9 ms |
|--------|-------|------------:|-------:|-------:|---------:|
| Glint | api | 137193 | 26.00 | 76.34 | 109.03 |
| Glint | static-4KiB | 39209 | 4.23 | 54.74 | 78.68 |
| Glint | static-1MiB | 19755 | 6.91 | 99.94 | 141.39 |
| Tomcat | api | 102969 | 30.50 | 50.25 | 74.23 |
| Tomcat | static-4KiB | 29456 | 16.38 | 33.79 | 57.12 |
| Tomcat | static-1MiB | 14779 | 21.80 | 57.70 | 89.98 |

errors = reset/EOF/non-200 on an established connection; connect errors = failed TCP connect (e.g. accept backlog full); timeouts = no response within 10 s. The generator closes sockets with SO_LINGER=0 (RST) to avoid client TIME_WAIT exhaustion.

Caveats: closed-loop load (a slow server also slows the generator, so tail latency is understated versus an open-loop tool such as wrk2); generator and server share the same CPU; Glint streams request bodies via bodyStream()/bodyChannel() (bodyBytes() still buffers when called); platform-thread counts exclude Glint's virtual threads by design; simulated waits use Thread.sleep, and on Windows JDK 21 virtual-thread timed sleeps are coarser under load than platform-thread sleeps, which penalizes Glint in sleep-heavy cells.


