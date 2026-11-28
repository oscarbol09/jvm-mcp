package dev.jvmmcp.core.pg;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;

class PostgresSlowQueriesTest {
    private PostgresSchemaReader reader;
    private MockedConstruction<HikariDataSource> pools;
    private Connection connection;
    private PreparedStatement statement;
    private ResultSet extension;
    private ResultSet results;

    @BeforeEach
    void setUp() throws Exception {
        connection = mock(Connection.class);
        statement = mock(PreparedStatement.class);
        extension = mock(ResultSet.class);
        results = mock(ResultSet.class);
        Statement extensionCheck = mock(Statement.class);
        when(connection.createStatement()).thenReturn(extensionCheck);
        when(extensionCheck.executeQuery(anyString())).thenReturn(extension);
        when(extension.next()).thenReturn(true);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(results);
        pools =
                mockConstruction(
                        HikariDataSource.class,
                        (pool, context) -> when(pool.getConnection()).thenReturn(connection));
        reader =
                new PostgresSchemaReader(
                        "jdbc:postgresql://localhost/" + UUID.randomUUID(), null, null);
    }

    @AfterEach
    @SuppressWarnings("unchecked")
    void tearDown() throws Exception {
        if (reader != null) reader.close();
        if (pools != null) {
            var field = PostgresSchemaReader.class.getDeclaredField("DATA_SOURCES");
            field.setAccessible(true);
            Map<String, HikariDataSource> cache = (Map<String, HikariDataSource>) field.get(null);
            synchronized (cache) {
                for (HikariDataSource pool : pools.constructed()) cache.values().remove(pool);
            }
            pools.close();
        }
    }

    @Test
    void mapsCumulativeTimeAndRanksBeforeLimiting() throws Exception {
        when(results.next()).thenReturn(true, true, false);
        when(results.getString("query"))
                .thenReturn("SELECT * FROM hot_endpoint", "ALTER USER app SET password = 'secret'");
        when(results.getLong("calls")).thenReturn(500000L, 1L);
        when(results.getDouble("compatible_total_time")).thenReturn(15000000.0, 150.0);
        when(results.getDouble("compatible_mean_time")).thenReturn(30.0, 150.0);
        when(results.getDouble("compatible_max_time")).thenReturn(60.0, 150.0);
        when(results.getLong("rows")).thenReturn(500000L, 0L);

        var analysis = reader.findSlowQueries();

        assertThat(analysis.pgStatStatementsAvailable()).isTrue();
        assertThat(analysis.slowQueries()).hasSize(2);
        assertThat(analysis.slowQueries().getFirst())
                .isEqualTo(
                        new PostgresModels.SlowQueryInfo(
                                "SELECT * FROM hot_endpoint",
                                500000L,
                                15000000.0,
                                30.0,
                                60.0,
                                500000L));
        assertThat(analysis.slowQueries().getLast().query())
                .contains("***")
                .doesNotContain("secret");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(sql.capture());
        assertThat(sql.getValue().replaceAll("\\s+", " "))
                .contains(
                        "COALESCE( (row_to_json(pss)->>'total_exec_time')::numeric, (row_to_json(pss)->>'total_time')::numeric, 0.0 ) AS compatible_total_time")
                .contains("dbid = (SELECT oid FROM pg_database WHERE datname = current_database())")
                .contains("ORDER BY compatible_total_time DESC LIMIT ?");
        verify(statement).setInt(1, 20);
        verify(statement).setQueryTimeout(10);
    }

    @ParameterizedTest
    @CsvSource({"0, 1", "5, 5", "1000, 100"})
    void bindsBoundedLimit(int requested, int expected) throws Exception {
        reader.findSlowQueries(requested);
        verify(statement).setInt(1, expected);
    }

    @Test
    void returnsEmptyAnalysisWithoutExtension() throws Exception {
        when(extension.next()).thenReturn(false);
        var analysis = reader.findSlowQueries();
        assertThat(analysis.pgStatStatementsAvailable()).isFalse();
        assertThat(analysis.slowQueries()).isEmpty();
        verify(connection, never()).prepareStatement(anyString());
    }
}
