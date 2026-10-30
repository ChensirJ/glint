package io.github.glint.servlet;

import jakarta.servlet.ServletContext;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide session store keyed by {@code JSESSIONID}.
 * Good enough for demos and Spring Hello World; not clustered.
 */
public final class GlintSessionManager {

    private static final Map<String, GlintHttpSession> SESSIONS = new ConcurrentHashMap<>();
    public static final String COOKIE_NAME = "JSESSIONID";

    private GlintSessionManager() {
    }

    public static GlintHttpSession get(String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        GlintHttpSession s = SESSIONS.get(id);
        if (s == null) {
            return null;
        }
        if (s.expired()) {
            SESSIONS.remove(id, s);
            return null;
        }
        s.touch();
        return s;
    }

    public static GlintHttpSession create(ServletContext ctx) {
        String id = UUID.randomUUID().toString().replace("-", "");
        GlintHttpSession s = new GlintHttpSession(id, ctx);
        SESSIONS.put(id, s);
        return s;
    }

    public static void remove(String id) {
        if (id != null) {
            SESSIONS.remove(id);
        }
    }

    static int size() {
        return SESSIONS.size();
    }
}
