package dev.jvmmcp.server.tool;

import dev.jvmmcp.core.pg.PostgresModels;
import dev.jvmmcp.core.pg.PostgresSchemaReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PostgresToolHandlerTest {

    @Mock
    private PostgresSchemaReader mockReader;

    @Test
    @DisplayName("toolNames and toolDefinitions return all 3 PostgreSQL tools")
    void testToolNamesAndDefinitions() {
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);

        assertThat(handler.toolNames()).containsExactlyInAnyOrder(
            "inspect_pg_schema",
            "find_missing_indexes",
            "find_slow_queries"
        );
        assertThat(handler.toolDefinitions()).hasSize(3);
    }

    @Test
    @DisplayName("execute throws on missing url")
    void testMissingUrl() {
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);

        assertThatThrownBy(() -> handler.execute("inspect_pg_schema", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required argument: url");
    }

    @Test
    @DisplayName("execute throws on invalid JDBC url")
    void testInvalidJdbcUrl() {
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);

        assertThatThrownBy(() -> handler.execute("inspect_pg_schema", Map.of("url", "http://not-jdbc.com")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("must start with jdbc:postgresql://");
    }

    @Test
    @DisplayName("inspect_pg_schema executes with default and explicit schema")
    void testInspectPgSchema() throws Exception {
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));
        when(mockReader.inspectSchema("custom")).thenReturn(new PostgresModels.SchemaInfo("custom", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of("PG_USER", "postgres", "PG_PASSWORD", "secret");
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> {
            assertThat(user).isEqualTo("postgres");
            assertThat(pass).isEqualTo("secret");
            return mockReader;
        }, env::get);

        // default public
        Map<String, Object> resultDefault = handler.execute("inspect_pg_schema", Map.of("url", "jdbc:postgresql://localhost:5432/mydb"));
        assertThat(resultDefault.get("isError")).isNull();
        verify(mockReader).inspectSchema("public");

        // explicit schema
        Map<String, Object> resultCustom = handler.execute("inspect_pg_schema", Map.of("url", "jdbc:postgresql://localhost:5432/mydb", "schema", "custom"));
        assertThat(resultCustom.get("isError")).isNull();
        verify(mockReader).inspectSchema("custom");
    }

    @Test
    @DisplayName("find_missing_indexes defaults to a one MiB minimum")
    void testFindMissingIndexes() throws Exception {
        when(mockReader.findMissingIndexes("public", 1024L * 1024)).thenReturn(new PostgresModels.MissingIndexAnalysis(List.of(
            new PostgresModels.MissingIndexCandidate("users", 1000L, 5000L, 10L, 50L)
        )));

        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);
        Map<String, Object> result = handler.execute("find_missing_indexes", Map.of("url", "jdbc:postgresql://localhost:5432/mydb"));

        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text").toString()).contains("users");
        verify(mockReader).findMissingIndexes("public", 1024L * 1024);
    }

    @Test
    @DisplayName("find_missing_indexes handles null arguments")
    void testFindMissingIndexesWithNullArguments() {
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);

        assertThatThrownBy(() -> handler.execute("find_missing_indexes", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required argument: url");

        verifyNoInteractions(mockReader);
    }

    @ParameterizedTest
    @MethodSource("validMinimumTableSizes")
    void forwardsExplicitMinimumWithTheRequestedSchema(Number megabytes) throws Exception {
        long bytes = megabytes.longValue() * 1024 * 1024;
        when(mockReader.findMissingIndexes("custom", bytes))
            .thenReturn(new PostgresModels.MissingIndexAnalysis(List.of()));
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);

        Map<String, Object> result = handler.execute("find_missing_indexes", Map.of(
            "url", "jdbc:postgresql://localhost:5432/mydb", "schema", "custom", "minTableSizeMb", megabytes));

        assertThat(result.get("isError")).isNull();
        verify(mockReader).findMissingIndexes("custom", bytes);
    }

    static Stream<Number> validMinimumTableSizes() {
        return Stream.of(0, 5, 4096L, 8796093022207L);
    }

    @ParameterizedTest
    @MethodSource("invalidMinimumTableSizes")
    void rejectsInvalidMinimumBeforeRunningAnalysis(Object megabytes) throws Exception {
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("url", "jdbc:postgresql://localhost:5432/mydb");
        arguments.put("minTableSizeMb", megabytes);

        assertThatThrownBy(() -> handler.execute("find_missing_indexes", arguments))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("minTableSizeMb");
        // Validation can close an allocated reader, but must never issue a database query.
        verify(mockReader, never()).findMissingIndexes(anyString(), anyLong());
    }

    static Stream<Object> invalidMinimumTableSizes() {
        return Stream.of(-1, 1.5, new BigDecimal("0.01"), new BigDecimal("5.0000000000000000001"), "not-a-number", true, null,
            8796093022208L, Long.MAX_VALUE, new BigInteger("9223372036854775808"),
            Double.NaN, Double.POSITIVE_INFINITY);
    }

    @Test
    @SuppressWarnings("unchecked")
    void minimumSizeIsAnOptionalNonNegativeIntegerInToolSchema() {
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);
        Map<String, Object> definition = handler.toolDefinitions().stream()
            .filter(tool -> "find_missing_indexes".equals(tool.get("name"))).findFirst().orElseThrow();
        Map<String, Object> schema = (Map<String, Object>) definition.get("inputSchema");
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> minimumSize = (Map<String, Object>) properties.get("minTableSizeMb");

        assertThat(minimumSize).containsEntry("type", "integer");
        assertThat(((Number) minimumSize.get("minimum")).longValue()).isZero();
        assertThat(((Number) minimumSize.get("default")).longValue()).isEqualTo(1);
        assertThat((List<String>) schema.get("required")).containsExactly("url");
        verifyNoInteractions(mockReader);
    }

    @Test
    @DisplayName("find_slow_queries executes properly")
    void testFindSlowQueries() throws Exception {
        when(mockReader.findSlowQueries(20)).thenReturn(new PostgresModels.SlowQueryAnalysis(true, List.of(
            new PostgresModels.SlowQueryInfo("SELECT * FROM big_table", 10L, 500.0, 50.0, 1000L)
        )));

        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);
        Map<String, Object> result = handler.execute("find_slow_queries", Map.of("url", "jdbc:postgresql://localhost:5432/mydb"));

        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text").toString()).contains("SELECT * FROM big_table");
        verify(mockReader).findSlowQueries(20);
    }

    @ParameterizedTest
    @CsvSource({"5, 5", "100, 100", "500, 100", "0, 1"})
    @DisplayName("find_slow_queries passes the limit argument, clamped to between 1 and 100")
    void testFindSlowQueriesLimit(int requested, int expected) throws Exception {
        when(mockReader.findSlowQueries(expected)).thenReturn(new PostgresModels.SlowQueryAnalysis(true, List.of()));

        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);
        handler.execute("find_slow_queries", Map.of("url", "jdbc:postgresql://localhost:5432/mydb", "limit", requested));

        verify(mockReader).findSlowQueries(expected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void slowQueryLimitIsAnOptionalBoundedIntegerInToolSchema() {
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);
        Map<String, Object> definition = handler.toolDefinitions().stream()
            .filter(tool -> "find_slow_queries".equals(tool.get("name"))).findFirst().orElseThrow();
        Map<String, Object> schema = (Map<String, Object>) definition.get("inputSchema");
        Map<String, Object> limit = (Map<String, Object>) ((Map<String, Object>) schema.get("properties")).get("limit");

        assertThat(limit).containsEntry("type", "integer")
            .containsEntry("minimum", 1).containsEntry("maximum", 100).containsEntry("default", 20);
        assertThat((List<String>) schema.get("required")).containsExactly("url");
    }

    @Test
    @DisplayName("execute throws on invalid or malicious schema name")
    void testInvalidSchemaNameThrows() {
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);

        assertThatThrownBy(() -> handler.execute("inspect_pg_schema", Map.of(
            "url", "jdbc:postgresql://localhost:5432/mydb",
            "schema", "public; DROP TABLE users;--"
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid schema name");
    }

    @Test
    @DisplayName("execute throws on unknown postgres tool")
    void testUnknownTool() {
        PostgresToolHandler handler = new PostgresToolHandler((url, user, pass) -> mockReader);

        assertThatThrownBy(() -> handler.execute("unknown_pg_tool", Map.of("url", "jdbc:postgresql://localhost:5432/mydb")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown postgres tool");
    }
}
