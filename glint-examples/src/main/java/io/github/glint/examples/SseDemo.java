package io.github.glint.examples;

import io.github.glint.Glint;
import io.github.glint.http.sse.SseEmitter;
import io.github.glint.http.sse.SseEvent;
import io.github.glint.middleware.Middlewares;

/**
 * Minimal Server-Sent Events demo.
 *
 * <pre>{@code
 * mvn -pl glint-examples -am exec:java -Dexec.mainClass=io.github.glint.examples.SseDemo
 * curl -N http://127.0.0.1:8081/events
 * }</pre>
 */
public final class SseDemo {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8081;

        Glint.create(port)
                .before(Middlewares.accessLogJson())
                .get("/events", (req, resp) -> {
                    try (SseEmitter sse = SseEmitter.start(resp)) {
                        sse.comment("connected");
                        for (int i = 1; i <= 5; i++) {
                            sse.send(SseEvent.builder()
                                    .id(Integer.toString(i))
                                    .event("tick")
                                    .data("{\"n\":" + i + "}")
                                    .build());
                            Thread.sleep(500);
                        }
                        sse.send(SseEvent.builder().event("done").data("bye").build());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                })
                .get("/", (req, resp) -> resp.html("""
                        <!DOCTYPE html>
                        <html><body>
                        <h1>Glint SSE demo</h1>
                        <pre id="log"></pre>
                        <script>
                        const log = document.getElementById('log');
                        const es = new EventSource('/events');
                        es.addEventListener('tick', e => { log.textContent += e.data + '\\n'; });
                        es.addEventListener('done', e => { log.textContent += 'done\\n'; es.close(); });
                        </script>
                        </body></html>
                        """))
                .startAsync();

        System.out.println("SSE demo on http://127.0.0.1:" + port + "/");
        System.out.println("Stream:  http://127.0.0.1:" + port + "/events");
        Thread.currentThread().join();
    }
}