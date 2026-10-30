package io.github.glint.servlet;

import jakarta.servlet.http.Part;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Collection;
import java.util.Collections;

/**
 * Adapts a Glint multipart {@link io.github.glint.http.multipart.Part}
 * to {@link jakarta.servlet.http.Part}.
 */
public final class GlintServletPart implements Part {

    private final io.github.glint.http.multipart.Part delegate;

    public GlintServletPart(io.github.glint.http.multipart.Part delegate) {
        this.delegate = delegate;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(delegate.bytes());
    }

    @Override
    public String getContentType() {
        return delegate.contentType();
    }

    @Override
    public String getName() {
        return delegate.name();
    }

    @Override
    public String getSubmittedFileName() {
        return delegate.filename().orElse(null);
    }

    @Override
    public long getSize() {
        return delegate.size();
    }

    @Override
    public void write(String fileName) throws IOException {
        Files.write(new File(fileName).toPath(), delegate.bytes());
    }

    @Override
    public void delete() {
        // in-memory only
    }

    @Override
    public String getHeader(String name) {
        return delegate.headers().get(name == null ? null : name.toLowerCase());
    }

    @Override
    public Collection<String> getHeaders(String name) {
        String v = getHeader(name);
        return v == null ? Collections.emptyList() : Collections.singletonList(v);
    }

    @Override
    public Collection<String> getHeaderNames() {
        return delegate.headers().keySet();
    }
}
