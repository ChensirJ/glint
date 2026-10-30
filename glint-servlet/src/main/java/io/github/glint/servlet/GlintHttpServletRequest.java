package io.github.glint.servlet;

import io.github.glint.http.HttpRequest;
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
import java.io.ByteArrayInputStream;
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
 * Minimal {@link HttpServletRequest} wrapping a Glint {@link HttpRequest}.
 * Enough for Spring {@code DispatcherServlet} Hello World controllers.
 * Full Servlet 6 compliance is intentionally out of scope for 0.1.
 */
public final class GlintHttpServletRequest implements HttpServletRequest {

    private final HttpRequest delegate;
    private final ServletContext servletContext;
    private final Map<String, Object> attributes = new HashMap<>();
    private String characterEncoding = StandardCharsets.UTF_8.name();
    private final Map<String, String[]> parameters;

    public GlintHttpServletRequest(HttpRequest delegate, ServletContext servletContext) {
        this.delegate = delegate;
        this.servletContext = servletContext;
        this.parameters = parseQuery(delegate.query());
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
    @Override public Cookie[] getCookies() { return new Cookie[0]; }
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
    @Override public String getRequestedSessionId() { return null; }
    @Override public String getRequestURI() { return delegate.path(); }
    @Override public StringBuffer getRequestURL() {
        return new StringBuffer("http://localhost").append(delegate.path());
    }
    @Override public String getServletPath() { return delegate.path(); }
    @Override public HttpSession getSession(boolean create) { return null; }
    @Override public HttpSession getSession() { return null; }
    @Override public String changeSessionId() { throw new IllegalStateException("sessions not supported"); }
    @Override public boolean isRequestedSessionIdValid() { return false; }
    @Override public boolean isRequestedSessionIdFromCookie() { return false; }
    @Override public boolean isRequestedSessionIdFromURL() { return false; }
    @Override public boolean authenticate(HttpServletResponse response) { return false; }
    @Override public void login(String username, String password) throws ServletException { throw new ServletException("unsupported"); }
    @Override public void logout() { }
    @Override public Collection<Part> getParts() { return List.of(); }
    @Override public Part getPart(String name) { return null; }
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
    @Override public int getContentLength() { return delegate.bodyBytes().length; }
    @Override public long getContentLengthLong() { return delegate.bodyBytes().length; }
    @Override public String getContentType() { return getHeader("content-type"); }
    @Override public ServletInputStream getInputStream() {
        ByteArrayInputStream bais = new ByteArrayInputStream(delegate.bodyBytes());
        return new ServletInputStream() {
            @Override public boolean isFinished() { return bais.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(jakarta.servlet.ReadListener readListener) { }
            @Override public int read() { return bais.read(); }
        };
    }
    @Override public String getParameter(String name) {
        String[] v = parameters.get(name);
        return v == null || v.length == 0 ? null : v[0];
    }
    @Override public Enumeration<String> getParameterNames() {
        return Collections.enumeration(parameters.keySet());
    }
    @Override public String[] getParameterValues(String name) { return parameters.get(name); }
    @Override public Map<String, String[]> getParameterMap() { return Collections.unmodifiableMap(parameters); }
    @Override public String getProtocol() { return "HTTP/1.1"; }
    @Override public String getScheme() { return "http"; }
    @Override public String getServerName() { return "localhost"; }
    @Override public int getServerPort() { return 80; }
    @Override public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(
                new ByteArrayInputStream(delegate.bodyBytes()), StandardCharsets.UTF_8));
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
    @Override public AsyncContext startAsync() { throw new UnsupportedOperationException("async"); }
    @Override public AsyncContext startAsync(ServletRequest servletRequest, ServletResponse servletResponse) {
        throw new UnsupportedOperationException("async");
    }
    @Override public boolean isAsyncStarted() { return false; }
    @Override public boolean isAsyncSupported() { return false; }
    @Override public AsyncContext getAsyncContext() { throw new IllegalStateException("async"); }
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
