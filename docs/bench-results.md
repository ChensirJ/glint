# Glint vs Tomcat microbench

- Captured: **2026-11-01 08:55:58 CST** (Asia/Shanghai)
- Method: Java `HttpClient` (HTTP/1.1) virtual-thread workers, same machine, warmup 2s + measure 5s, concurrency 64, path `/hello` body `ok`
- Glint: in-process `Glint.create(0)` / Tomcat: `tomcat-embed-core` ephemeral port
- Re-run: `powershell -ExecutionPolicy Bypass -File scripts/run-bench.ps1`

| Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |
|--------|------:|-------:|---------:|---------:|---------:|
| Glint | 93216 | 0 | 0.62 | 2.19 | 5.57 |
| Tomcat embed | 104884 | 0 | 0.57 | 1.26 | 12.72 |

Numbers are machine-local and not a guarantee of production ranking.

## Static files (Glint vs Tomcat DefaultServlet)

- Captured: **2026-11-01 08:56:00 CST** (Asia/Shanghai)
- Method: Java `HttpClient` (HTTP/1.1) virtual-thread workers, warmup 2s + measure 5s, concurrency 64
- Glint: `staticFiles` with default StaticOptions (small-file cache + zero-copy for large files)
- Tomcat: embed 10.1.24.0 `DefaultServlet` / `initWebappDefaults`, docBase = temp dir
- Re-run: `powershell -ExecutionPolicy Bypass -File scripts/run-bench.ps1`

| Size | Server | req/s | errors | p50 (ms) | p99 (ms) | max (ms) |
|------|--------|------:|-------:|---------:|---------:|---------:|
| 1 KiB | Glint | 97073 | 0 | 0.66 | 2.28 | 23.18 |
| 1 KiB | Tomcat DefaultServlet | 23775 | 0 | 1.96 | 5.06 | 170.73 |
| 100 KiB | Glint | 14760 | 0 | 3.86 | 6.31 | 165.75 |
| 100 KiB | Tomcat DefaultServlet | 10714 | 0 | 5.68 | 12.94 | 25.70 |
| 2 MiB | Glint | 563 | 0 | 111.38 | 152.70 | 161.19 |
| 2 MiB | Tomcat DefaultServlet | 575 | 0 | 107.76 | 142.79 | 162.96 |

Numbers are machine-local and not a guarantee of production ranking.
## Realistic suite (Glint vs Tomcat vs Tomcat-VT, same app logic)

- Captured: **2026-11-01 09:55:06 CST** (Asia/Shanghai)
- CPU: AMD Ryzen 7 7840HS w/ Radeon 780M Graphics
- Logical cores: 16
- RAM: 31.2 GiB
- OS: Windows 11 10.0 amd64
- JDK: OpenJDK 64-Bit Server VM 21.0.8+9-LTS
- Tomcat: 10.1.24.0 (embedded, default NIO connector: maxThreads 200, acceptCount 100)
- Tomcat-VT: 10.1.24.0 (same embedded NIO connector and servlet app; worker executor StandardVirtualThreadExecutor; acceptor and poller stay platform threads)
- Server JVM flags: -Xms256m -Xmx1g -XX:+UseG1GC (same flags, one child JVM per server)
- Server modes: `glint` is virtual-thread-native (one virtual thread per connection); `tomcat` is embedded Tomcat on the default NIO connector with a platform thread pool (maxThreads 200); `tomcat-vt` is the same servlet app and the same NIO connector with `StandardVirtualThreadExecutor` (one virtual thread per request task). Acceptor and poller stay platform threads. Tomcat-VT is still NIO/Servlet, not virtual-thread-native like Glint.
- Servers in this run: `glint`, `tomcat`, `tomcat-vt`
- Load generator: `RealisticBench`, raw HTTP/1.1 over blocking sockets, one virtual thread per client, closed loop, same machine (loopback), separate JVM from the servers
- Each cell: warmup 3s + measure 8s, 3 rounds rotating which server goes first; the median round by req/s is shown with the min-max req/s across rounds
- Handlers: identical `BenchApp` code on every server; Glint uses default settings except maxRequestBodyBytes 16 MiB (for uploads); `tomcat` uses embedded defaults; `tomcat-vt` uses that same servlet app with a virtual-thread executor on the connector
- Heap peak and platform-thread peak are sampled every 50 ms inside the server JVM during the measured window; RSS is the process working set right after it. Platform-thread counts do not include virtual threads, so Tomcat-VT worker threads are absent from that column the same way Glint's are
- Re-run: `powershell -File scripts/run-bench.ps1` (all suites, three realistic servers) or `powershell -File scripts/run-bench.ps1 -Servers "glint,tomcat"` for the original pair. A single suite is `java -cp ... -Dglint.bench.servers=glint,tomcat,tomcat-vt io.github.glint.bench.RealisticBench 3 3 8`

