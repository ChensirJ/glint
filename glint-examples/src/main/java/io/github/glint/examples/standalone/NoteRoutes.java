package io.github.glint.examples.standalone;

import io.github.glint.Glint;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import io.github.glint.http.sse.SseEmitter;
import io.github.glint.http.sse.SseEvent;
import io.github.glint.obs.Trace;
import io.github.glint.router.RouteHandler;
import io.github.glint.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JSON notes API.
 *
 * <pre>
 * GET    /api/notes?done=true|false
 * POST   /api/notes
 * GET    /api/notes/stream
 * GET    /api/notes/{id}
 * PUT    /api/notes/{id}
 * DELETE /api/notes/{id}
 * </pre>
 *
 * {@code /stream} is registered before {@code /{id}} so the literal path wins.
 */
public final class NoteRoutes {

    private static final int MAX_TITLE = 200;
    private static final int MAX_BODY = 4_000;

    private final NoteStore store;

    public NoteRoutes(NoteStore store) {
        this.store = store;
    }

    public void mount(Glint app) {
        app.get("/api/notes", guard(this::list));
        app.post("/api/notes", guard(this::create));
        app.get("/api/notes/stream", guard(this::stream));
        app.get("/api/notes/{id}", guard(this::getOne));
        app.put("/api/notes/{id}", guard(this::replace));
        app.delete("/api/notes/{id}", guard(this::delete));
    }

    private void list(HttpRequest req, HttpResponse resp) throws Exception {
        Boolean doneFilter = doneFilter(req);
        List<Note> notes = Trace.span("notes.list", () -> store.list(doneFilter));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Note note : notes) {
            rows.add(note.toJson());
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("notes", rows);
        JsonBodies.write(resp, 200, body);
    }

    private void create(HttpRequest req, HttpResponse resp) throws Exception {
        Draft draft = readDraft(req);
        Note note = Trace.span("notes.insert", () -> store.create(draft.title, draft.body, draft.done));
        resp.header("Location", "/api/notes/" + note.id());
        JsonBodies.write(resp, 201, note.toJson());
    }

    private void getOne(HttpRequest req, HttpResponse resp) throws Exception {
        long id = parseId(req);
        Optional<Note> found = Trace.span("notes.find", () -> store.find(id));
        if (found.isEmpty()) {
            JsonBodies.error(resp, 404, "Note not found");
            return;
        }
        JsonBodies.write(resp, 200, found.get().toJson());
    }

    private void replace(HttpRequest req, HttpResponse resp) throws Exception {
        long id = parseId(req);
        Draft draft = readDraft(req);
        Optional<Note> updated = Trace.span("notes.update",
                () -> store.replace(id, draft.title, draft.body, draft.done));
        if (updated.isEmpty()) {
            JsonBodies.error(resp, 404, "Note not found");
            return;
        }
        JsonBodies.write(resp, 200, updated.get().toJson());
    }

    private void delete(HttpRequest req, HttpResponse resp) throws Exception {
        long id = parseId(req);
        boolean removed = Trace.span("notes.delete", () -> store.delete(id));
        if (!removed) {
            JsonBodies.error(resp, 404, "Note not found");
            return;
        }
        resp.status(204).empty();
    }

    private void stream(HttpRequest req, HttpResponse resp) throws Exception {
        List<Note> notes = Trace.span("notes.list", () -> store.list(null));
        try (SseEmitter sse = SseEmitter.start(resp)) {
            sse.comment("connected");
            for (Note note : notes) {
                sse.send(SseEvent.builder()
                        .id(Long.toString(note.id()))
                        .event("note")
                        .data(Json.stringify(note.toJson()))
                        .build());
            }
            Map<String, Object> end = new LinkedHashMap<>();
            end.put("count", notes.size());
            sse.send(SseEvent.builder().event("end").data(Json.stringify(end)).build());
        }
    }

    private static RouteHandler guard(RouteHandler next) {
        return (HttpRequest req, HttpResponse resp) -> {
            try {
                next.handle(req, resp);
            } catch (IllegalArgumentException ex) {
                String message = ex.getMessage();
                JsonBodies.error(resp, 400, message == null ? "Bad request" : message);
            }
        };
    }

    private static Boolean doneFilter(HttpRequest req) {
        Optional<String> raw = req.queryParam("done");
        if (raw.isEmpty()) {
            return null;
        }
        String value = raw.get();
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("done must be true or false");
    }

    private static long parseId(HttpRequest req) {
        String raw = req.pathParam("id");
        if (raw == null || raw.isEmpty()) {
            throw new IllegalArgumentException("id is required");
        }
        try {
            long id = Long.parseLong(raw);
            if (id <= 0L) {
                throw new IllegalArgumentException("id must be a positive integer");
            }
            return id;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("id must be a positive integer");
        }
    }

    private static Draft readDraft(HttpRequest req) {
        Map<String, Object> body = JsonBodies.readObject(req.bodyText(req.contentCharset()));
        String title = JsonBodies.stringField(body, "title", true).trim();
        String text = JsonBodies.stringField(body, "body", false);
        boolean done = JsonBodies.booleanField(body, "done", false);
        if (title.isEmpty()) {
            throw new IllegalArgumentException("title is required");
        }
        if (title.length() > MAX_TITLE) {
            throw new IllegalArgumentException("title is too long");
        }
        if (text.length() > MAX_BODY) {
            throw new IllegalArgumentException("body is too long");
        }
        return new Draft(title, text, done);
    }

    private record Draft(String title, String body, boolean done) {
    }
}
