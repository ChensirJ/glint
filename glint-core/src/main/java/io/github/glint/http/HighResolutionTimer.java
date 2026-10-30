package io.github.glint.http;

import java.util.Locale;

/**
 * Keeps the Windows system timer at 1 ms resolution while at least one
 * {@link VtServer} is running.
 *
 * <p>Why: on Windows the default timer tick is about 15.6 ms. HotSpot raises the
 * resolution only around a platform thread's {@code Thread.sleep(ms)} when
 * {@code ms} is not a multiple of 10, but virtual-thread timed waits
 * ({@code Thread.sleep}, {@code LockSupport.parkNanos}, socket timeouts) are
 * driven by the JDK's internal scheduler threads through {@code Unsafe.park},
 * which never raises it. A virtual thread asking for 15 ms therefore often
 * wakes after one or two whole ticks (15.6 or 31.2 ms), while a thread-pool
 * server whose many workers keep sleeping gets 1 ms resolution as a side effect.
 *
 * <p>How: one daemon platform thread sleeps for {@code Long.MAX_VALUE} ms (not a
 * multiple of 10), which makes HotSpot hold {@code timeBeginPeriod(1)} for the
 * whole sleep. That is a documented HotSpot behavior, needs no native code,
 * and ends when the thread is interrupted. It is a no-op on other operating
 * systems, where timed waits are already fine-grained.
 *
 * <p>Disable with {@code -Dglint.highResolutionTimer=false} or
 * {@link ServerConfig.Builder#highResolutionTimer(boolean)}.
 */
final class HighResolutionTimer {

    private static final boolean WINDOWS = System.getProperty("os.name", "")
            .toLowerCase(Locale.ROOT).startsWith("windows");
    private static final Object LOCK = new Object();
    private static int users;
    private static Thread keeper;

    private HighResolutionTimer() {
    }

    /** True when the keeper thread is running. */
    static boolean active() {
        synchronized (LOCK) {
            return keeper != null;
        }
    }

    /** Register one user; starts the keeper on first use. Returns whether a user was added. */
    static boolean acquire() {
        if (!WINDOWS || !Boolean.parseBoolean(System.getProperty("glint.highResolutionTimer", "true"))) {
            return false;
        }
        // Only called from server start/stop (never on a request path), so a
        // monitor here cannot pin request-handling virtual threads.
        synchronized (LOCK) {
            if (users++ == 0) {
                Thread t = Thread.ofPlatform().daemon(true).name("glint-hires-timer").unstarted(() -> {
                    try {
                        while (true) {
                            Thread.sleep(Long.MAX_VALUE);
                        }
                    } catch (InterruptedException ignored) {
                        // released
                    }
                });
                t.start();
                keeper = t;
            }
            return true;
        }
    }

    /** Drop one user; stops the keeper when none remain. */
    static void release() {
        synchronized (LOCK) {
            if (users == 0) {
                return;
            }
            if (--users == 0 && keeper != null) {
                keeper.interrupt();
                keeper = null;
            }
        }
    }
}