Scenarios:

- blocking-50ms: `GET /api/slow`, handler sleeps 50 ms (slow downstream), keep-alive
- json+db+cpu: `POST /api/order` with a 7 KiB JSON order (60 lines): parse, price lines, SHA-256 x5 over the body, sleep 15 ms, serialize a ~5 KiB JSON response
- mixed-traffic: 70% `GET /api/item` (10 ms wait + 1 KiB JSON), 20% 4 KiB static CSS, 10% 1 MiB static file
- keep-alive vs conn-per-req: `GET /api/hello` tiny JSON; conn-per-req sends `Connection: close` and opens a new TCP connection for every request
- upload-1MiB: `POST /api/upload` with a 1 MiB body; handler streams via bodyStream() and returns CRC32

| Scenario | Clients | Server | req/s (median) | req/s min-max | p50 ms | p99 ms | p99.9 ms | errors | connect errors | timeouts | heap peak MiB | RSS MiB | platform threads peak |
|----------|--------:|--------|---------------:|---------------|-------:|-------:|---------:|-------:|---------------:|---------:|--------------:|--------:|----------------------:|
| blocking-50ms | 100 | Glint | 1938 | 1938-1939 | 51.17 | 52.78 | 53.72 | 0 | 0 | 0 | 154 | 230 | 34 |
| blocking-50ms | 100 | Tomcat | 1574 | 1573-1577 | 63.18 | 64.73 | 64.91 | 0 | 0 | 0 | 91 | 183 | 114 |
| blocking-50ms | 100 | Tomcat-VT | 1563 | 1563-1563 | 63.44 | 66.04 | 67.09 | 0 | 0 | 0 | 96 | 188 | 29 |
| blocking-50ms | 500 | Glint | 9699 | 9697-9709 | 51.18 | 52.72 | 53.51 | 0 | 0 | 0 | 156 | 235 | 34 |
| blocking-50ms | 500 | Tomcat | 3162 | 3157-3165 | 156.02 | 173.88 | 175.10 | 0 | 0 | 0 | 156 | 247 | 212 |
| blocking-50ms | 500 | Tomcat-VT | 7937 | 7917-7944 | 62.29 | 64.97 | 73.34 | 0 | 159 | 0 | 212 | 342 | 29 |
| blocking-50ms | 1000 | Glint | 19362 | 19350-19364 | 51.21 | 52.91 | 53.74 | 0 | 0 | 0 | 180 | 263 | 34 |
| blocking-50ms | 1000 | Tomcat | 3110 | 3100-3112 | 311.16 | 321.57 | 329.92 | 0 | 0 | 0 | 102 | 291 | 212 |
| blocking-50ms | 1000 | Tomcat-VT | 15888 | 15831-15909 | 62.30 | 69.60 | 77.47 | 0 | 792 | 0 | 534 | 796 | 29 |
| blocking-50ms | 2000 | Glint | 38454 | 38386-38523 | 51.50 | 54.18 | 55.48 | 0 | 0 | 0 | 235 | 323 | 34 |
| blocking-50ms | 2000 | Tomcat | 2980 | 2979-2989 | 623.98 | 640.19 | 642.22 | 0 | 0 | 0 | 118 | 301 | 212 |
| blocking-50ms | 2000 | Tomcat-VT | 29985 | 29825-30381 | 63.52 | 92.16 | 106.10 | 0 | 1115 | 0 | 1010 | 874 | 29 |
| blocking-50ms | 4000 | Glint | 75538 | 74934-75611 | 52.19 | 57.67 | 61.83 | 0 | 0 | 0 | 277 | 380 | 34 |
| blocking-50ms | 4000 | Tomcat | 2734 | 2722-2739 | 1242.54 | 1256.88 | 1267.31 | 0 | 0 | 0 | 179 | 313 | 212 |
| blocking-50ms | 4000 | Tomcat-VT | 38234 | 37607-38596 | 101.23 | 165.62 | 187.71 | 0 | 1705 | 0 | 972 | 1154 | 29 |
| json+db+cpu | 200 | Glint | 11919 | 11858-11920 | 16.52 | 21.12 | 22.30 | 0 | 0 | 0 | 272 | 406 | 34 |
| json+db+cpu | 200 | Tomcat | 11977 | 11855-12029 | 16.43 | 21.32 | 23.24 | 0 | 0 | 0 | 259 | 418 | 212 |
| json+db+cpu | 200 | Tomcat-VT | 6441 | 6421-6442 | 31.31 | 36.80 | 38.51 | 0 | 0 | 0 | 177 | 374 | 29 |
| json+db+cpu | 1000 | Glint | 30906 | 21173-34191 | 32.63 | 54.22 | 65.49 | 0 | 0 | 0 | 601 | 698 | 34 |
| json+db+cpu | 1000 | Tomcat | 12285 | 12186-12287 | 80.39 | 86.41 | 87.99 | 0 | 0 | 0 | 340 | 497 | 212 |
| json+db+cpu | 1000 | Tomcat-VT | 16434 | 15076-16736 | 58.91 | 105.29 | 119.57 | 0 | 0 | 0 | 992 | 1133 | 29 |
| mixed-traffic | 500 | Glint | 22664 | 20704-34716 | 23.02 | 35.49 | 43.63 | 0 | 0 | 0 | 163 | 361 | 48 |
| mixed-traffic | 500 | Tomcat | 18760 | 16855-19258 | 28.75 | 44.08 | 47.05 | 0 | 0 | 0 | 343 | 510 | 212 |
| mixed-traffic | 500 | Tomcat-VT | 22483 | 18048-23327 | 22.49 | 48.74 | 61.28 | 0 | 0 | 0 | 411 | 683 | 146 |
| keep-alive | 64 | Glint | 103508 | 103158-103755 | 0.60 | 1.14 | 2.22 | 0 | 0 | 0 | 160 | 362 | 47 |
| keep-alive | 64 | Tomcat | 104795 | 104770-104993 | 0.59 | 1.11 | 2.25 | 0 | 0 | 0 | 358 | 506 | 212 |
| keep-alive | 64 | Tomcat-VT | 110092 | 109554-110173 | 0.56 | 1.03 | 2.31 | 0 | 0 | 0 | 175 | 419 | 145 |
| conn-per-req | 64 | Glint | 31620 | 31576-31623 | 1.93 | 3.25 | 5.89 | 0 | 0 | 0 | 159 | 355 | 47 |
| conn-per-req | 64 | Tomcat | 25514 | 25389-25525 | 2.40 | 3.79 | 5.40 | 0 | 0 | 0 | 359 | 507 | 212 |
| conn-per-req | 64 | Tomcat-VT | 25902 | 25879-25934 | 2.36 | 4.06 | 5.67 | 0 | 0 | 0 | 175 | 419 | 144 |
| upload-1MiB | 64 | Glint | 8185 | 7629-8564 | 7.10 | 21.23 | 32.31 | 0 | 0 | 0 | 155 | 301 | 33 |
| upload-1MiB | 64 | Tomcat | 7631 | 5216-7693 | 7.54 | 29.22 | 56.05 | 0 | 0 | 0 | 166 | 274 | 78 |
| upload-1MiB | 64 | Tomcat-VT | 9390 | 9069-9409 | 6.03 | 23.82 | 37.70 | 0 | 0 | 0 | 164 | 266 | 82 |

