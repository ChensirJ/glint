package io.github.glint.router;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Simple ordered router. First matching route wins.
 * Supports path parameters like {@code /users/{id}}.
 *
 * <p>HEAD falls back to a matching GET handler (body suppressed by the server).
 * When the path matches another method, {@link Match#methodNotAllowed()} is set
 * so the pipeline can return 405.
 */
public final class Router {

    /** Result of attempting to match a request. */
    public record Match(RouteHandler handler, boolean methodNotAllowed, Set<HttpMethod> allowed) {
        public static Match found(RouteHandler h) {
            return new Match(h, false, Set.of());
        }

        public static Match notAllowed(Set<HttpMethod> allowed) {
            return new Match(null, true, allowed);
        }

        public static Match miss() {
            return new Match(null, false, Set.of());
        }
    }

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
        Match m = matchDetailed(request);
        return m.handler();
    }

    public Match matchDetailed(HttpRequest request) {
        // Exact method match first.
        for (Route route : routes) {
            if (route.matches(request)) {
                return Match.found(route.handler());
            }
        }
        // HEAD → GET fallback.
        if (request.method() == HttpMethod.HEAD) {
            for (Route route : routes) {
                if (route.method() == HttpMethod.GET && route.matchesPath(request)) {
                    return Match.found(route.handler());
                }
            }
        }
        // Path matches another method?
        EnumSet<HttpMethod> allowed = EnumSet.noneOf(HttpMethod.class);
        for (Route route : routes) {
            if (route.matchesPath(request)) {
                allowed.add(route.method());
            }
        }
        if (!allowed.isEmpty()) {
            if (allowed.contains(HttpMethod.GET)) {
                allowed.add(HttpMethod.HEAD);
            }
            return Match.notAllowed(allowed);
        }
        return Match.miss();
    }

    public List<Route> routes() {
        return List.copyOf(routes);
    }
}
