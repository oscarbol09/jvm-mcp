package dev.jvmmcp.server.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PostgresUrls.withDefaultDatabase")
class PostgresUrlsTest {

    private static Function<String, String> env(Map<String, String> values) {
        return values::get;
    }

    @Test
    @DisplayName("appends PGDATABASE when the URL has no database path")
    void appendsCanonicalDatabase() {
        assertThat(
                        PostgresUrls.withDefaultDatabase(
                                "jdbc:postgresql://localhost:5432",
                                env(Map.of("PGDATABASE", "myapp"))))
                .isEqualTo("jdbc:postgresql://localhost:5432/myapp");
    }

    @Test
    @DisplayName("appends after a trailing slash")
    void appendsAfterTrailingSlash() {
        assertThat(
                        PostgresUrls.withDefaultDatabase(
                                "jdbc:postgresql://localhost:5432/",
                                env(Map.of("PGDATABASE", "myapp"))))
                .isEqualTo("jdbc:postgresql://localhost:5432/myapp");
    }

    @Test
    @DisplayName("PGDATABASE wins when both canonical and legacy are set")
    void canonicalWinsOverLegacy() {
        assertThat(
                        PostgresUrls.withDefaultDatabase(
                                "jdbc:postgresql://localhost:5432",
                                env(Map.of("PGDATABASE", "canonical", "PG_DATABASE", "legacy"))))
                .isEqualTo("jdbc:postgresql://localhost:5432/canonical");
    }

    @Test
    @DisplayName("an empty PGDATABASE is taken as set (no fallback) and leaves the URL unchanged")
    void emptyCanonicalDoesNotFallBackToLegacy() {
        // firstEnv returns the empty string because it is non-null, so PG_DATABASE is not
        // consulted;
        // an empty database name then yields no change. This mirrors the MCP handler's behaviour.
        assertThat(
                        PostgresUrls.withDefaultDatabase(
                                "jdbc:postgresql://localhost:5432",
                                env(Map.of("PGDATABASE", "", "PG_DATABASE", "legacy"))))
                .isEqualTo("jdbc:postgresql://localhost:5432");
    }

    @Test
    @DisplayName("keeps an explicit database already present in the URL")
    void explicitDatabaseWins() {
        assertThat(
                        PostgresUrls.withDefaultDatabase(
                                "jdbc:postgresql://localhost:5432/explicit",
                                env(Map.of("PGDATABASE", "myapp"))))
                .isEqualTo("jdbc:postgresql://localhost:5432/explicit");
    }

    @Test
    @DisplayName("returns the URL unchanged when no database variable is set")
    void unchangedWhenNoEnv() {
        assertThat(
                        PostgresUrls.withDefaultDatabase(
                                "jdbc:postgresql://localhost:5432", env(Map.of())))
                .isEqualTo("jdbc:postgresql://localhost:5432");
    }

    @Test
    @DisplayName("URL-encodes database names with spaces, plus and slash")
    void encodesSpecialCharacters() {
        assertThat(
                        PostgresUrls.withDefaultDatabase(
                                "jdbc:postgresql://localhost:5432",
                                env(Map.of("PGDATABASE", "team db/x+y"))))
                .isEqualTo("jdbc:postgresql://localhost:5432/team%20db%2Fx%2By");
    }

    @Test
    @DisplayName("preserves query parameters while inserting the database")
    void preservesQueryParameters() {
        assertThat(
                        PostgresUrls.withDefaultDatabase(
                                "jdbc:postgresql://localhost:5432?sslmode=require",
                                env(Map.of("PGDATABASE", "myapp"))))
                .isEqualTo("jdbc:postgresql://localhost:5432/myapp?sslmode=require");
    }

    @Test
    @DisplayName("returns null and non-jdbc URLs unchanged (guards)")
    void guardsNullAndNonJdbc() {
        assertThat(PostgresUrls.withDefaultDatabase(null, env(Map.of("PGDATABASE", "myapp"))))
                .isNull();
        assertThat(
                        PostgresUrls.withDefaultDatabase(
                                "postgresql://localhost:5432", env(Map.of("PGDATABASE", "myapp"))))
                .isEqualTo("postgresql://localhost:5432");
    }
}
