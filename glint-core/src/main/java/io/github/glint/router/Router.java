package io.github.glint.router;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Simple ordered router. First matching route wins.
 * Supports path parameters like {@code /users/{id}}.
 */
public final class Router {

    private final List<Route> routes = new ArrayList<>();

    public void add(HttpMethod method, String path, RouteHandler handler) {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(handler, "handler");
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("path must start with /: " + path);
        }
        routes.add(new Route(method, path, handler));
    }

    /**
     * Find a handler for the request, populating path params on match.
     *
     * @return the handler, or {@code null} if no route matches
     */
    public RouteHandler match(HttpRequest request) {
        for (Route route : routes) {
            if (route.matches(request)) {
                return route.handler();
            }
        }
        return null;
    }

    public List<Route> routes() {
        return List.copyOf(routes);
    }
}
