package dev.jvmmcp.core.util;

import java.util.regex.Pattern;

/**
 * Defensive utility that scrubs operational secrets (passwords, auth tokens, JDBC credentials,
 * API keys, and JWTs) from diagnostic reports, thread dumps, and actuator output before
 * transmission over MCP.
 */
public final class SensitiveDataRedactor {

    private static final Pattern BEARER_PATTERN = Pattern.compile(
        "(?i)\\b(bearer\\s+)[A-Za-z0-9\\-._~+/]+=*",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern BASIC_PATTERN = Pattern.compile(
        "(?i)\\b(basic\\s+)[A-Za-z0-9+/]+=*",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern JSON_SECRET_PATTERN = Pattern.compile(
        "(?i)(\"(?:password|secret|token|apikey|api_key|access_token|client_secret|private_key|auth_token)\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
        "(?i)\\b(password|secret|token|apikey|api_key|access_token|client_secret|private_key|auth_token)\\s*=\\s*['\"]?[^'\"\\s,;&]+['\"]?",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern JDBC_URL_PATTERN = Pattern.compile(
        "(?i)(jdbc:[a-z:]+://[^:]+:)([^@]+)@",
        Pattern.CASE_INSENSITIVE
    );

    // Generic userinfo redaction for ANY scheme (mongodb, amqp, redis, postgres, https, ...).
    // The JDBC variant above only matches jdbc: URLs and its [^@]+ stops at the FIRST '@',
    // leaking the tail of a password that itself contains '@'. This pattern anchors on the
    // last '@' before the host: the password group ([^/\\s]+) is greedy, so 'u:pa@ss@host'
    // redacts to 'u:***@host'. Runs after JDBC_URL_PATTERN (idempotent on already-redacted text).
    private static final Pattern URI_USERINFO_PATTERN = Pattern.compile(
        "(?i)\\b([a-z][a-z0-9+.\\-]*://[^\\s/:@]*:)([^\\s/]+)@",
        Pattern.CASE_INSENSITIVE
    );

    private static final Pattern JWT_PATTERN = Pattern.compile(
        "\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]*\\b"
    );

    private static final Pattern SENSITIVE_KEY_PATTERN = Pattern.compile(
        "(?i).*(password|secret|token|apikey|api_key|access_token|client_secret|private_key|auth_token|credential).*"
    );

    private SensitiveDataRedactor() {
    }

    /**
     * Redacts known sensitive patterns from the provided text.
     *
     * @param text input diagnostic text or JSON
     * @return sanitized text with sensitive substrings replaced by '***'
     */
    public static String redact(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        String result = text;
        result = BEARER_PATTERN.matcher(result).replaceAll("$1***");
        result = BASIC_PATTERN.matcher(result).replaceAll("$1***");
        result = JSON_SECRET_PATTERN.matcher(result).replaceAll("$1\"***\"");
        result = ASSIGNMENT_PATTERN.matcher(result).replaceAll("$1=***");
        result = JDBC_URL_PATTERN.matcher(result).replaceAll("$1***@");
        result = URI_USERINFO_PATTERN.matcher(result).replaceAll("$1***@");
        result = JWT_PATTERN.matcher(result).replaceAll("***");

        return result;
    }

    /**
     * Recursively traverses and sanitizes nested data structures (Maps, Lists, Strings, Arrays).
     *
     * @param obj arbitrary object
     * @return deep-sanitized clone of the object
     */
    public static Object redactDeep(Object obj) {
        if (obj == null) {
            return null;
        }
        if (obj instanceof String s) {
            return redact(s);
        }
        if (obj instanceof java.util.Map<?, ?> map) {
            java.util.Map<Object, Object> copy = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<?, ?> entry : map.entrySet()) {
                Object key = entry.getKey();
                Object safeKey = key instanceof String sk ? redact(sk) : key;
                Object val = entry.getValue();
                if (key instanceof String sk && SENSITIVE_KEY_PATTERN.matcher(sk).matches() && val instanceof String) {
                    copy.put(safeKey, "***");
                } else {
                    copy.put(safeKey, redactDeep(val));
                }
            }
            return copy;
        }
        if (obj instanceof java.util.List<?> list) {
            java.util.List<Object> copy = new java.util.ArrayList<>(list.size());
            for (Object item : list) {
                copy.add(redactDeep(item));
            }
            return copy;
        }
        if (obj.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(obj);
            java.util.List<Object> copy = new java.util.ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                copy.add(redactDeep(java.lang.reflect.Array.get(obj, i)));
            }
            return copy;
        }
        return obj;
    }
}
