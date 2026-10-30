package io.github.glint.http.staticfiles;

import io.github.glint.obs.Trace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Small in-memory cache for hot static files.
 *
 * <p>Entries are keyed by absolute path and validated on every lookup against the
 * current file size and modification time, so an edited file is re-read on the next
 * request without any explicit invalidation call. Lock-free (no {@code synchronized})
 * to stay friendly to virtual threads on JDK 21.
 *
 * <p>Eviction is deliberately simple: when the soft byte budget is exceeded, arbitrary
 * entries are dropped until the cache fits again. Hot files are re-cached on the next hit.
 */
public final class StaticFileCache {

    record Entry(long size, long mtimeMillis, byte[] data) {
    }

    private final ConcurrentHashMap<Path, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong totalBytes = new AtomicLong();
    private final long maxTotalBytes;

    public StaticFileCache(long maxTotalBytes) {
        this.maxTotalBytes = maxTotalBytes;
    }

    /**
     * Return the bytes of {@code file}, from cache when {@code size} and {@code mtimeMillis}
     * still match, otherwise reading (and caching) the file.
     */
    public byte[] get(Path file, long size, long mtimeMillis) throws IOException {
        Entry e = entries.get(file);
        if (e != null && e.size() == size && e.mtimeMillis() == mtimeMillis) {
            Trace.METRICS.recordStaticCache(true);
            return e.data();
        }
        Trace.METRICS.recordStaticCache(false);
        byte[] data = Files.readAllBytes(file);
        if (maxTotalBytes <= 0 || data.length > maxTotalBytes) {
            return data;
        }
        Entry fresh = new Entry(data.length, mtimeMillis, data);
        Entry old = entries.put(file, fresh);
        totalBytes.addAndGet(data.length - (old == null ? 0L : old.data().length));
        evictIfNeeded(file);
        return data;
    }

    private void evictIfNeeded(Path keep) {
        if (totalBytes.get() <= maxTotalBytes) {
            return;
        }
        Iterator<Map.Entry<Path, Entry>> it = entries.entrySet().iterator();
        while (totalBytes.get() > maxTotalBytes && it.hasNext()) {
            Map.Entry<Path, Entry> me = it.next();
            if (me.getKey().equals(keep)) {
                continue;
            }
            if (entries.remove(me.getKey(), me.getValue())) {
                totalBytes.addAndGet(-me.getValue().data().length);
            }
        }
    }

    /** Number of cached files. */
    public int size() {
        return entries.size();
    }

    /** Bytes currently held by the cache. */
    public long bytes() {
        return totalBytes.get();
    }

    /** Drop all entries. */
    public void clear() {
        entries.clear();
        totalBytes.set(0);
    }
}
