package dev.jvmmcp.core.pg;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class PostgresSchemaReaderValidationTest {

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://localhost:5432/mydb",
                "jdbc:postgresql://127.0.0.1:5432/testdb",
                "jdbc:postgresql://[::1]:5432/ipv6db"
            })
    @DisplayName("Valid loopback JDBC URLs should pass validation")
    void shouldAcceptValidLoopbackUrls(String url) {
        assertThatCode(() -> PostgresSchemaReader.validateJdbcUrl(url)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("SSRF Bypass: Embedded userinfo in authority must be rejected")
    void shouldRejectUserinfoBypass() {
        String bypassUrl = "jdbc:postgresql://localhost:secret@attacker-db.com:5432/evil_db";
        assertThatThrownBy(() -> PostgresSchemaReader.validateJdbcUrl(bypassUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Embedded userinfo");
    }

    @Test
    @DisplayName("SSRF Bypass: Multi-host connection syntax must be rejected")
    void shouldRejectMultiHostBypass() {
        String multiHostUrl =
                "jdbc:postgresql://localhost:5432,internal-database.corp:5432/db?targetServerType=secondary";
        assertThatThrownBy(() -> PostgresSchemaReader.validateJdbcUrl(multiHostUrl))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Multi-host");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://169.254.169.254/latest/meta-data",
                "jdbc:postgresql://google.com:5432/db",
                "jdbc:postgresql://10.0.0.1:5432/internal",
                "jdbc:postgresql://192.168.1.50:5432/lan"
            })
    @DisplayName("Non-loopback hosts must be rejected by SSRF protection")
    void shouldRejectNonLoopbackHosts(String url) {
        assertThatThrownBy(() -> PostgresSchemaReader.validateJdbcUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://localhost:5432/db?socketFactory=org.newsclub.net.unix.AFUNIXSocketFactory$FactoryArg",
                "jdbc:postgresql://localhost:5432/db?sslfactory=org.example.EvilFactory",
                "jdbc:postgresql://localhost:5432/db?sslrootcert=/etc/passwd",
                "jdbc:postgresql://localhost:5432/db?options=-c%20statement_timeout=1",
                "jdbc:postgresql://localhost:5432/db?sslmode=disable",
                "jdbc:postgresql://localhost:5432/db?loggerFile=/tmp/evil.log",
                "jdbc:postgresql://localhost:5432/db?loggerLevel=TRACE",
                "jdbc:postgresql://localhost:5432/db?socketFactoryArg=something",
                "jdbc:postgresql://localhost:5432/db?sslfactoryarg=something",
                "jdbc:postgresql://localhost:5432/db?allowEncodingChanges=true"
            })
    @DisplayName("Dangerous connection property parameters must be rejected")
    void shouldRejectDangerousParameters(String url) {
        assertThatThrownBy(() -> PostgresSchemaReader.validateJdbcUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dangerous JDBC connection parameter");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "jdbc:postgresql://localhost:5432/db?authenticationPluginClassName=com.evil.Plugin",
                "jdbc:postgresql://localhost:5432/db?sslhostnameverifier=com.evil.Verifier",
                "jdbc:postgresql://localhost:5432/db?sslpassword=correct-horse",
                "jdbc:postgresql://localhost:5432/db?service=prod",
                "jdbc:postgresql://localhost:5432/db?jaasApplicationName=evil",
                "jdbc:postgresql://localhost:5432/db?gsslib=evil",
                "jdbc:postgresql://localhost:5432/db?sspiServiceClass=evil",
                "jdbc:postgresql://localhost:5432/db?classLoaderStrategy=com.evil.Strategy"
            })
    @DisplayName("pgjdbc properties that load classes or override the connection are rejected")
    void shouldRejectAdditionalDangerousParameters(String url) {
        assertThatThrownBy(() -> PostgresSchemaReader.validateJdbcUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dangerous JDBC connection parameter");
    }

    @Test
    @DisplayName("Blocked parameter names are matched case-insensitively under a Turkish locale")
    void shouldMatchBlockedParametersRegardlessOfLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThatThrownBy(
                            () ->
                                    PostgresSchemaReader.validateJdbcUrl(
                                            "jdbc:postgresql://localhost:5432/db?LOGGERFILE=/tmp/pwn"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("loggerfile");
            assertThatThrownBy(
                            () ->
                                    PostgresSchemaReader.validateJdbcUrl(
                                            "jdbc:postgresql://localhost:5432/db?SSLMODE=DISABLE"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sslmode");
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    @DisplayName("Validation reports the blocked parameter name without echoing query values")
    void shouldNotLeakQueryValuesInErrorMessage() {
        String url = "jdbc:postgresql://localhost:5432/db?password=hunter2&sslpassword=topsecret";
        assertThatThrownBy(() -> PostgresSchemaReader.validateJdbcUrl(url))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sslpassword")
                .hasMessageNotContaining("topsecret")
                .hasMessageNotContaining("hunter2");
    }

    @Test
    @DisplayName("A blocked word inside a harmless value is not rejected")
    void shouldAllowHarmlessParametersThatMentionBlockedWords() {
        assertThatCode(
                        () ->
                                PostgresSchemaReader.validateJdbcUrl(
                                        "jdbc:postgresql://localhost:5432/db?applicationName=loggerfile"))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                PostgresSchemaReader.validateJdbcUrl(
                                        "jdbc:postgresql://localhost:5432/db?sslmode=require&connectTimeout=5"))
                .doesNotThrowAnyException();
        assertThatCode(
                        () ->
                                PostgresSchemaReader.validateJdbcUrl(
                                        "jdbc:postgresql://localhost:5432/db?applicationName=100%25"))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName(
            "validateSchemaName accepts valid SQL identifiers and defaults blank/null to public")
    void shouldValidateValidSchemaNames() {
        org.assertj.core.api.Assertions.assertThat(PostgresSchemaReader.validateSchemaName(null))
                .isEqualTo("public");
        org.assertj.core.api.Assertions.assertThat(PostgresSchemaReader.validateSchemaName(""))
                .isEqualTo("public");
        org.assertj.core.api.Assertions.assertThat(PostgresSchemaReader.validateSchemaName("   "))
                .isEqualTo("public");
        org.assertj.core.api.Assertions.assertThat(
                        PostgresSchemaReader.validateSchemaName("public"))
                .isEqualTo("public");
        org.assertj.core.api.Assertions.assertThat(
                        PostgresSchemaReader.validateSchemaName("my_schema_2026"))
                .isEqualTo("my_schema_2026");
    }

    @Test
    @DisplayName(
            "validateSchemaName folds an unquoted schema name to lowercase, as PostgreSQL does")
    void shouldFoldSchemaNamesToLowercase() {
        org.assertj.core.api.Assertions.assertThat(
                        PostgresSchemaReader.validateSchemaName("Public"))
                .isEqualTo("public");
        org.assertj.core.api.Assertions.assertThat(
                        PostgresSchemaReader.validateSchemaName("MY_SCHEMA"))
                .isEqualTo("my_schema");
        org.assertj.core.api.Assertions.assertThat(
                        PostgresSchemaReader.validateSchemaName("  Sales  "))
                .isEqualTo("sales");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "public; DROP TABLE users;--",
                "schema' OR '1'='1",
                "schema-with-dashes",
                "123startsWithNumber",
                "schema$name",
                "schema name with spaces",
                "a_very_long_schema_name_that_exceeds_sixty_three_characters_in_total_length_which_is_invalid"
            })
    @DisplayName("validateSchemaName rejects malicious, invalid, or overly long SQL identifiers")
    void shouldRejectInvalidSchemaNames(String invalidSchema) {
        assertThatThrownBy(() -> PostgresSchemaReader.validateSchemaName(invalidSchema))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid schema name");
    }

    @Test
    @DisplayName("sanitizeQuery redacts tokens and API keys in slow queries, not only passwords")
    void shouldRedactCredentialsInSlowQueries() {
        String bearer =
                PostgresSchemaReader.sanitizeQuery(
                        "INSERT INTO audit (header) VALUES ('Bearer abcDEF123456.token')");
        org.assertj.core.api.Assertions.assertThat(bearer)
                .contains("Bearer ***")
                .doesNotContain("abcDEF123456");

        String apiKey =
                PostgresSchemaReader.sanitizeQuery(
                        "UPDATE integrations SET api_key = 'sk_live_51Habc' WHERE id = 7");
        org.assertj.core.api.Assertions.assertThat(apiKey)
                .contains("api_key=***")
                .doesNotContain("sk_live_51Habc");

        String jwt =
                PostgresSchemaReader.sanitizeQuery(
                        "SELECT * FROM sessions WHERE jwt = 'eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.c2lnbmF0dXJl'");
        org.assertj.core.api.Assertions.assertThat(jwt).doesNotContain("eyJhbGciOiJIUzI1NiJ9");

        String password =
                PostgresSchemaReader.sanitizeQuery("ALTER USER app SET password = 'hunter2'");
        org.assertj.core.api.Assertions.assertThat(password).doesNotContain("hunter2");

        String plain = "SELECT id, name FROM customers WHERE region = $1";
        org.assertj.core.api.Assertions.assertThat(PostgresSchemaReader.sanitizeQuery(plain))
                .isEqualTo(plain);
        org.assertj.core.api.Assertions.assertThat(PostgresSchemaReader.sanitizeQuery(null))
                .isNull();
    }

    @ParameterizedTest
    @CsvSource({"5, 5", "20, 20", "100, 100", "101, 100", "0, 1", "-3, 1"})
    @DisplayName("Slow query limit is clamped to between 1 and 100")
    void slowQueryLimitIsClamped(int requested, int expected) {
        org.assertj.core.api.Assertions.assertThat(PostgresSchemaReader.slowQueryLimit(requested))
                .isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({"-1, 0", "-5, 0", "0, 0", "1, 1", "42, 42"})
    @DisplayName("Unanalyzed tables report a zero row estimate instead of a negative count")
    void negativeRowEstimatesBecomeZero(long raw, long expected) {
        org.assertj.core.api.Assertions.assertThat(PostgresSchemaReader.nonNegativeRowEstimate(raw))
                .isEqualTo(expected);
    }
}
