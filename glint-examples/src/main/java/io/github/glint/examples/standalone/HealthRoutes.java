package io.github.glint.examples.standalone;

import io.github.glint.Glint;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code GET /health}. */
public final class HealthRoutes {

    public void mount(Glint app) {
        app.get("/health", this::health);
    }

    private void health(HttpRequest req, HttpResponse resp) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "up");
        JsonBodies.write(resp, 200, body);
    }
}
