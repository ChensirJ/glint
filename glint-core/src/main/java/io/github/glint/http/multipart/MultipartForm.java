package io.github.glint.http.multipart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Parsed {@code multipart/form-data} payload: named form fields and file parts.
 */
public final class MultipartForm {

    private final Map<String, List<Part>> partsByName;

    public MultipartForm(List<Part> parts) {
        Map<String, List<Part>> map = new LinkedHashMap<>();
        for (Part p : parts) {
            map.computeIfAbsent(p.name(), k -> new ArrayList<>()).add(p);
        }
        // freeze
        Map<String, List<Part>> frozen = new LinkedHashMap<>();
        for (Map.Entry<String, List<Part>> e : map.entrySet()) {
            frozen.put(e.getKey(), List.copyOf(e.getValue()));
        }
        this.partsByName = Collections.unmodifiableMap(frozen);
    }

    public static MultipartForm empty() {
        return new MultipartForm(List.of());
    }

    /** First part with the given field name, if any. */
    public Optional<Part> first(String name) {
        List<Part> list = partsByName.get(name);
        if (list == null || list.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(list.get(0));
    }

    /** Text value of the first non-file part with the given name. */
    public Optional<String> field(String name) {
        return first(name).filter(p -> !p.isFile()).map(Part::asText);
    }

    /** All parts for a name (files and fields). */
    public List<Part> parts(String name) {
        return partsByName.getOrDefault(name, List.of());
    }

    /** Flat list of every part in order of appearance. */
    public List<Part> all() {
        List<Part> all = new ArrayList<>();
        for (List<Part> list : partsByName.values()) {
            all.addAll(list);
        }
        return List.copyOf(all);
    }

    public Map<String, List<Part>> asMap() {
        return partsByName;
    }

    public int size() {
        int n = 0;
        for (List<Part> list : partsByName.values()) {
            n += list.size();
        }
        return n;
    }

    public boolean isEmpty() {
        return partsByName.isEmpty();
    }
}
