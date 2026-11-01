package io.github.glint.examples.standalone;

import java.util.LinkedHashMap;
import java.util.Map;

/** One note stored by {@link NoteStore}. */
public record Note(long id, String title, String body, boolean done) {

    public Map<String, Object> toJson() {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", id);
        json.put("title", title);
        json.put("body", body);
        json.put("done", done);
        return json;
    }
}
