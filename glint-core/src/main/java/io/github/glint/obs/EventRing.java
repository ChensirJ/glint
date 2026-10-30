package io.github.glint.obs;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Fixed-capacity ring buffer for recent diagnostic events.
 * Uses {@link ReentrantLock} instead of {@code synchronized} (JDK 21 pinning).
 */
public final class EventRing<T> {

    private final Object[] buf;
    private final ReentrantLock lock = new ReentrantLock();
    private int head;
    private int size;

    public EventRing(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity");
        }
        this.buf = new Object[capacity];
    }

    public void add(T item) {
        lock.lock();
        try {
            buf[head] = item;
            head = (head + 1) % buf.length;
            if (size < buf.length) {
                size++;
            }
        } finally {
            lock.unlock();
        }
    }

    /** Newest-first snapshot. */
    @SuppressWarnings("unchecked")
    public List<T> snapshot() {
        lock.lock();
        try {
            List<T> out = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                int idx = (head - 1 - i + buf.length) % buf.length;
                out.add((T) buf[idx]);
            }
            return out;
        } finally {
            lock.unlock();
        }
    }

    public int size() {
        lock.lock();
        try {
            return size;
        } finally {
            lock.unlock();
        }
    }
}
