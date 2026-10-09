package dev.jvmmcp.core.util;

import java.util.*;

/**
 * Ultra-lightweight, zero-dependency JSON parser designed for Java 21 standard runtime.
 * Parses JSON strings into Maps, Lists, Strings, Doubles, Longs, Booleans, and nulls.
 */
public final class SimpleJson {

    private SimpleJson() {}

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object parsed = parse(json);
        if (parsed instanceof Map) {
            return (Map<String, Object>) parsed;
        }
        throw new IllegalArgumentException("Expected JSON Object but found: " + (parsed != null ? parsed.getClass().getSimpleName() : "null"));
    }

    public static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof String) {
            String s = (String) obj;
            StringBuilder sb = new StringBuilder(s.length() + 16);
            sb.append('"');
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                switch (c) {
                    case '"': sb.append("\\\""); break;
                    case '\\': sb.append("\\\\"); break;
                    case '\b': sb.append("\\b"); break;
                    case '\f': sb.append("\\f"); break;
                    case '\n': sb.append("\\n"); break;
                    case '\r': sb.append("\\r"); break;
                    case '\t': sb.append("\\t"); break;
                    default:
                        if (c < 32) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                }
            }
            sb.append('"');
            return sb.toString();
        }
        if (obj instanceof Double) {
            Double d = (Double) obj;
            if (d.isNaN() || d.isInfinite()) return "null";
            return d.toString();
        }
        if (obj instanceof Float) {
            Float f = (Float) obj;
            if (f.isNaN() || f.isInfinite()) return "null";
            return f.toString();
        }
        if (obj instanceof Number || obj instanceof Boolean) return obj.toString();
        if (obj instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            Map<?, ?> map = (Map<?, ?>) obj;
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append(toJson(entry.getKey().toString())).append(":").append(toJson(entry.getValue()));
                first = false;
            }
            return sb.append("}").toString();
        }
        if (obj instanceof Iterable) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Iterable<?>) obj) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            return sb.append("]").toString();
        }
        if (obj instanceof Object[]) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object item : (Object[]) obj) {
                if (!first) sb.append(",");
                sb.append(toJson(item));
                first = false;
            }
            return sb.append("]").toString();
        }
        // Serialize Java Records using reflection
        if (obj.getClass().isRecord()) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (java.lang.reflect.RecordComponent component : obj.getClass().getRecordComponents()) {
                if (!first) sb.append(",");
                sb.append(toJson(component.getName())).append(":");
                try {
                    java.lang.reflect.Method accessor = component.getAccessor();
                    accessor.setAccessible(true);
                    sb.append(toJson(accessor.invoke(obj)));
                } catch (Exception e) {
                    sb.append("null");
                }
                first = false;
            }
            return sb.append("}").toString();
        }
        // Fallback for everything else
        return toJson(obj.toString());
    }

    public static Object parse(String json) {
        if (json == null) {
            return null;
        }
        String trimmed = json.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        Tokenizer tokenizer = new Tokenizer(trimmed);
        return parseValue(tokenizer);
    }

    private static Object parseValue(Tokenizer tokenizer) {
        tokenizer.skipWhitespace();
        char c = tokenizer.peek();

        if (c == '{') {
            return parseObject(tokenizer);
        } else if (c == '[') {
            return parseArray(tokenizer);
        } else if (c == '"' || c == '\'') {
            return parseString(tokenizer);
        } else if (c == 't' || c == 'f') {
            return parseBoolean(tokenizer);
        } else if (c == 'n') {
            return parseNull(tokenizer);
        } else if (c == '-' || Character.isDigit(c)) {
            return parseNumber(tokenizer);
        }

        throw new IllegalArgumentException("Unexpected character at position " + tokenizer.getIndex() + ": '" + c + "'");
    }

    private static Map<String, Object> parseObject(Tokenizer tokenizer) {
        tokenizer.enter();
        try {
            Map<String, Object> map = new LinkedHashMap<>();
            tokenizer.consume('{');
            tokenizer.skipWhitespace();

            if (tokenizer.peek() == '}') {
                tokenizer.consume('}');
                return map;
            }

            while (true) {
                tokenizer.skipWhitespace();
                String key = parseString(tokenizer);
                tokenizer.skipWhitespace();
                tokenizer.consume(':');
                Object value = parseValue(tokenizer);
                map.put(key, value);

                tokenizer.skipWhitespace();
                char next = tokenizer.peek();
                if (next == '}') {
                    tokenizer.consume('}');
                    break;
                } else if (next == ',') {
                    tokenizer.consume(',');
                } else {
                    throw new IllegalArgumentException("Expected ',' or '}' in object at position " + tokenizer.getIndex() + ", got '" + next + "'");
                }
            }

            return map;
        } finally {
            tokenizer.exit();
        }
    }

    private static List<Object> parseArray(Tokenizer tokenizer) {
        tokenizer.enter();
        try {
            List<Object> list = new ArrayList<>();
            tokenizer.consume('[');
            tokenizer.skipWhitespace();

            if (tokenizer.peek() == ']') {
                tokenizer.consume(']');
                return list;
            }

            while (true) {
                list.add(parseValue(tokenizer));
                tokenizer.skipWhitespace();
                char next = tokenizer.peek();
                if (next == ']') {
                    tokenizer.consume(']');
                    break;
                } else if (next == ',') {
                    tokenizer.consume(',');
                } else {
                    throw new IllegalArgumentException("Expected ',' or ']' in array at position " + tokenizer.getIndex() + ", got '" + next + "'");
                }
            }

            return list;
        } finally {
            tokenizer.exit();
        }
    }

    private static String parseString(Tokenizer tokenizer) {
        char quote = tokenizer.consume();
        if (quote != '"' && quote != '\'') {
            throw new IllegalArgumentException("Expected quote at " + tokenizer.getIndex());
        }

        StringBuilder sb = new StringBuilder();
        while (tokenizer.hasMore()) {
            char c = tokenizer.consume();
            if (c == quote) {
                return sb.toString();
            }
            if (c == '\\') {
                if (!tokenizer.hasMore()) break;
                char escape = tokenizer.consume();
                switch (escape) {
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    case '\\' -> sb.append('\\');
                    case '/' -> sb.append('/');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'u' -> {
                        StringBuilder hex = new StringBuilder(4);
                        for (int i = 0; i < 4 && tokenizer.hasMore(); i++) {
                            hex.append(tokenizer.consume());
                        }
                        sb.append((char) Integer.parseInt(hex.toString(), 16));
                    }
                    default -> sb.append(escape);
                }
            } else {
                sb.append(c);
            }
        }

        throw new IllegalArgumentException("Unterminated string starting with " + quote);
    }

    private static Boolean parseBoolean(Tokenizer tokenizer) {
        if (tokenizer.match("true")) {
            return Boolean.TRUE;
        } else if (tokenizer.match("false")) {
            return Boolean.FALSE;
        }
        throw new IllegalArgumentException("Expected boolean at " + tokenizer.getIndex());
    }

    private static Object parseNull(Tokenizer tokenizer) {
        if (tokenizer.match("null")) {
            return null;
        }
        throw new IllegalArgumentException("Expected null at " + tokenizer.getIndex());
    }

    private static Number parseNumber(Tokenizer tokenizer) {
        int start = tokenizer.getIndex();
        if (tokenizer.peek() == '-') {
            tokenizer.consume();
        }
        while (tokenizer.hasMore() && Character.isDigit(tokenizer.peek())) {
            tokenizer.consume();
        }

        boolean isFloatingPoint = false;
        if (tokenizer.hasMore() && tokenizer.peek() == '.') {
            isFloatingPoint = true;
            tokenizer.consume();
            while (tokenizer.hasMore() && Character.isDigit(tokenizer.peek())) {
                tokenizer.consume();
            }
        }

        if (tokenizer.hasMore() && (tokenizer.peek() == 'e' || tokenizer.peek() == 'E')) {
            isFloatingPoint = true;
            tokenizer.consume();
            if (tokenizer.hasMore() && (tokenizer.peek() == '+' || tokenizer.peek() == '-')) {
                tokenizer.consume();
            }
            while (tokenizer.hasMore() && Character.isDigit(tokenizer.peek())) {
                tokenizer.consume();
            }
        }

        String numStr = tokenizer.substring(start, tokenizer.getIndex());
        if (isFloatingPoint) {
            return Double.parseDouble(numStr);
        }
        try {
            long l = Long.parseLong(numStr);
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) {
                return (int) l;
            }
            return l;
        } catch (NumberFormatException e) {
            return Double.parseDouble(numStr);
        }
    }

    private static final class Tokenizer {
        private final String src;
        private int index;
        private int depth;
        private static final int MAX_DEPTH = 150;

        Tokenizer(String src) {
            this.src = src;
            this.index = 0;
            this.depth = 0;
        }

        void enter() {
            depth++;
            if (depth > MAX_DEPTH) {
                throw new IllegalArgumentException("Max JSON recursion depth exceeded: " + MAX_DEPTH);
            }
        }

        void exit() {
            depth--;
        }

        boolean hasMore() {
            return index < src.length();
        }

        char peek() {
            if (!hasMore()) {
                throw new IllegalArgumentException("Unexpected end of JSON input at index " + index);
            }
            return src.charAt(index);
        }

        char consume() {
            char c = peek();
            index++;
            return c;
        }

        void consume(char expected) {
            char c = consume();
            if (c != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' but got '" + c + "' at index " + (index - 1));
            }
        }

        boolean match(String target) {
            if (src.startsWith(target, index)) {
                index += target.length();
                return true;
            }
            return false;
        }

        void skipWhitespace() {
            while (hasMore() && Character.isWhitespace(src.charAt(index))) {
                index++;
            }
        }

        int getIndex() {
            return index;
        }

        String substring(int start, int end) {
            return src.substring(start, end);
        }
    }
}

