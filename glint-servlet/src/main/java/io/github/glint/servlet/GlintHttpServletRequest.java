package io.github.glint.servlet;

import io.github.glint.http.HttpRequest;
import io.github.glint.http.ServerConfig;
import io.github.glint.http.multipart.MultipartForm;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletConnection;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletMapping;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.servlet.http.HttpUpgradeHandler;
import jakarta.servlet.http.Part;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link HttpServletRequest} wrapping a Glint {@link HttpRequest}.
 *
 * <p>Supports cookie sessions ({@code JSESSIONID}), multipart via
 * {@link #getParts()}, and a virtual-thread {@link AsyncContext}.
 */
public final class GlintHttpServletRequest implements HttpServletRequest {

    private final HttpRequest delegate;
    private final ServletContext servletContext;
    private final GlintHttpServletResponse response;
    private final Map<String, Object> attributes = new HashMap<>();
    private String characterEncoding = StandardCharsets.UTF_8.name();
    private final Map<String, String[]> parameters;
    private GlintHttpSession session;
    private boolean sessionFromCookie;
    private Collection<Part> cachedParts;
    private boolean multipartFieldsMerged;
    private GlintAsyncContext asyncContext;
    private boolean asyncStarted;

    public GlintHttpServletRequest(HttpRequest delegate,
                                   ServletContext servletContext,
                                   GlintHttpServletResponse response) {
        this.delegate = delegate;
        this.servletContext = servletContext;
        this.response = response;
        this.parameters = parseQuery(delegate.query());
        String sid = readSessionIdFromCookie();
        if (sid != null) {
            GlintHttpSession existing = GlintSessionManager.get(sid);
            if (existing != null) {
                this.session = existing;
                this.sessionFromCookie = true;
            }
        }
    }

    private String readSessionIdFromCookie() {
        String raw = delegate.header("cookie").orElse(null);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (String part : raw.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0) {
                String name = p.substring(0, eq).trim();
                if (GlintSessionManager.COOKIE_NAME.equals(name)) {
                    return p.substring(eq + 1).trim();
                }
            }
        }
        return null;
    }

    private static Map<String, String[]> parseQuery(String query) {
        Map<String, String[]> map = new HashMap<>();
        if (query == null || query.isEmpty()) {
            return map;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq >= 0 ? pair.substring(0, eq) : pair;
            String v = eq >= 0 ? pair.substring(eq + 1) : "";
            map.merge(k, new String[]{v}, (a, b) -> {
                String[] n = new String[a.length + 1];
                System.arraycopy(a, 0, n, 0, a.length);
                n[a.length] = b[0];
                return n;
            });
        }
        return map;
    }

    @Override public String getAuthType() { return null; }
    @Override public Cookie[] getCookies() {
        String raw = delegate.header("cookie").orElse(null);
        if (raw == null || raw.isBlank()) {
            return new Cookie[0];
        }
        List<Cookie> list = new ArrayList<>();
        for (String part : raw.split(";")) {
            String p = part.trim();
            int eq = p.indexOf('=');
            if (eq > 0) {
                list.add(new Cookie(p.substring(0, eq).trim(), p.substring(eq + 1).trim()));
            }
        }
        return list.toArray(Cookie[]::new);
    }
    @Override public long getDateHeader(String name) { return -1; }
    @Override public String getHeader(String name) { return delegate.header(name).orElse(null); }
    @Override public Enumeration<String> getHeaders(String name) {
        String v = getHeader(name);
        return v == null ? Collections.emptyEnumeration() : Collections.enumeration(List.of(v));
    }
    @Override public Enumeration<String> getHeaderNames() {
        return Collections.enumeration(new ArrayList<>(delegate.headers().keySet()));
    }
    @Override public int getIntHeader(String name) {
        String v = getHeader(name);
        return v == null ? -1 : Integer.parseInt(v);
    }
    @Override public String getMethod() { return delegate.method().name(); }
    @Override public String getPathInfo() { return null; }
    @Override public String getPathTranslated() { return null; }
    @Override public String getContextPath() { return ""; }
    @Override public String getQueryString() {
        return delegate.query().isEmpty() ? null : delegate.query();
    }
    @Override public String getRemoteUser() { return null; }
    @Override public boolean isUserInRole(String role) { return false; }
    @Override public Principal getUserPrincipal() { return null; }
    @Override public String getRequestedSessionId() {
        return session != null ? session.getId() : readSessionIdFromCookie();
    }
    @Override public String getRequestURI() { return delegate.path(); }
    @Override public StringBuffer getRequestURL() {
        return new StringBuffer("http://localhost").append(delegate.path());
    }
    @Override public String getServletPath() { return delegate.path(); }

    @Override
    public HttpSession getSession(boolean create) {
        if (session != null && !session.expired()) {
            session.touch();
            return session;
        }
        if (!create) {
            return null;
        }
        session = GlintSessionManager.create(servletContext);
        sessionFromCookie = false;
        if (response != null) {
            Cookie c = new Cookie(GlintSessionManager.COOKIE_NAME, session.getId());
            c.setPath("/");
            c.setHttpOnly(true);
            response.addCookie(c);
        }
        return session;
    }

    @Override
    public HttpSession getSession() {
        return getSession(true);
    }

    @Override
    public String changeSessionId() {
        HttpSession old = getSession(false);
        if (old == null) {
            throw new IllegalStateException("no session");
        }
        old.invalidate();
        session = null;
        return getSession(true).getId();
    }

    @Override public boolean isRequestedSessionIdValid() {
        return session != null && !session.expired();
    }
    @Override public boolean isRequestedSessionIdFromCookie() { return sessionFromCookie; }
    @Override public boolean isRequestedSessionIdFromURL() { return false; }
    @Override public boolean authenticate(HttpServletResponse response) { return false; }
    @Override public void login(String username, String password) throws ServletException {
        throw new ServletException("unsupported");
    }
    @Override public void logout() { }

    @Override
    public Collection<Part> getParts() throws IOException, ServletException {
        if (cachedParts != null) {
            return cachedParts;
        }
        String ct = getContentType();
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            cachedParts = List.of();
            return cachedParts;
        }
        try {
            MultipartForm form = delegate.multipart(ServerConfig.defaults());
            List<Part> parts = new ArrayList<>();
            for (io.github.glint.http.multipart.Part p : form.all()) {
                parts.add(new GlintServletPart(p));
            }
            cachedParts = Collections.unmodifiableList(parts);
            return cachedParts;
        } catch (RuntimeException e) {
            throw new ServletException("multipart parse failed", e);
        }
    }

    @Override
    public Part getPart(String name) throws IOException, ServletException {
        for (Part p : getParts()) {
            if (p.getName().equals(name)) {
                return p;
            }
        }
        return null;
    }

    @Override public <T extends HttpUpgradeHandler> T upgrade(Class<T> handlerClass) {
        throw new UnsupportedOperationException("upgrade");
    }
    @Override public HttpServletMapping getHttpServletMapping() {
        return new HttpServletMapping() {
            @Override public String getMatchValue() { return ""; }
            @Override public String getPattern() { return "/*"; }
            @Override public String getServletName() { return "dispatcher"; }
            @Override public jakarta.servlet.http.MappingMatch getMappingMatch() {
                return jakarta.servlet.http.MappingMatch.DEFAULT;
            }
        };
    }

    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }
    @Override public String getCharacterEncoding() { return characterEncoding; }
    @Override public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
        this.characterEncoding = env;
    }
    @Override public int getContentLength() {
        long n = getContentLengthLong();
        return n > Integer.MAX_VALUE ? -1 : (int) n;
    }
    @Override public long getContentLengthLong() { return delegate.contentLength(); }
    @Override public String getContentType() { return getHeader("content-type"); }
    @Override public ServletInputStream getInputStream() {
        InputStream raw = delegate.bodyStream();
        return new ServletInputStream() {
            private boolean finished;
            @Override public boolean isFinished() { return finished; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(jakarta.servlet.ReadListener readListener) { }
            @Override public int read() throws IOException {
                int b = raw.read();
                if (b < 0) {
                    finished = true;
                }
                return b;
            }
            @Override public int read(byte[] b, int off, int len) throws IOException {
                int n = raw.read(b, off, len);
                if (n < 0) {
                    finished = true;
                }
                return n;
            }
        };
    }
    /**
     * Servlet containers expose multipart text fields as request parameters
     * (Spring's {@code StandardMultipartHttpServletRequest} relies on this).
     * Merged lazily so plain requests never buffer their body.
     */
    private Map<String, String[]> effectiveParameters() {
        if (multipartFieldsMerged) {
            return parameters;
        }
        multipartFieldsMerged = true;
        String ct = getContentType();
        if (ct == null || !ct.toLowerCase(Locale.ROOT).startsWith("multipart/")) {
            return parameters;
        }
        try {
            for (Part part : getParts()) {
                if (part.getSubmittedFileName() != null) {
                    continue;
                }
                String value;
                try (InputStream in = part.getInputStream()) {
                    value = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                }
                String[] prev = parameters.get(part.getName());
                if (prev == null) {
                    parameters.put(part.getName(), new String[]{value});
                } else {
                    String[] next = java.util.Arrays.copyOf(prev, prev.length + 1);
                    next[prev.length] = value;
                    parameters.put(part.getName(), next);
                }
            }
        } catch (IOException | ServletException e) {
            throw new IllegalStateException("multipart parse failed", e);
        }
        return parameters;
    }
    @Override public String getParameter(String name) {
        String[] v = effectiveParameters().get(name);
        return v == null || v.length == 0 ? null : v[0];
    }
    @Override public Enumeration<String> getParameterNames() {
        return Collections.enumeration(effectiveParameters().keySet());
    }
    @Override public String[] getParameterValues(String name) { return effectiveParameters().get(name); }
    @Override public Map<String, String[]> getParameterMap() {
        return Collections.unmodifiableMap(effectiveParameters());
    }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public String getScheme() { return "http"; }
    @Override public String getServerName() { return "localhost"; }
    @Override public int getServerPort() { return 80; }
    @Override public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(delegate.bodyStream(), StandardCharsets.UTF_8));
    }
    @Override public String getRemoteAddr() { return "127.0.0.1"; }
    @Override public String getRemoteHost() { return "127.0.0.1"; }
    @Override public void setAttribute(String name, Object o) { attributes.put(name, o); }
    @Override public void removeAttribute(String name) { attributes.remove(name); }
    @Override public Locale getLocale() { return Locale.getDefault(); }
    @Override public Enumeration<Locale> getLocales() {
        return Collections.enumeration(List.of(Locale.getDefault()));
    }
    @Override public boolean isSecure() { return false; }
    @Override public RequestDispatcher getRequestDispatcher(String path) { return null; }
    @Override public int getRemotePort() { return 0; }
    @Override public String getLocalName() { return "localhost"; }
    @Override public String getLocalAddr() { return "127.0.0.1"; }
    @Override public int getLocalPort() { return 80; }
    @Override public ServletContext getServletContext() { return servletContext; }

    @Override
    public AsyncContext startAsync() {
        return startAsync(this, response);
    }

    @Override
    public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        if (asyncContext == null) {
            asyncContext = new GlintAsyncContext(servletRequest, servletResponse, response);
        }
        asyncStarted = true;
        return asyncContext;
    }

    @Override public boolean isAsyncStarted() { return asyncStarted; }
    @Override public boolean isAsyncSupported() { return true; }
    @Override public AsyncContext getAsyncContext() {
        if (asyncContext == null) {
            throw new IllegalStateException("async not started");
        }
        return asyncContext;
    }
    @Override public DispatcherType getDispatcherType() { return DispatcherType.REQUEST; }
    @Override public String getRequestId() { return ""; }
    @Override public String getProtocolRequestId() { return ""; }
    @Override public ServletConnection getServletConnection() {
        return new ServletConnection() {
            @Override public String getConnectionId() { return ""; }
            @Override public String getProtocol() { return "HTTP/1.1"; }
            @Override public String getProtocolConnectionId() { return ""; }
            @Override public boolean isSecure() { return false; }
        };
    }
}
