package io.github.glint.servlet;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterConfig;
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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Minimal {@link ServletContext} that records servlet and filter registrations
 * so the Spring Boot factory can dispatch to {@code DispatcherServlet} through
 * the registered filter chain.
 */
public final class GlintServletContext implements ServletContext {

    private final Map<String, String> initParams = new HashMap<>();
    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, Servlet> servletsByName = new LinkedHashMap<>();
    private final Map<String, String> mappings = new LinkedHashMap<>();
    /** Filters in registration order. */
    private final Map<String, FilterReg> filtersByName = new LinkedHashMap<>();
    /**
     * Filter mappings in effective order: mappings added with
     * {@code isMatchAfter=false} first (registration order), then those added with
     * {@code isMatchAfter=true}.
     */
    private final List<FilterMapping> beforeMappings = new ArrayList<>();
    private final List<FilterMapping> afterMappings = new ArrayList<>();
    private final ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
    private boolean initialized;

    /** One URL-pattern or servlet-name mapping for a filter. */
    record FilterMapping(String filterName, String urlPattern, String servletName,
                         EnumSet<DispatcherType> dispatcherTypes) {
        boolean matches(String path, String targetServlet, DispatcherType type) {
            if (!dispatcherTypes.contains(type)) {
                return false;
            }
            if (servletName != null) {
                return "*".equals(servletName) || servletName.equals(targetServlet);
            }
            return urlPatternMatches(urlPattern, path);
        }
    }

    /**
     * Servlet spec URL-pattern matching: exact, {@code /prefix/*}, {@code *.ext},
     * and {@code /} (default, matches everything).
     */
    static boolean urlPatternMatches(String pattern, String path) {
        if (pattern == null || path == null) {
            return false;
        }
        if (pattern.equals("/*") || pattern.equals("/")) {
            return true;
        }
        if (pattern.endsWith("/*")) {
            String prefix = pattern.substring(0, pattern.length() - 2);
            return path.equals(prefix) || path.startsWith(prefix + "/");
        }
        if (pattern.startsWith("*.")) {
            return path.endsWith(pattern.substring(1));
        }
        return pattern.equals(path);
    }

    public Servlet findServletForPath(String path) {
        String name = findServletNameForPath(path);
        return name == null ? null : servletsByName.get(name);
    }

    /** Name of the servlet that handles {@code path}, or {@code null}. */
    public String findServletNameForPath(String path) {
        // Prefer exact mapping, then "/*" / default.
        String name = mappings.get(path);
        if (name == null) {
            name = mappings.get("/*");
        }
        if (name == null) {
            name = mappings.get("/");
        }
        if (name == null && !mappings.isEmpty()) {
            name = mappings.values().iterator().next();
        }
        return name;
    }

    public Map<String, Servlet> servlets() {
        return Collections.unmodifiableMap(servletsByName);
    }

    /**
     * Filters that apply to a request for {@code path} dispatched to
     * {@code servletName}, in execution order. A filter appears at most once.
     */
    public List<Filter> filtersFor(String path, String servletName, DispatcherType type) {
        if (filtersByName.isEmpty()) {
            return List.of();
        }
        Set<String> names = new LinkedHashSet<>();
        // Servlet spec: URL-pattern mappings first, then servlet-name mappings.
        collect(names, beforeMappings, path, servletName, type, true);
        collect(names, afterMappings, path, servletName, type, true);
        collect(names, beforeMappings, path, servletName, type, false);
        collect(names, afterMappings, path, servletName, type, false);
        List<Filter> out = new ArrayList<>(names.size());
        for (String n : names) {
            FilterReg reg = filtersByName.get(n);
            if (reg != null) {
                out.add(reg.filter);
            }
        }
        return out;
    }

    private static void collect(Set<String> names, List<FilterMapping> mappings, String path,
                                String servletName, DispatcherType type, boolean urlPatterns) {
        for (FilterMapping m : mappings) {
            if ((m.urlPattern() != null) == urlPatterns && m.matches(path, servletName, type)) {
                names.add(m.filterName());
            }
        }
    }

    /**
     * Initialize every registered filter and servlet. Spring Boot expects the
     * container to call {@link Filter#init} / {@link Servlet#init} after
     * {@code ServletContextInitializer}s run. Idempotent.
     */
    public void initialize() throws ServletException {
        if (initialized) {
            return;
        }
        initialized = true;
        for (FilterReg reg : filtersByName.values()) {
            reg.filter.init(reg);
        }
        for (Map.Entry<String, Servlet> e : servletsByName.entrySet()) {
            Servlet servlet = e.getValue();
            if (servlet.getServletConfig() == null) {
                servlet.init(new SimpleServletConfig(e.getKey()));
            }
        }
    }

    /** @deprecated use {@link #initialize()}, which also initializes filters. */
    @Deprecated
    public void initServlets() throws ServletException {
        initialize();
    }

    /** Call {@code destroy()} on filters and servlets (best effort). */
    public void destroy() {
        for (FilterReg reg : filtersByName.values()) {
            try {
                reg.filter.destroy();
            } catch (RuntimeException ignored) {
                // keep shutting down the rest
            }
        }
        for (Servlet s : servletsByName.values()) {
            try {
                s.destroy();
            } catch (RuntimeException ignored) {
                // keep shutting down the rest
            }
        }
    }

