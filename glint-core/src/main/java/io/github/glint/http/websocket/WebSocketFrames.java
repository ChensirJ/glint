package io.github.glint.http.websocket;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Minimal RFC 6455 frame reader/writer for text, binary, ping, pong, and close.
 * Client frames are expected to be masked; server frames are never masked.
 */
public final class WebSocketFrames {

    public static final int OPCODE_CONTINUATION = 0x0;
    public static final int OPCODE_TEXT = 0x1;
    public static final int OPCODE_BINARY = 0x2;
    public static final int OPCODE_CLOSE = 0x8;
    public static final int OPCODE_PING = 0x9;
    public static final int OPCODE_PONG = 0xA;

    private WebSocketFrames() {
    }

    public record Frame(int opcode, byte[] payload, boolean fin) {
        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    public static void write(OutputStream out, int opcode, byte[] payload, boolean masked) throws IOException {
        if (payload == null) {
            payload = new byte[0];
        }
        int len = payload.length;
        out.write(0x80 | (opcode & 0x0F)); // FIN + opcode
        if (len < 126) {
            out.write((masked ? 0x80 : 0x00) | len);
        } else if (len <= 0xFFFF) {
            out.write((masked ? 0x80 : 0x00) | 126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write((masked ? 0x80 : 0x00) | 127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) ((len >> (8 * i)) & 0xFF));
            }
        }
        if (masked) {
            byte[] mask = new byte[]{1, 2, 3, 4}; // servers should not mask; kept for tests
            out.write(mask);
            for (int i = 0; i < len; i++) {
                out.write(payload[i] ^ mask[i % 4]);
            }
        } else {
            out.write(payload);
        }
    }

    public static Frame read(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            throw new EOFException("socket closed");
        }
        int b1 = in.read();
        if (b1 < 0) {
            throw new EOFException("socket closed");
        }
        boolean fin = (b0 & 0x80) != 0;
        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((readByte(in) << 8) | readByte(in)) & 0xFFFFL;
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte(in);
            }
        }
        if (len > 16 * 1024 * 1024L) {
            throw new IOException("WebSocket frame too large: " + len);
        }
        byte[] mask = null;
        if (masked) {
            mask = new byte[4];
            readFully(in, mask);
        }
        byte[] payload = new byte[(int) len];
        readFully(in, payload);
        if (mask != null) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] = (byte) (payload[i] ^ mask[i % 4]);
            }
        }
        return new Frame(opcode, payload, fin);
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) {
                throw new EOFException();
            }
            off += n;
        }
    }
}
