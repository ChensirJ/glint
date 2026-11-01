package io.github.glint.examples.standalone;

import io.github.glint.Glint;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;

/** {@code GET /} index for the notes sample. */
public final class HomePage {

    private static final String PAGE = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <title>Glint notes</title>
            </head>
            <body>
              <h1>Glint notes</h1>
              <p>Small standalone app on Glint. No Spring. Access log on stdout. Ctrl+C stops the server.</p>
              <ul>
                <li><a href="/health">GET /health</a></li>
                <li><a href="/api/notes">GET /api/notes</a> and <code>?done=true</code></li>
                <li><code>GET /api/notes/{id}</code>, <code>PUT</code>, <code>DELETE</code></li>
                <li><a href="/api/notes/stream">GET /api/notes/stream</a> (server-sent events)</li>
                <li><a href="/_vt/">/_vt diagnostics</a></li>
              </ul>
              <p>POST /api/notes</p>
              <pre>{"title":"Buy milk","body":"oat","done":false}</pre>
            </body>
            </html>
            """;

    public void mount(Glint app) {
        app.get("/", this::index);
    }

    private void index(HttpRequest req, HttpResponse resp) {
        resp.html(PAGE);
    }
}
