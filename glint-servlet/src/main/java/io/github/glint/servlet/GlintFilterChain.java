package io.github.glint.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.util.List;

/**
 * Servlet {@link FilterChain} that walks the matched filters in order and then
 * invokes the target servlet. A filter short-circuits by not calling
 * {@link #doFilter}.
 */
public final class GlintFilterChain implements FilterChain {

    private final List<Filter> filters;
    private final Servlet servlet;
    private int pos;

    public GlintFilterChain(List<Filter> filters, Servlet servlet) {
        this.filters = filters;
        this.servlet = servlet;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
        if (pos < filters.size()) {
            Filter next = filters.get(pos++);
            next.doFilter(request, response, this);
            return;
        }
        if (servlet != null) {
            servlet.service(request, response);
        }
    }
}