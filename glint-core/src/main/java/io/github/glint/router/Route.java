package io.github.glint.router;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A single HTTP route with optional path parameters such as {@code /users/{id}}.
 */
public final class Route {

    private final HttpMethod method;
    private final String pattern;
    private final RouteHandler handler;
    private final Pattern regex;
    private final List<String> paramNames;

    public Route(HttpMethod method, String pattern, RouteHandler handler) {
        this.method = method;
        this.pattern = pattern;
        this.handler = handler;
        this.paramNames = new ArrayList<>();
        this.regex = compile(pattern, paramNames);
    }

    public HttpMethod method() {
        return method;
    }

    public String pattern() {
        return pattern;
    }

    public RouteHandler handler() {
        return handler;
    }

    /**
     * Attempt to match {@code request}. On success, path params are written
     * into the request and {@code true} is returned.
     */
    public boolean matches(HttpRequest request) {
        if (request.method() != method) {
            return false;
        }
        Matcher m = regex.matcher(request.path());
        if (!m.matches()) {
            return false;
        }
        for (int i = 0; i < paramNames.size(); i++) {
            request.pathParam(paramNames.get(i), m.group(i + 1));
        }
        return true;
    }

    private static boolean isRegexSpecial(char c) {
        return switch (c) {
            case '.', '+', '*', '?', '^', '$', '(', ')', '[', ']', '{', '}', '|' -> true;
            default -> c == 92; // backslash
        };
    }

    private static Pattern compile(String pattern, List<String> names) {
        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < pattern.length()) {
            char c = pattern.charAt(i);
            if (c == '{') {
                int end = pattern.indexOf('}', i);
                if (end < 0) {
                    throw new IllegalArgumentException("Unclosed path param in " + pattern);
                }
                String name = pattern.substring(i + 1, end);
                names.add(name);
                sb.append("([^/]+)");
                i = end + 1;
            } else {
                if (isRegexSpecial(c)) {
                    sb.append((char) 92);
                }
                sb.append(c);
                i++;
            }
        }
        sb.append('$');
        return Pattern.compile(sb.toString());
    }
}
