package io.github.glint.http;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connection buffers must reassemble partial socket reads and must not drop
 * bytes that arrived with a large body (the next keep-alive request).
 */
class ConnectionInputStreamTest {

    @Test
    void smallReadsReassembleAcrossShortFills() throws Exception {
        byte[] payload = "hello-body".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = request(payload);
        ConnectionInputStream in = new ConnectionInputStream(new ChunkChannel(raw, 3));
        HttpRequest req = HttpParser.parse(in, ServerConfig.defaults());
        assertEquals(payload.length, req.contentLength());
        assertArrayEquals(payload, req.bodyStream().readAllBytes());
    }

    @Test
    void largeBodyKeepsPipelinedNextRequest() throws Exception {
        byte[] body = new byte[80 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) (i * 17);
        }
        byte[] second = "GET /ping HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII);
        byte[] raw = concat(request(body), second);
        // Chunks smaller than the header buffer, and a total larger than that buffer,
        // so the large-body path has to read ahead without eating the next request.
        ConnectionInputStream in = new ConnectionInputStream(new ChunkChannel(raw, 1000));
        ServerConfig config = ServerConfig.builder().maxRequestBodyBytes(1024 * 1024).build();
        HttpRequest req = HttpParser.parse(in, config);
        assertTrue(req.contentLength() >= ConnectionInputStream.LARGE_BODY_BYTES);
        in.expectLargeBody();
        assertArrayEquals(body, req.bodyStream().readAllBytes());

        HttpRequest next = HttpParser.parse(in, config);
        assertEquals("/ping", next.path());
        assertEquals(HttpMethod.GET, next.method());
    }

    @Test
    void heapLargeBodyAlsoPreservesTail() throws Exception {
        byte[] body = new byte[70 * 1024];
        for (int i = 0; i < body.length; i++) {
            body[i] = (byte) i;
        }
        byte[] second = "GET /next HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
        ConnectionInputStream in = new ConnectionInputStream(new ByteArrayInputStream(concat(request(body), second)));
        ServerConfig config = ServerConfig.builder().maxRequestBodyBytes(1024 * 1024).build();
        HttpRequest req = HttpParser.parse(in, config);
        in.expectLargeBody();
        assertArrayEquals(body, req.bodyBytes());
        HttpRequest next = HttpParser.parse(in, config);
        assertEquals("/next", next.path());
    }

    @Test
    void outputStreamFlushesSmallAndLargeWrites() throws Exception {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        ConnectionOutputStream out = new ConnectionOutputStream(raw);
        out.write(7);
        out.write("abc".getBytes(StandardCharsets.US_ASCII));
        assertEquals(0, raw.size());
        out.flush();
        assertArrayEquals(new byte[]{7, 'a', 'b', 'c'}, raw.toByteArray());

        byte[] big = new byte[ConnectionOutputStream.BUFFER + 10];
        for (int i = 0; i < big.length; i++) {
            big[i] = (byte) i;
        }
        out.write(big);
        out.flush();
        byte[] all = raw.toByteArray();
        assertEquals(4 + big.length, all.length);
        byte[] tail = new byte[big.length];
        System.arraycopy(all, 4, tail, 0, big.length);
        assertArrayEquals(big, tail);
        out.close();
        assertThrows(IOException.class, () -> out.write(1));
    }

    private static byte[] request(byte[] body) {
        String head = "POST /upload HTTP/1.1\r\nHost: localhost\r\nContent-Length: " + body.length + "\r\n\r\n";
        return concat(head.getBytes(StandardCharsets.US_ASCII), body);
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = new byte[a.length + b.length];
        System.arraycopy(a, 0, out, 0, a.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    /** Returns at most {@code max} bytes per read, like a stingy socket. */
    private static final class ChunkChannel implements ReadableByteChannel {
        private final byte[] data;
        private final int max;
        private int pos;
        private boolean open = true;

        ChunkChannel(byte[] data, int max) {
            this.data = data;
            this.max = max;
        }

        @Override
        public int read(ByteBuffer dst) {
            if (pos >= data.length) {
                return -1;
            }
            int n = Math.min(max, Math.min(dst.remaining(), data.length - pos));
            dst.put(data, pos, n);
            pos += n;
            return n;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
