package io.github.glint.http.multipart;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * One part of a {@code multipart/form-data} body.
 * Either a regular form field ({@link #isFile()} is false) or a file upload.
 */
public final class Part {

    private final String name;
    private final String filename;
    private final String contentType;
    private final Map<String, String> headers;
    private final byte[] data;

    public Part(String name, String filename, String contentType,
                Map<String, String> headers, byte[] data) {
        this.name = name;
        this.filename = filename;
        this.contentType = contentType;
        this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        this.data = data == null ? new byte[0] : data;
    }

    public String name() {
        return name;
    }

    /** Original filename from Content-Disposition, or empty when not a file. */
    public Optional<String> filename() {
        return filename == null || filename.isEmpty() ? Optional.empty() : Optional.of(filename);
    }

    public boolean isFile() {
        return filename != null && !filename.isEmpty();
    }

    public String contentType() {
        return contentType == null || contentType.isEmpty()
                ? "application/octet-stream"
                : contentType;
    }

    public Map<String, String> headers() {
        return headers;
    }

    public byte[] bytes() {
        return data;
    }

    public String asText() {
        return asText(StandardCharsets.UTF_8);
    }

    public String asText(Charset charset) {
        return new String(data, charset);
    }

    public int size() {
        return data.length;
    }

    @Override
    public String toString() {
        return "Part{name=" + name
                + (isFile() ? ", filename=" + filename : "")
                + ", size=" + data.length + "}";
    }

    static String header(Map<String, String> headers, String name) {
        return headers.getOrDefault(name.toLowerCase(Locale.ROOT), "");
    }
}
