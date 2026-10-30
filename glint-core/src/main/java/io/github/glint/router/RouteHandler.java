package io.github.glint.router;

import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;

/** Handler for a matched route. */
@FunctionalInterface
public interface RouteHandler {

    void handle(HttpRequest request, HttpResponse response) throws Exception;
}
