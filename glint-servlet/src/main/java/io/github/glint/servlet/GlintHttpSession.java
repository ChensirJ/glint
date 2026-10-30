package io.github.glint.servlet;

import jakarta.servlet.ServletContext;
import jakarta.servlet.http.HttpSession;

import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory {@link HttpSession} for the Glint servlet adapter.
 * Cookie-based {@code JSESSIONID} tracking only (no URL rewriting).
 */
public final class GlintHttpSession implements HttpSession {

    private final String id;
    private final long created = System.currentTimeMillis();
    private volatile long lastAccessed = created;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private final ServletContext servletContext;
    private final AtomicBoolean valid = new AtomicBoolean(true);
    private int maxInactiveInterval = 30 * 60; // 30 minutes

    GlintHttpSession(String id, ServletContext servletContext) {
        this.id = id;
        this.servletContext = servletContext;
    }

    void touch() {
        lastAccessed = System.currentTimeMillis();
    }

    boolean expired() {
        if (maxInactiveInterval < 0) {
            return false;
        }
        long idleMs = System.currentTimeMillis() - lastAccessed;
        return idleMs > maxInactiveInterval * 1000L;
    }

    @Override
    public long getCreationTime() {
        checkValid();
        return created;
    }

    @Override
    public String getId() {
        return id;
    }

    @Override
    public long getLastAccessedTime() {
        checkValid();
        return lastAccessed;
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        this.maxInactiveInterval = interval;
    }

    @Override
    public int getMaxInactiveInterval() {
        return maxInactiveInterval;
    }

    @Override
    public Object getAttribute(String name) {
        checkValid();
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        checkValid();
        return Collections.enumeration(attributes.keySet());
    }

    @Override
    public void setAttribute(String name, Object value) {
        checkValid();
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }

    @Override
    public void removeAttribute(String name) {
        checkValid();
        attributes.remove(name);
    }

    @Override
    public void invalidate() {
        if (valid.compareAndSet(true, false)) {
            attributes.clear();
            GlintSessionManager.remove(id);
        }
    }

    @Override
    public boolean isNew() {
        checkValid();
        return created == lastAccessed;
    }

    private void checkValid() {
        if (!valid.get() || expired()) {
            throw new IllegalStateException("session invalid");
        }
    }
}
