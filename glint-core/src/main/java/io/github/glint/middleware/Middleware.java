package io.github.glint.middleware;

import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import io.github.glint.router.RouteHandler;

/**
 * Middleware that can inspect / mutate a request before delegating to {@code next}.
 */
@FunctionalInterface
public interface Middleware {

    void handle(HttpRequest request, HttpResponse response, RouteHandler next) throws Exception;
}
