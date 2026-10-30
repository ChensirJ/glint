package io.github.glint.servlet;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterRegistration;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.Servlet;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRegistration;
import jakarta.servlet.SessionCookieConfig;
import jakarta.servlet.SessionTrackingMode;
import jakarta.servlet.descriptor.JspConfigDescriptor;

import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Minimal {@link ServletContext} that records servlet registrations so the
 * Spring Boot factory can dispatch to {@code DispatcherServlet}.
 */
public final class GlintServletContext implements ServletContext {

    private final Map<String, String> initParams = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, Servlet> servletsByName = new HashMap<>();
    private final Map<String, String> mappings = new HashMap<>();
    private final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();

    public Servlet findServletForPath(String path) {
        // Prefer exact mapping, then "/*" / default.
        String name = mappings.get(path);
        if (name == null) {
            name = mappings.get("/*");
        }
        if (name == null && !mappings.isEmpty()) {
            name = mappings.values().iterator().next();
        }
        return name == null ? null : servletsByName.get(name);
    }

    public Map<String, Servlet> servlets() {
        return Collections.unmodifiableMap(servletsByName);
    }

    @Override public String getContextPath() { return ""; }
    @Override public ServletContext getContext(String uripath) { return this; }
    @Override public int getMajorVersion() { return 6; }
    @Override public int getMinorVersion() { return 0; }
    @Override public int getEffectiveMajorVersion() { return 6; }
    @Override public int getEffectiveMinorVersion() { return 0; }
    @Override public String getMimeType(String file) { return null; }
    @Override public Set<String> getResourcePaths(String path) { return Collections.emptySet(); }
    @Override public URL getResource(String path) { return null; }
    @Override public InputStream getResourceAsStream(String path) { return null; }
    @Override public RequestDispatcher getRequestDispatcher(String path) { return null; }
    @Override public RequestDispatcher getNamedDispatcher(String name) { return null; }
    @Override public void log(String msg) { System.out.println("[GlintServletContext] " + msg); }
    @Override public void log(String message, Throwable throwable) {
        System.out.println("[GlintServletContext] " + message);
        throwable.printStackTrace();
    }
    @Override public String getRealPath(String path) { return null; }
    @Override public String getServerInfo() { return "Glint/0.1"; }
    @Override public String getInitParameter(String name) { return initParams.get(name); }
    @Override public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParams.keySet());
    }
    @Override public boolean setInitParameter(String name, String value) {
        return initParams.putIfAbsent(name, value) == null;
    }
    @Override public Object getAttribute(String name) { return attributes.get(name); }
    @Override public Enumeration<String> getAttributeNames() {
        return Collections.enumeration(attributes.keySet());
    }
    @Override public void setAttribute(String name, Object object) { attributes.put(name, object); }
    @Override public void removeAttribute(String name) { attributes.remove(name); }
    @Override public String getServletContextName() { return "glint"; }
    @Override public ServletRegistration.Dynamic addServlet(String servletName, String className) {
        throw new UnsupportedOperationException("addServlet(className)");
    }
    @Override public ServletRegistration.Dynamic addServlet(String servletName, Servlet servlet) {
        servletsByName.put(servletName, servlet);
        return new DynamicReg(servletName);
    }
    @Override public ServletRegistration.Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {
        try {
            Servlet servlet = servletClass.getDeclaredConstructor().newInstance();
            return addServlet(servletName, servlet);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    @Override public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }
    @Override public ServletRegistration getServletRegistration(String servletName) {
        return servletsByName.containsKey(servletName) ? new DynamicReg(servletName) : null;
    }
    @Override public Map<String, ? extends ServletRegistration> getServletRegistrations() {
        Map<String, ServletRegistration> m = new HashMap<>();
        for (String n : servletsByName.keySet()) {
            m.put(n, new DynamicReg(n));
        }
        return m;
    }
    @Override public FilterRegistration.Dynamic addFilter(String filterName, String className) { return null; }
    @Override public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) { return null; }
    @Override public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) { return null; }
    @Override public <T extends Filter> T createFilter(Class<T> clazz) { throw new UnsupportedOperationException(); }
    @Override public FilterRegistration getFilterRegistration(String filterName) { return null; }
    @Override public Map<String, ? extends FilterRegistration> getFilterRegistrations() { return Map.of(); }
    @Override public SessionCookieConfig getSessionCookieConfig() { return null; }
    @Override public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) { }
    @Override public Set<SessionTrackingMode> getDefaultSessionTrackingModes() { return Set.of(); }
    @Override public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() { return Set.of(); }
    @Override public void addListener(String className) { }
    @Override public <T extends EventListener> void addListener(T t) { }
    @Override public void addListener(Class<? extends EventListener> listenerClass) { }
    @Override public <T extends EventListener> T createListener(Class<T> clazz) { throw new UnsupportedOperationException(); }
    @Override public JspConfigDescriptor getJspConfigDescriptor() { return null; }
    @Override public ClassLoader getClassLoader() { return classLoader; }
    @Override public void declareRoles(String... roleNames) { }
    @Override public String getVirtualServerName() { return "glint"; }
    @Override public int getSessionTimeout() { return 0; }
    @Override public void setSessionTimeout(int sessionTimeout) { }
    @Override public String getRequestCharacterEncoding() { return "UTF-8"; }
    @Override public void setRequestCharacterEncoding(String encoding) { }
    @Override public String getResponseCharacterEncoding() { return "UTF-8"; }
    @Override public void setResponseCharacterEncoding(String encoding) { }
    @Override public ServletRegistration.Dynamic addJspFile(String servletName, String jspFile) {
        throw new UnsupportedOperationException("jsp");
    }

    private final class DynamicReg implements ServletRegistration.Dynamic {
        private final String name;
        private DynamicReg(String name) { this.name = name; }
        @Override public void setLoadOnStartup(int loadOnStartup) { }
        @Override public Set<String> addMapping(String... urlPatterns) {
            for (String p : urlPatterns) {
                mappings.put(p, name);
            }
            return Collections.emptySet();
        }
        @Override public Collection<String> getMappings() {
            Set<String> s = new HashSet<>();
            for (Map.Entry<String, String> e : mappings.entrySet()) {
                if (name.equals(e.getValue())) {
                    s.add(e.getKey());
                }
            }
            return s;
        }
        @Override public String getRunAsRole() { return null; }
        @Override public String getName() { return name; }
        @Override public String getClassName() {
            Servlet s = servletsByName.get(name);
            return s == null ? null : s.getClass().getName();
        }
        @Override public boolean setInitParameter(String name, String value) { return true; }
        @Override public String getInitParameter(String name) { return null; }
        @Override public Set<String> setInitParameters(Map<String, String> initParameters) { return Set.of(); }
        @Override public Map<String, String> getInitParameters() { return Map.of(); }
        @Override public void setAsyncSupported(boolean isAsyncSupported) { }
        @Override public void setMultipartConfig(jakarta.servlet.MultipartConfigElement multipartConfig) { }
        @Override public void setRunAsRole(String roleName) { }
        @Override public Set<String> setServletSecurity(jakarta.servlet.ServletSecurityElement constraint) { return Set.of(); }
    }
}
