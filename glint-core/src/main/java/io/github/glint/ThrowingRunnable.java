package io.github.glint;

/**
 * Runnable that may throw checked exceptions.
 * Used by {@link io.github.glint.obs.Trace} so handler bodies stay idiomatic.
 */
@FunctionalInterface
public interface ThrowingRunnable {

    void run() throws Exception;
}
