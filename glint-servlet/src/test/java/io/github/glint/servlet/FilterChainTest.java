package io.github.glint.servlet;

import io.github.glint.http.HttpMethod;
import io.github.glint.http.HttpRequest;
import io.github.glint.http.HttpResponse;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.http.HttpFilter;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Registered servlet filters run in order before the servlet, honor URL
 * patterns / servlet names / dispatcher types, and can short-circuit.
 */
class FilterChainTest {

    private final List<String> events = new ArrayList<>();

    private GlintServletContext context() {
        GlintServletContext ctx = new GlintServletContext();
        ctx.addServlet("dispatcher", new HttpServlet() {
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                events.add("servlet");
                resp.setContentType("text/plain");
                resp.getWriter().write("servlet:" + req.getAttribute("seen"));
            }
        }).addMapping("/");
        return ctx;
    }

    private Filter recording(String name) {
        return new HttpFilter() {
            @Override
            protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws IOException, jakarta.servlet.ServletException {
                events.add(name);
                req.setAttribute("seen", name);
                chain.doFilter(req, res);
            }
        };
    }

    private String run(GlintServletContext ctx, String path) throws Exception {
        ctx.initialize();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        HttpResponse resp = new HttpResponse(out);
        new GlintServletDispatcher(ctx).handle(
                new HttpRequest(HttpMethod.GET, path, Map.of(), new byte[0]), resp);
        resp.finish();
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void filtersRunInRegistrationOrderBeforeServlet() throws Exception {
        GlintServletContext ctx = context();
        ctx.addFilter("a", recording("a")).addMappingForUrlPatterns(null, false, "/*");
        ctx.addFilter("b", recording("b")).addMappingForServletNames(null, false, "dispatcher");
        ctx.addFilter("c", recording("c")).addMappingForUrlPatterns(null, false, "/api/*");
        String raw = run(ctx, "/api/x");
        // URL-pattern mappings (a, c) precede servlet-name mappings (b).
        assertEquals(List.of("a", "c", "b", "servlet"), events);
        assertTrue(raw.contains("servlet:b"), raw);
    }

    @Test
    void urlPatternsAndDispatcherTypesAreHonored() throws Exception {
        GlintServletContext ctx = context();
        ctx.addFilter("api", recording("api")).addMappingForUrlPatterns(null, false, "/api/*");
        ctx.addFilter("json", recording("json")).addMappingForUrlPatterns(null, false, "*.json");
        ctx.addFilter("err", recording("err"))
                .addMappingForUrlPatterns(EnumSet.of(DispatcherType.ERROR), false, "/*");
        run(ctx, "/other/data.json");
        assertEquals(List.of("json", "servlet"), events);
    }

    @Test
    void filterCanShortCircuit() throws Exception {
        GlintServletContext ctx = context();
        ctx.addFilter("deny", new HttpFilter() {
            @Override
            protected void doFilter(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
                    throws IOException {
                events.add("deny");
                res.setStatus(403);
                res.setContentType("text/plain");
                res.getWriter().write("blocked");
            }
        }).addMappingForUrlPatterns(null, false, "/*");
        String raw = run(ctx, "/secret");
        assertEquals(List.of("deny"), events);
        assertTrue(raw.startsWith("HTTP/1.1 403"), raw);
        assertTrue(raw.endsWith("blocked"), raw);
    }

    @Test
    void filterInitReceivesConfigAndDuplicateNameIsRejected() throws Exception {
        GlintServletContext ctx = context();
        List<String> init = new ArrayList<>();
        var reg = ctx.addFilter("cfg", new HttpFilter() {
            @Override
            public void init(FilterConfig config) {
                init.add(config.getFilterName() + "=" + config.getInitParameter("k"));
            }
        });
        reg.setInitParameter("k", "v");
        reg.addMappingForUrlPatterns(null, true, "/*");
        assertNull(ctx.addFilter("cfg", recording("dup")));
        ctx.initialize();
        assertEquals(List.of("cfg=v"), init);
        assertEquals(List.of("/*"), List.copyOf(ctx.getFilterRegistration("cfg").getUrlPatternMappings()));
    }

    @Test
    void urlPatternMatching() {
        assertTrue(GlintServletContext.urlPatternMatches("/*", "/a"));
        assertTrue(GlintServletContext.urlPatternMatches("/api/*", "/api"));
        assertTrue(GlintServletContext.urlPatternMatches("/api/*", "/api/v1/x"));
        assertFalse(GlintServletContext.urlPatternMatches("/api/*", "/apix"));
        assertTrue(GlintServletContext.urlPatternMatches("*.css", "/s/app.css"));
        assertTrue(GlintServletContext.urlPatternMatches("/exact", "/exact"));
        assertFalse(GlintServletContext.urlPatternMatches("/exact", "/exact/more"));
    }
}