Mixed-traffic breakdown (median round):

| Server | Class | ok requests | p50 ms | p99 ms | p99.9 ms |
|--------|-------|------------:|-------:|-------:|---------:|
| Glint | api | 126920 | 24.72 | 35.73 | 43.51 |
| Glint | static-4KiB | 36252 | 13.27 | 23.38 | 29.29 |
| Glint | static-1MiB | 18140 | 19.07 | 38.64 | 47.71 |
| Tomcat | api | 105042 | 30.64 | 44.78 | 47.46 |
| Tomcat | static-4KiB | 29984 | 16.33 | 27.51 | 31.33 |
| Tomcat | static-1MiB | 15051 | 20.84 | 35.23 | 45.94 |
| Tomcat-VT | api | 125834 | 28.11 | 49.41 | 60.51 |
| Tomcat-VT | static-4KiB | 35963 | 6.53 | 27.34 | 37.70 |
| Tomcat-VT | static-1MiB | 18067 | 13.94 | 54.10 | 78.41 |

errors = reset/EOF/non-200 on an established connection; connect errors = failed TCP connect (e.g. accept backlog full); timeouts = no response within 10 s. The generator closes sockets with SO_LINGER=0 (RST) to avoid client TIME_WAIT exhaustion.

Caveats: closed-loop load (a slow server also slows the generator, so tail latency is understated versus an open-loop tool such as wrk2); generator and server share the same CPU; Glint streams request bodies via bodyStream()/bodyChannel() (bodyBytes() still buffers when called); platform-thread counts exclude virtual threads by design (Glint connection threads and Tomcat-VT request workers). Tomcat-VT is still the NIO connector plus the Servlet container; only the worker executor is virtual threads. simulated waits use Thread.sleep, and on Windows JDK 21 virtual-thread timed sleeps are coarser under load than platform-thread sleeps, which penalizes Glint and Tomcat-VT in sleep-heavy cells.
