package io.github.glint.examples.standalone;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory notes. Safe for the virtual threads that serve connections.
 * {@code doneFilter} null means every note.
 */
public final class NoteStore {

    private final ConcurrentHashMap<Long, Note> notes = new ConcurrentHashMap<>();
    private final AtomicLong nextId = new AtomicLong(1L);

    public Note create(String title, String body, boolean done) {
        long id = nextId.getAndIncrement();
        Note note = new Note(id, title, body, done);
        notes.put(id, note);
        return note;
    }

    public Optional<Note> find(long id) {
        return Optional.ofNullable(notes.get(id));
    }

    public List<Note> list(Boolean doneFilter) {
        List<Note> out = new ArrayList<>();
        for (Note note : notes.values()) {
            if (doneFilter == null || note.done() == doneFilter.booleanValue()) {
                out.add(note);
            }
        }
        out.sort(Comparator.comparingLong(Note::id));
        return List.copyOf(out);
    }

    public Optional<Note> replace(long id, String title, String body, boolean done) {
        Note next = new Note(id, title, body, done);
        Note previous = notes.replace(id, next);
        if (previous == null) {
            return Optional.empty();
        }
        return Optional.of(next);
    }

    public boolean delete(long id) {
        return notes.remove(id) != null;
    }
}
