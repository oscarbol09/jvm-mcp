package dev.jvmmcp.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveDataRedactorTest {

    @Test
    @DisplayName("Returns null or empty unchanged")
    void testNullOrEmpty() {
        assertThat(SensitiveDataRedactor.redact(null)).isNull();
        assertThat(SensitiveDataRedactor.redact("")).isEmpty();
        assertThat(SensitiveDataRedactor.redact("   ")).isEqualTo("   ");
    }

    @Test
    @DisplayName("Redacts Bearer and Basic authentication headers")
    void testAuthHeaders() {
        String input = "Authorization: Bearer my-super-secret-token-12345";
        assertThat(SensitiveDataRedactor.redact(input)).isEqualTo("Authorization: Bearer ***");

        String basic = "Authorization: Basic dXNlcjpwYXNzd29yZA==";
        assertThat(SensitiveDataRedactor.redact(basic)).isEqualTo("Authorization: Basic ***");
    }

    @Test
    @DisplayName("Redacts JSON key-value secrets")
    void testJsonSecrets() {
        String json = "{\"username\": \"admin\", \"password\": \"SuperSecret123!\", \"apiKey\": \"ak_live_abcdef123456\"}";
        String redacted = SensitiveDataRedactor.redact(json);
        assertThat(redacted)
            .contains("\"password\": \"***\"")
            .contains("\"apiKey\": \"***\"")
            .contains("\"username\": \"admin\"");
    }

    @Test
    @DisplayName("Redacts assignment-based secrets")
    void testAssignmentSecrets() {
        String text = "Connecting with password=mySecretPass and token=xyz789";
        String redacted = SensitiveDataRedactor.redact(text);
        assertThat(redacted)
            .contains("password=***")
            .contains("token=***")
            .doesNotContain("mySecretPass")
            .doesNotContain("xyz789");
    }

    @Test
    @DisplayName("Redacts JDBC connection password in URLs")
    void testJdbcUrlPassword() {
        String url = "Connecting to jdbc:postgresql://postgres:secretDbPass123@localhost:5432/mydb";
        assertThat(SensitiveDataRedactor.redact(url))
            .contains("jdbc:postgresql://postgres:***@localhost:5432/mydb")
            .doesNotContain("secretDbPass123");
    }

    @Test
    @DisplayName("Redacts credentials in non-JDBC URI schemes (mongodb/amqp/redis/postgres/https)")
    void testNonJdbcUriCredentials() {
        String mongo = "Mongo" + "PW123";
        String amq = "Amq" + "PW456";
        String redis = "Redis" + "PW789";
        String pg = "Pg" + "PW000";
        String https = "Https" + "PW111";
        String input = "uri is mongodb://admin:" + mongo + "@host:27017/db and "
            + "amqp://amquser:" + amq + "@rabbit and "
            + "redis://redisuser:" + redis + "@cache:6379 and "
            + "postgres://pguser:" + pg + "@dbhost:5432/db and "
            + "https://httpsuser:" + https + "@secure.example.com/x";
        // guard: prove the test data actually carries the secrets
        assertThat(input).contains(mongo).contains(amq).contains(redis).contains(pg).contains(https);
        String out = SensitiveDataRedactor.redact(input);
        assertThat(out).doesNotContain(mongo).doesNotContain(amq).doesNotContain(redis).doesNotContain(pg).doesNotContain(https);
        assertThat(out).contains("mongodb://admin:***@host:27017/db");
        assertThat(out).contains("amqp://amquser:***@rabbit");
        assertThat(out).contains("redis://redisuser:***@cache:6379");
        assertThat(out).contains("postgres://pguser:***@dbhost:5432/db");
        assertThat(out).contains("https://httpsuser:***@secure.example.com/x");
    }

    @Test
    @DisplayName("Redacts the whole password when it contains an @ (splits on the last @)")
    void testPasswordContainingAt() {
        String pa = "pa" + "@ssword";
        String to = "to" + "@ken";
        String input = "conn jdbc:postgresql://u:" + pa + "@host:5432/db and "
            + "mongodb://m:" + to + "@host/db and https://host/path?x=1";
        // guard: prove the test data actually carries the secrets
        assertThat(input).contains(pa).contains(to);
        String out = SensitiveDataRedactor.redact(input);
        assertThat(out).doesNotContain(pa).doesNotContain(to);
        assertThat(out).contains("jdbc:postgresql://u:***@host:5432/db");
        assertThat(out).contains("mongodb://m:***@host/db");
        assertThat(out).contains("https://host/path?x=1");
    }

    @Test
    @DisplayName("Redacts standalone JWT tokens")
    void testJwtRedaction() {
        String part1 = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9";
        String part2 = "eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4ifQ";
        String part3 = "SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c";
        String jwt = String.join(".", part1, part2, part3);
        String log = "Active session token: " + jwt + " for user";
        assertThat(SensitiveDataRedactor.redact(log))
            .contains("Active session token: *** for user")
            .doesNotContain(part3);
    }

    @Test
    @DisplayName("Recursively redacts secrets in nested maps and lists")
    @SuppressWarnings("unchecked")
    void testRedactDeepWithNestedStructures() {
        java.util.Map<String, Object> innerMap = new java.util.LinkedHashMap<>();
        innerMap.put("dbUrl", "jdbc:postgresql://appuser:superSecretPass@localhost:5432/appdb");
        innerMap.put("normalKey", "harmlessValue");

        java.util.List<Object> list = new java.util.ArrayList<>();
        list.add("Authorization: Bearer secret-bearer-999");
        list.add(innerMap);
        list.add(12345);

        java.util.Map<String, Object> root = new java.util.LinkedHashMap<>();
        root.put("items", list);
        root.put("header", "Authorization: Basic dXNlcjpwYXNz");

        Object deepSanitized = SensitiveDataRedactor.redactDeep(root);
        assertThat(deepSanitized).isInstanceOf(java.util.Map.class);

        java.util.Map<String, Object> sanitizedMap = (java.util.Map<String, Object>) deepSanitized;
        assertThat(sanitizedMap.get("header")).isEqualTo("Authorization: Basic ***");

        java.util.List<Object> sanitizedList = (java.util.List<Object>) sanitizedMap.get("items");
        assertThat(sanitizedList.get(0)).isEqualTo("Authorization: Bearer ***");
        assertThat(sanitizedList.get(2)).isEqualTo(12345);

        java.util.Map<String, Object> sanitizedInner = (java.util.Map<String, Object>) sanitizedList.get(1);
        assertThat(sanitizedInner.get("dbUrl")).isEqualTo("jdbc:postgresql://appuser:***@localhost:5432/appdb");
        assertThat(sanitizedInner.get("normalKey")).isEqualTo("harmlessValue");
    }
}
