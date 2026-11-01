package io.github.glint.examples.standalone;

import io.github.glint.http.HttpResponse;
import io.github.glint.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON object reader for this sample, plus response helpers.
 * {@code glint-core} ships a writer ({@link Json}) and no parser, and this
 * module stays free of third-party JSON libraries.
 */
public final class JsonBodies {

    private final String text;
    private int i;

    private JsonBodies(String text) {
        this.text = text;
    }

    public static Map<String, Object> readObject(String text) {
        String source = text == null ? "" : text;
        JsonBodies parser = new JsonBodies(source);
        parser.skipWs();
        if (parser.i >= source.length()) {
            throw new IllegalArgumentException("JSON body is required");
        }
        if (!parser.peek('{')) {
            throw new IllegalArgumentException("JSON object expected");
        }
        Map<String, Object> value = parser.readObjectValue();
        parser.skipWs();
        if (parser.i != source.length()) {
            throw new IllegalArgumentException("Trailing data in JSON");
        }
        return value;
    }

    public static String stringField(Map<String, Object> body, String name, boolean required) {
        if (!body.containsKey(name) || body.get(name) == null) {
            if (required) {
                throw new IllegalArgumentException(name + " is required");
            }
            return "";
        }
        Object value = body.get(name);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return text;
    }

    public static boolean booleanField(Map<String, Object> body, String name, boolean defaultValue) {
        if (!body.containsKey(name) || body.get(name) == null) {
            return defaultValue;
        }
        Object value = body.get(name);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(name + " must be a boolean");
        }
        return flag.booleanValue();
    }

    public static void write(HttpResponse resp, int status, Object body) {
        resp.status(status).json(Json.stringify(body));
    }

    public static void error(HttpResponse resp, int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", message);
        body.put("status", status);
        write(resp, status, body);
    }

    private Map<String, Object> readObjectValue() {
        expect('{');
        Map<String, Object> map = new LinkedHashMap<>();
        skipWs();
        if (peek('}')) {
            i++;
            return map;
        }
        while (true) {
            skipWs();
            String key = readString();
            skipWs();
            expect(':');
            skipWs();
            map.put(key, readValue());
            skipWs();
            if (peek('}')) {
                i++;
                return map;
            }
            expect(',');
        }
    }

    private List<Object> readArray() {
        expect('[');
        List<Object> list = new ArrayList<>();
        skipWs();
        if (peek(']')) {
            i++;
            return list;
        }
        while (true) {
            skipWs();
            list.add(readValue());
            skipWs();
            if (peek(']')) {
                i++;
                return list;
            }
            expect(',');
        }
    }

    private Object readValue() {
        skipWs();
        if (i >= text.length()) {
            throw fail("Unexpected end of JSON");
        }
        char c = text.charAt(i);
        if (c == '{') {
            return readObjectValue();
        }
        if (c == '[') {
            return readArray();
        }
        if (c == '"') {
            return readString();
        }
        if (c == 't') {
            expectLiteral("true");
            return Boolean.TRUE;
        }
        if (c == 'f') {
            expectLiteral("false");
            return Boolean.FALSE;
        }
        if (c == 'n') {
            expectLiteral("null");
            return null;
        }
        if (c == '-' || (c >= '0' && c <= '9')) {
            return readNumber();
        }
        throw fail("Unexpected character");
    }

    private String readString() {
        expect('"');
        StringBuilder out = new StringBuilder();
        while (i < text.length()) {
            char c = text.charAt(i++);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (i >= text.length()) {
                    throw fail("Bad escape");
                }
                char escaped = text.charAt(i++);
                switch (escaped) {
                    case '"', '\\', '/' -> out.append(escaped);
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(readHex());
                    default -> throw fail("Bad escape");
                }
            } else if (c < 0x20) {
                throw fail("Unescaped control character");
            } else {
                out.append(c);
            }
        }
        throw fail("Unterminated string");
    }

    private char readHex() {
        if (i + 4 > text.length()) {
            throw fail("Bad unicode escape");
        }
        String hex = text.substring(i, i + 4);
        i += 4;
        try {
            return (char) Integer.parseInt(hex, 16);
        } catch (NumberFormatException ex) {
            throw fail("Bad unicode escape");
        }
    }

    private Number readNumber() {
        int start = i;
        if (peek('-')) {
            i++;
        }
        if (!hasDigit()) {
            throw fail("Expected digit");
        }
        if (text.charAt(i) == '0') {
            i++;
        } else {
            while (hasDigit()) {
                i++;
            }
        }
        boolean real = false;
        if (peek('.')) {
            real = true;
            i++;
            if (!hasDigit()) {
                throw fail("Expected digit after decimal");
            }
            while (hasDigit()) {
                i++;
            }
        }
        if (peek('e') || peek('E')) {
            real = true;
            i++;
            if (peek('+') || peek('-')) {
                i++;
            }
            if (!hasDigit()) {
                throw fail("Expected digit in exponent");
            }
            while (hasDigit()) {
                i++;
            }
        }
        String literal = text.substring(start, i);
        try {
            if (!real) {
                return Long.valueOf(literal);
            }
            return Double.valueOf(literal);
        } catch (NumberFormatException ex) {
            throw fail("Invalid number");
        }
    }

    private void expectLiteral(String literal) {
        if (!text.startsWith(literal, i)) {
            throw fail("Expected " + literal);
        }
        i += literal.length();
    }

    private void expect(char c) {
        if (!peek(c)) {
            throw fail("Expected '" + c + "'");
        }
        i++;
    }

    private boolean peek(char c) {
        return i < text.length() && text.charAt(i) == c;
    }

    private boolean hasDigit() {
        return i < text.length() && text.charAt(i) >= '0' && text.charAt(i) <= '9';
    }

    private void skipWs() {
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c != ' ' && c != '\n' && c != '\r' && c != '\t') {
                return;
            }
            i++;
        }
    }

    private IllegalArgumentException fail(String message) {
        return new IllegalArgumentException(message);
    }
}
