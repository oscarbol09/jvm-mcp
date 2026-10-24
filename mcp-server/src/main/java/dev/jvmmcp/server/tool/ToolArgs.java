package dev.jvmmcp.server.tool;

import java.util.Map;

/**
 * Type-safe argument extraction utilities for MCP tool invocations.
 */
public final class ToolArgs {

    private ToolArgs() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asMap(Object obj) {
        return obj instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    public static String getStringArg(Map<String, Object> args, String key, boolean required) {
        if (args == null || !args.containsKey(key)) {
            if (required) {
                throw new IllegalArgumentException("Missing required argument: " + key);
            }
            return null;
        }
        Object val = args.get(key);

        if (val == null) {
            if (required) {
                throw new IllegalArgumentException("Missing required argument: " + key);
            }
            return null;
        }

        if (!(val instanceof String s)) {
            throw new IllegalArgumentException("Argument '" + key + "' must be a string");
        }

        if (required && s.isBlank()) {
            throw new IllegalArgumentException("Argument '" + key + "' cannot be blank");
        }

        return s;
    }

    public static long getLongArg(Map<String, Object> args, String key, boolean required) {
        if (args == null || !args.containsKey(key) || (!required && args.get(key) == null)) {
            if (required) {
                throw new IllegalArgumentException("Missing required argument: " + key);
            }
            return 0L;
        }
        Object val = args.get(key);
        if (val instanceof Number n) {
            return n.longValue();
        }
        if (val instanceof String s && !s.isBlank()) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Argument '" + key + "' must be a number, got: " + s);
            }
        }
        throw new IllegalArgumentException("Argument '" + key + "' must be a number");
    }

    public static long getPositiveLongArg(Map<String, Object> args, String key, boolean required) {
        long value = getLongArg(args, key, required);
        if (required && value <= 0) {
            throw new IllegalArgumentException("Argument '" + key + "' must be a positive number (got: " + value + ")");
        }
        return value;
    }

    public static int getIntArg(Map<String, Object> args, String key, int defaultValue, int min, int max) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return defaultValue;
        }
        Object val = args.get(key);
        int parsed;
        if (val instanceof Number n) {
            parsed = n.intValue();
        } else if (val instanceof String s && !s.isBlank()) {
            try {
                parsed = Integer.parseInt(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Argument '" + key + "' must be a number, got: " + s);
            }
        } else {
            throw new IllegalArgumentException("Argument '" + key + "' must be a number");
        }
        if (parsed < min) {
            return min;
        }
        return Math.min(parsed, max);
    }

    public static boolean getBooleanArg(Map<String, Object> args, String key, boolean defaultValue) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return defaultValue;
        }
        Object val = args.get(key);
        if (val instanceof Boolean b) {
            return b;
        }
        if (val instanceof String s) {
            return Boolean.parseBoolean(s.trim());
        }
        throw new IllegalArgumentException("Argument '" + key + "' must be a boolean");
    }
}
