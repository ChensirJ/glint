package io.github.glint.http;

/**
 * Low-level handler invoked by {@link VtServer} for each request.
 * Prefer {@link io.github.glint.router.RouteHandler} when using the fluent API.
 */
@FunctionalInterface
public interface RequestHandler {

    void handle(HttpRequest request, HttpResponse response) throws Exception;
}
