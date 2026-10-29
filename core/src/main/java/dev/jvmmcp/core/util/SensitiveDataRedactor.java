package dev.jvmmcp.core.util;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Defensive utility that scrubs operational secrets (passwords, auth tokens, JDBC credentials, API
 * keys, and JWTs) from diagnostic reports, thread dumps, and actuator output before transmission
 * over MCP.
 */
public final class SensitiveDataRedactor {

    private static final Pattern BEARER_PATTERN =
            Pattern.compile("(?i)\\b(bearer\\s+)[A-Za-z0-9\\-._~+/]+=*", Pattern.CASE_INSENSITIVE);

    private static final Pattern BASIC_PATTERN =
            Pattern.compile("(?i)\\b(basic\\s+)[A-Za-z0-9+/]+=*", Pattern.CASE_INSENSITIVE);

    /*
     * Matches JSON string-valued properties. The key is captured separately and checked with
     * isSensitiveKeyName(), so prefixed and camelCase secret names use the same policy as assignments
     * and deep-redaction map keys.
     */
    private static final Pattern JSON_SECRET_PATTERN =
            Pattern.compile("(\"((?:[^\"\\\\]|\\\\.)*)\"\\s*:\\s*)\"(?:[^\"\\\\]|\\\\.)*\"");

    /*
     * Capture an assignment key, then determine whether it contains a sensitive identifier.
     * This supports prefixed keys and avoids false positives such as "tokenizer" and
     * "passwordless".
     */
    private static final Pattern ASSIGNMENT_PATTERN =
            Pattern.compile(
                    "(?<![A-Za-z0-9_.-])([A-Za-z0-9_.-]+)(\\s*=\\s*)['\"]?[^'\"\\s,;&]+['\"]?");

    private static final Pattern JDBC_URL_PATTERN =
            Pattern.compile("(?i)(jdbc:[a-z:]+://[^:]+:)([^@]+)@", Pattern.CASE_INSENSITIVE);

    /*
     * Generic userinfo redaction for any scheme (mongodb, amqp, redis, postgres, https, ...).
     * The password group is greedy, so credentials containing '@' are handled using the last
     * '@' before the host. Runs after JDBC_URL_PATTERN.
     */
    private static final Pattern URI_USERINFO_PATTERN =
            Pattern.compile(
                    "(?i)\\b([a-z][a-z0-9+.\\-]*://[^\\s/:@]*:)([^\\s/]+)@",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern JWT_PATTERN =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]{10,}\\.[A-Za-z0-9_-]*\\b");

    /*
     * Sensitive identifiers must form a complete component delimited by the start/end of the key,
     * ".", "_" or "-". CamelCase names are normalized before matching. Thus "clientSecret" and
     * "secretKey" are sensitive, while "tokenizer" and "passwordless" are not.
     */
    private static final Pattern SENSITIVE_KEY_PATTERN =
            Pattern.compile(
                    "(?:^|[._-])(?:password|passwd|pwd|secret|token|apikey|api[_-]?key|access[_-]?(?:key|token)|client[_-]?secret|private[_-]?key|auth[_-]?token|credential)(?:$|[._-])");

    private SensitiveDataRedactor() {}

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
        result = redactJsonSecrets(result);
        result = redactAssignments(result);
        result = JDBC_URL_PATTERN.matcher(result).replaceAll("$1***@");
        result = URI_USERINFO_PATTERN.matcher(result).replaceAll("$1***@");
        result = JWT_PATTERN.matcher(result).replaceAll("***");

        return result;
    }

    private static String redactJsonSecrets(String text) {
        Matcher matcher = JSON_SECRET_PATTERN.matcher(text);
        StringBuffer redacted = new StringBuffer();

        while (matcher.find()) {
            String replacement = matcher.group();

            if (isSensitiveKeyName(matcher.group(2))) {
                replacement = matcher.group(1) + "\"***\"";
            }

            matcher.appendReplacement(redacted, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(redacted);
        return redacted.toString();
    }

    private static String redactAssignments(String text) {
        Matcher matcher = ASSIGNMENT_PATTERN.matcher(text);
        StringBuffer redacted = new StringBuffer();

        while (matcher.find()) {
            String replacement = matcher.group();

            if (isSensitiveKeyName(matcher.group(1))) {
                // Preserve the existing normalized format expected by callers and tests.
                replacement = matcher.group(1) + "=***";
            }

            matcher.appendReplacement(redacted, Matcher.quoteReplacement(replacement));
        }

        matcher.appendTail(redacted);
        return redacted.toString();
    }

    private static boolean isSensitiveKeyName(String key) {
        if (key == null || key.isBlank()) {
            return false;
        }

        /*
         * Pass 1: split uppercase acronym runs from following capitalized words.
         * Example: DBPassword -> DB_Password.
         *
         * Pass 2: split lowercase letters or digits from following uppercase letters.
         * Example: clientSecret -> client_Secret.
         *
         * Lowercase only after splitting so the original case boundaries are retained.
         */
        String normalized =
                key.replaceAll("([A-Z]+)([A-Z][a-z])", "$1_$2")
                        .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                        .toLowerCase(Locale.ROOT);

        return SENSITIVE_KEY_PATTERN.matcher(normalized).find();
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

                if (key instanceof String sk && isSensitiveKeyName(sk) && val instanceof String) {
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
