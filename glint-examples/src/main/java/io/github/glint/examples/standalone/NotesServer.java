package io.github.glint.examples.standalone;

import io.github.glint.Glint;
import io.github.glint.http.ServerConfig;
import io.github.glint.middleware.Middlewares;

import java.net.InetAddress;

/**
 * Standalone notes service. No Spring.
 *
 * <p>{@code main} only wires config, middleware, route groups, and startup.
 * Handlers live in {@link HealthRoutes}, {@link HomePage}, and {@link NoteRoutes}.
 * Ctrl+C runs a shutdown hook that calls {@link Glint#stop()} (stop accepting,
 * then drain in-flight work). {@link Thread#join()} keeps the process up until then.
 *
 * <pre>{@code
 * mvn -pl glint-examples -am exec:java
 * ./scripts/run-standalone.sh
 * .\scripts\run-standalone.ps1
 * }</pre>
 *
 * Open http://127.0.0.1:8080/ and http://127.0.0.1:8080/_vt/ .
 */
public final class NotesServer {

    private NotesServer() {
    }

    public static void main(String[] args) throws Exception {
        int port = 8080;
        if (args.length > 0) {
            port = Integer.parseInt(args[0]);
        }
        Glint app = wire(port);
        app.startAsync();
        Runtime.getRuntime().addShutdownHook(new Thread(app::stop, "glint-shutdown"));

        int bound = app.port();
        System.out.println("Notes sample listening on http://127.0.0.1:" + bound + "/");
        System.out.println("API:         http://127.0.0.1:" + bound + "/api/notes");
        System.out.println("Diagnostics: http://127.0.0.1:" + bound + "/_vt/");
        System.out.println("Ctrl+C to stop");
        Thread.currentThread().join();
    }

    /** Config, access log, diagnostics, and route groups. */
    public static Glint wire(int port) throws Exception {
        NoteStore notes = new NoteStore();
        ServerConfig config = ServerConfig.builder()
                .bindAddress(InetAddress.getByName("127.0.0.1"))
                .shutdownWaitMs(5_000L)
                .build();
        Glint app = Glint.create(port)
                .config(config)
                .before(Middlewares.accessLog())
                .diagnostics("/_vt");
        new HealthRoutes().mount(app);
        new HomePage().mount(app);
        new NoteRoutes(notes).mount(app);
        return app;
    }
}