    private final class SimpleServletConfig implements jakarta.servlet.ServletConfig {
        private final String name;
        private SimpleServletConfig(String name) { this.name = name; }
        @Override public String getServletName() { return name; }
        @Override public ServletContext getServletContext() { return GlintServletContext.this; }
        @Override public String getInitParameter(String name) { return null; }
        @Override public Enumeration<String> getInitParameterNames() {
            return Collections.emptyEnumeration();
        }
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
        try {
            Class<?> c = classLoader.loadClass(className);
            return addServlet(servletName, (Servlet) c.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot instantiate servlet " + className, e);
        }
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

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, String className) {
        try {
            Class<?> c = classLoader.loadClass(className);
            return addFilter(filterName, (Filter) c.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot instantiate filter " + className, e);
        }
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {
        if (filterName == null || filterName.isEmpty()) {
            throw new IllegalArgumentException("filterName must not be empty");
        }
        if (filtersByName.containsKey(filterName)) {
            return null; // servlet spec: already registered
        }
        FilterReg reg = new FilterReg(filterName, filter);
        filtersByName.put(filterName, reg);
        return reg;
    }

    @Override
    public FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {
        try {
            return addFilter(filterName, filterClass.getDeclaredConstructor().newInstance());
        } catch (Exception e) {
            throw new IllegalArgumentException("Cannot instantiate filter " + filterClass, e);
        }
    }

    @Override
    public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    @Override
    public FilterRegistration getFilterRegistration(String filterName) {
        return filtersByName.get(filterName);
    }

    @Override
    public Map<String, ? extends FilterRegistration> getFilterRegistrations() {
        return Collections.unmodifiableMap(filtersByName);
    }

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

    /** Filter registration that also serves as the filter's {@link FilterConfig}. */
    private final class FilterReg implements FilterRegistration.Dynamic, FilterConfig {
        private final String name;
        private final Filter filter;
        private final Map<String, String> initParameters = new LinkedHashMap<>();

        private FilterReg(String name, Filter filter) {
            this.name = name;
            this.filter = filter;
        }

        private void addMappings(EnumSet<DispatcherType> types, boolean isMatchAfter,
                                 String[] urlPatterns, String[] servletNames) {
            EnumSet<DispatcherType> t = types == null || types.isEmpty()
                    ? EnumSet.of(DispatcherType.REQUEST)
                    : EnumSet.copyOf(types);
            List<FilterMapping> target = isMatchAfter ? afterMappings : beforeMappings;
            if (urlPatterns != null) {
                for (String p : urlPatterns) {
                    target.add(new FilterMapping(name, p, null, t));
                }
            }
            if (servletNames != null) {
                for (String s : servletNames) {
                    target.add(new FilterMapping(name, null, s, t));
                }
            }
        }

        @Override
        public void addMappingForServletNames(EnumSet<DispatcherType> dispatcherTypes,
                                              boolean isMatchAfter, String... servletNames) {
            addMappings(dispatcherTypes, isMatchAfter, null, servletNames);
        }

        @Override
        public Collection<String> getServletNameMappings() {
            List<String> out = new ArrayList<>();
            for (FilterMapping m : allMappings()) {
                if (name.equals(m.filterName()) && m.servletName() != null) {
                    out.add(m.servletName());
                }
            }
            return out;
        }

        @Override
        public void addMappingForUrlPatterns(EnumSet<DispatcherType> dispatcherTypes,
                                             boolean isMatchAfter, String... urlPatterns) {
            addMappings(dispatcherTypes, isMatchAfter, urlPatterns, null);
        }

        @Override
        public Collection<String> getUrlPatternMappings() {
            List<String> out = new ArrayList<>();
            for (FilterMapping m : allMappings()) {
                if (name.equals(m.filterName()) && m.urlPattern() != null) {
                    out.add(m.urlPattern());
                }
            }
            return out;
        }

        private List<FilterMapping> allMappings() {
            List<FilterMapping> all = new ArrayList<>(beforeMappings);
            all.addAll(afterMappings);
            return all;
        }

        @Override public String getName() { return name; }
        @Override public String getFilterName() { return name; }
        @Override public ServletContext getServletContext() { return GlintServletContext.this; }
        @Override public String getClassName() { return filter.getClass().getName(); }
        @Override public boolean setInitParameter(String key, String value) {
            return initParameters.putIfAbsent(key, value) == null;
        }
        @Override public String getInitParameter(String key) { return initParameters.get(key); }
        @Override public Enumeration<String> getInitParameterNames() {
            return Collections.enumeration(initParameters.keySet());
        }
        @Override public Set<String> setInitParameters(Map<String, String> params) {
            Set<String> conflicts = new HashSet<>();
            for (Map.Entry<String, String> e : params.entrySet()) {
                if (!setInitParameter(e.getKey(), e.getValue())) {
                    conflicts.add(e.getKey());
                }
            }
            return conflicts;
        }
        @Override public Map<String, String> getInitParameters() { return Map.copyOf(initParameters); }
        @Override public void setAsyncSupported(boolean isAsyncSupported) { }
    }
}