package io.vorg.simulation.json;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal dependency-free JSON codec with a <b>canonical</b> writer (ADR-47), so the library needs no Jackson/Spring.
 *
 * <p>Parsing: objects become {@link LinkedHashMap} (document order kept), arrays {@link ArrayList}, integers without
 * fraction/exponent {@link Long}, other numbers {@link Double}, plus {@link String}, {@link Boolean} and {@code null}.
 * Duplicate keys are rejected.
 *
 * <p>Canonical writing: maps in their iteration order (callers build {@link LinkedHashMap}s in a fixed order or use
 * sorted maps), no whitespace, minimal string escaping ({@code " \ } and control characters as {@code \\uXXXX} except
 * {@code \n \r \t \b \f}), integers as is, doubles rounded half-even to {@value #DECIMALS} decimals with trailing zeros
 * stripped and no exponent; NaN and infinities are written as {@code null}; {@code -0} as {@code 0}.
 */
public final class Json {

    public static final int DECIMALS = 6;

    private Json() {}

    // ---------------------------------------------------------------- writing

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        write(sb, value);
        return sb.toString();
    }

    /** Pretty output for humans (2-space indent); same value formatting as {@link #write(Object)}. */
    public static String writePretty(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writePretty(sb, value, 0);
        sb.append('\n');
        return sb.toString();
    }

    public static String sha256(String canonical) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String formatDouble(double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            return "null";
        }
        BigDecimal bd = BigDecimal.valueOf(d).setScale(DECIMALS, RoundingMode.HALF_EVEN).stripTrailingZeros();
        if (bd.signum() == 0) {
            return "0";
        }
        return bd.toPlainString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            writeString(sb, s);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Double || v instanceof Float) {
            sb.append(formatDouble(((Number) v).doubleValue()));
        } else if (v instanceof BigDecimal bd) {
            sb.append(formatDouble(bd.doubleValue()));
        } else if (v instanceof Number n) {
            sb.append(n.longValue());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> it) {
            sb.append('[');
            boolean first = true;
            for (Object o : it) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(sb, o);
            }
            sb.append(']');
        } else if (v instanceof double[] arr) {
            List<Double> l = new ArrayList<>(arr.length);
            for (double d : arr) {
                l.add(d);
            }
            write(sb, l);
        } else if (v instanceof Enum<?> e) {
            writeString(sb, e.name());
        } else {
            throw new IllegalArgumentException("not JSON-serializable: " + v.getClass().getName());
        }
    }

    private static void writePretty(StringBuilder sb, Object v, int indent) {
        if (v instanceof Map<?, ?> m && !m.isEmpty()) {
            sb.append("{\n");
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                pad(sb, indent + 1);
                writeString(sb, String.valueOf(e.getKey()));
                sb.append(": ");
                writePretty(sb, e.getValue(), indent + 1);
                sb.append(++i < m.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append('}');
        } else if (v instanceof List<?> l && !l.isEmpty()) {
            boolean scalars = l.stream().noneMatch(o -> o instanceof Map || o instanceof List);
            if (scalars) {
                write(sb, l);
                return;
            }
            sb.append("[\n");
            for (int i = 0; i < l.size(); i++) {
                pad(sb, indent + 1);
                writePretty(sb, l.get(i), indent + 1);
                sb.append(i + 1 < l.size() ? ",\n" : "\n");
            }
            pad(sb, indent);
            sb.append(']');
        } else {
            write(sb, v);
        }
    }

    private static void pad(StringBuilder sb, int indent) {
        for (int i = 0; i < indent; i++) {
            sb.append("  ");
        }
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
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

    // ---------------------------------------------------------------- parsing

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.pos != text.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new IllegalArgumentException("JSON object expected");
        }
        return (Map<String, Object>) v;
    }

    private static final class Parser {
        private final String s;
        private int pos;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON parse error at " + pos + ": " + msg);
        }

        void ws() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t' || c == '﻿') {
                    pos++;
                } else {
                    break;
                }
            }
        }

        Object value() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        Object literal(String word, Object v) {
            if (!s.startsWith(word, pos)) {
                throw error("expected " + word);
            }
            pos += word.length();
            return v;
        }

        Map<String, Object> object() {
            Map<String, Object> m = new LinkedHashMap<>();
            pos++;
            ws();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                ws();
                if (peek() != '"') {
                    throw error("object key expected");
                }
                String k = string();
                ws();
                expect(':');
                ws();
                Object v = value();
                if (m.containsKey(k)) {
                    throw error("duplicate key '" + k + "'");
                }
                m.put(k, v);
                ws();
                char c = next();
                if (c == '}') {
                    return m;
                }
                if (c != ',') {
                    throw error("',' or '}' expected");
                }
            }
        }

        List<Object> array() {
            List<Object> l = new ArrayList<>();
            pos++;
            ws();
            if (peek() == ']') {
                pos++;
                return l;
            }
            while (true) {
                ws();
                l.add(value());
                ws();
                char c = next();
                if (c == ']') {
                    return l;
                }
                if (c != ',') {
                    throw error("',' or ']' expected");
                }
            }
        }

        String string() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = next();
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    char e = next();
                    switch (e) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
                            pos += 4;
                        }
                        default -> throw error("bad escape");
                    }
                } else if (c < 0x20) {
                    throw error("control character in string");
                } else {
                    sb.append(c);
                }
            }
        }

        Object number() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            boolean integral = true;
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c >= '0' && c <= '9') {
                    pos++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    pos++;
                } else {
                    break;
                }
            }
            String t = s.substring(start, pos);
            if (t.isEmpty() || t.equals("-")) {
                throw error("value expected");
            }
            try {
                if (integral) {
                    return Long.parseLong(t);
                }
                return Double.parseDouble(t);
            } catch (NumberFormatException e) {
                throw error("bad number '" + t + "'");
            }
        }

        char peek() {
            if (pos >= s.length()) {
                throw error("unexpected end");
            }
            return s.charAt(pos);
        }

        char next() {
            char c = peek();
            pos++;
            return c;
        }

        void expect(char c) {
            if (next() != c) {
                pos--;
                throw error("'" + c + "' expected");
            }
        }
    }
}
