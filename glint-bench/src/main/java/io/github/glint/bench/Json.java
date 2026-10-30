package io.github.glint.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON reader/writer used by the realistic bench.
 * Both servers run exactly this code so the parse/serialize cost is identical.
 */
public final class Json {

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    /** Parse a JSON document into Map / List / String / Long / Double / Boolean / null. */
    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) {
            throw new IllegalArgumentException("Trailing data at " + p.i);
        }
        return v;
    }

    private Object value() {
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            String k = string();
            ws();
            if (s.charAt(i++) != ':') {
                throw new IllegalArgumentException("Expected ':' at " + (i - 1));
            }
            ws();
            m.put(k, value());
            ws();
            char c = s.charAt(i++);
            if (c == '}') {
                return m;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' at " + (i - 1));
            }
        }
    }

    private List<Object> array() {
        List<Object> l = new ArrayList<>();
        i++;
        ws();
        if (s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value());
            ws();
            char c = s.charAt(i++);
            if (c == ']') {
                return l;
            }
            if (c != ',') {
                throw new IllegalArgumentException("Expected ',' at " + (i - 1));
            }
        }
    }

    private String string() {
        if (s.charAt(i) != '"') {
            throw new IllegalArgumentException("Expected string at " + i);
        }
        i++;
        StringBuilder sb = null;
        int start = i;
        while (true) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb == null ? s.substring(start, i - 1) : sb.toString();
            }
            if (c == '\\') {
                if (sb == null) {
                    sb = new StringBuilder(s.substring(start, i - 1));
                }
                char e = s.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> sb.append(e);
                }
            } else if (sb != null) {
                sb.append(c);
            }
        }
    }

    private Object number() {
        int start = i;
        boolean fp = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '-' || c == '+') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E') {
                fp = true;
                i++;
            } else {
                break;
            }
        }
        String n = s.substring(start, i);
        if (n.isEmpty()) {
            throw new IllegalArgumentException("Unexpected char at " + start);
        }
        return fp ? (Object) Double.parseDouble(n) : (Object) Long.parseLong(n);
    }

    private void expect(String word) {
        if (!s.startsWith(word, i)) {
            throw new IllegalArgumentException("Expected " + word + " at " + i);
        }
        i += word.length();
    }

    private void ws() {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
            i++;
        }
    }

    /** Serialize Map / List / CharSequence / Number / Boolean / null. */
    public static String write(Object v) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                quote(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List<?> l) {
            sb.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) {
                    sb.append(',');
                }
                write(sb, l.get(k));
            }
            sb.append(']');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else {
            quote(sb, v.toString());
        }
    }

    static void quote(StringBuilder sb, String str) {
        sb.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }
}
