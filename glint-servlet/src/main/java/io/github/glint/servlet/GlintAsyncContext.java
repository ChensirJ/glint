package io.github.glint.servlet;

import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Minimal {@link AsyncContext} backed by virtual threads.
 *
 * <p>{@link #start(Runnable)} runs the task on a new virtual thread.
 * {@link #complete()} commits the buffered servlet response to Glint and
 * releases the dispatcher wait latch. Full async dispatch / timeout listeners
 * are stubbed enough for basic Spring async controller experiments.
 */
public final class GlintAsyncContext implements AsyncContext {

    private final ServletRequest request;
    private final ServletResponse response;
    private final GlintHttpServletResponse glintResponse;
    private final CountDownLatch done = new CountDownLatch(1);
    private final AtomicBoolean completed = new AtomicBoolean(false);
    private final List<AsyncListener> listeners = new ArrayList<>();
    private long timeoutMs = 30_000;
    private volatile boolean hasOriginalRequestAndResponse = true;

    GlintAsyncContext(ServletRequest request,
                      ServletResponse response,
                      GlintHttpServletResponse glintResponse) {
        this.request = request;
        this.response = response;
        this.glintResponse = glintResponse;
    }

    /** Block the servlet dispatcher until {@link #complete()} or timeout. */
    public void awaitCompletion() throws InterruptedException {
        if (!done.await(Math.max(timeoutMs, 1_000), TimeUnit.MILLISECONDS)) {
            try {
                onTimeout();
            } catch (Exception ignored) {
                // fall through to complete
            }
            complete();
        }
    }

    private void onTimeout() throws IOException {
        AsyncEvent event = new AsyncEvent(this);
        for (AsyncListener listener : List.copyOf(listeners)) {
            try {
                listener.onTimeout(event);
            } catch (IOException e) {
                throw e;
            } catch (Exception ignored) {
                // continue
            }
        }
    }

    @Override
    public ServletRequest getRequest() {
        return request;
    }

    @Override
    public ServletResponse getResponse() {
        return response;
    }

    @Override
    public boolean hasOriginalRequestAndResponse() {
        return hasOriginalRequestAndResponse;
    }

    @Override
    public void dispatch() {
        dispatch(request.getServletContext(), ((jakarta.servlet.http.HttpServletRequest) request).getRequestURI());
    }

    @Override
    public void dispatch(String path) {
        dispatch(request.getServletContext(), path);
    }

    @Override
    public void dispatch(ServletContext context, String path) {
        // Stub: mark complete after a no-op dispatch so callers do not hang.
        // Real path re-dispatch needs the servlet registry; deferred to a later batch.
        complete();
    }

    @Override
    public void complete() {
        if (!completed.compareAndSet(false, true)) {
            return;
        }
        try {
            glintResponse.commitToGlint();
            AsyncEvent event = new AsyncEvent(this);
            for (AsyncListener listener : List.copyOf(listeners)) {
                try {
                    listener.onComplete(event);
                } catch (Exception ignored) {
                    // continue
                }
            }
        } catch (IOException e) {
            // still release waiters
        } finally {
            done.countDown();
        }
    }

    @Override
    public void start(Runnable run) {
        Thread.ofVirtual().name("glint-async").start(run);
    }

    @Override
    public void addListener(AsyncListener listener) {
        listeners.add(listener);
    }

    @Override
    public void addListener(AsyncListener listener, ServletRequest servletRequest, ServletResponse servletResponse) {
        listeners.add(listener);
    }

    @Override
    public <T extends AsyncListener> T createListener(Class<T> clazz) throws ServletException {
        try {
            return clazz.getDeclaredConstructor().newInstance();
        } catch (Exception e) {
            throw new ServletException(e);
        }
    }

    @Override
    public void setTimeout(long timeout) {
        this.timeoutMs = timeout;
    }

    @Override
    public long getTimeout() {
        return timeoutMs;
    }
}
