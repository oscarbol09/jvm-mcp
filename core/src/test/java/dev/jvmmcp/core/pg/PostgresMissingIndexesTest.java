package dev.jvmmcp.core.pg;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class PostgresMissingIndexesTest {

    private static final long MIB = 1024L * 1024;
    private Connection fixture;
    private PostgresSchemaReader reader;
    private MockedConstruction<HikariDataSource> pools;
    private String jdbcUrl;

    @BeforeEach
    void setUp() throws Exception {
        String database = "jdbc:h2:mem:" + UUID.randomUUID();
        fixture = DriverManager.getConnection(database);
        try (var statement = fixture.createStatement()) {
            statement.execute("CREATE TABLE relation_sizes (relid BIGINT PRIMARY KEY, size_bytes BIGINT)");
            statement.execute("""
                CREATE TABLE pg_stat_user_tables (
                    schemaname VARCHAR, relname VARCHAR, relid BIGINT,
                    seq_scan BIGINT, seq_tup_read BIGINT, idx_scan BIGINT, idx_tup_fetch BIGINT
                )
                """);
            statement.execute("CREATE ALIAS pg_relation_size FOR '" + getClass().getName() + ".relationSize'");
        }
        // Execute the real query against deterministic statistics, without a PostgreSQL server or pool.
        pools = mockConstruction(HikariDataSource.class, (pool, context) ->
            when(pool.getConnection()).thenAnswer(invocation -> DriverManager.getConnection(database)));
        jdbcUrl = "jdbc:postgresql://localhost/" + UUID.randomUUID();
        reader = new PostgresSchemaReader(jdbcUrl, null, null);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (reader != null) reader.close();
        if (pools != null) {
            removeConstructedPoolsFromCache();
            pools.close();
        }
        if (fixture != null) fixture.close();
    }

    @SuppressWarnings("unchecked")
    private void removeConstructedPoolsFromCache() throws Exception {
        var field = PostgresSchemaReader.class.getDeclaredField("DATA_SOURCES");
        field.setAccessible(true);
        Map<String, HikariDataSource> cache =
            (Map<String, HikariDataSource>) field.get(null);

        synchronized (cache) {
            for (HikariDataSource pool : pools.constructed()) {
                cache.values().remove(pool);
            }
        }
    }

    @Test
    void defaultsToOneMiBAndIncludesTheBoundary() throws Exception {
        addTable(1, "public", "below", MIB - 1, 101, 20000, 0L);
        addTable(2, "public", "boundary", MIB, 101, 20000, 0L);
        addTable(3, "public", "larger", 2 * MIB, 200, 30000, null);

        var candidates = reader.findMissingIndexes("public").candidates();

        assertThat(candidates).extracting(PostgresModels.MissingIndexCandidate::tableName)
            .containsExactly("larger", "boundary");
        assertThat(candidates.getFirst().seqScanCount()).isEqualTo(200);
        assertThat(candidates.getFirst().seqTupRead()).isEqualTo(30000);
    }

    @Test
    void zeroDisablesOnlyTheSizeFilter() throws Exception {
        addTable(1, "public", "empty", 0, 101, 20000, null);
        addTable(2, "public", "small", 8192, 101, 30000, 0L);
        addTable(3, "public", "too_few_scans", 8192, 100, 50000, 0L);
        addTable(4, "public", "too_few_rows_read", 8192, 101, 10000, 0L);
        addTable(5, "public", "index_is_used_more", 8192, 101, 40000, 101L);
        addTable(6, "other", "wrong_schema", 8192, 101, 60000, 0L);

        assertThat(reader.findMissingIndexes("public", 0).candidates())
            .extracting(PostgresModels.MissingIndexCandidate::tableName)
            .containsExactly("small", "empty");
    }

    @Test
    void appliesCustomThresholdToTheRequestedSchema() throws Exception {
        addTable(1, "custom", "below", 5 * MIB - 1, 101, 20000, 0L);
        addTable(2, "custom", "boundary", 5 * MIB, 101, 20000, 0L);
        addTable(3, "public", "wrong_schema", 6 * MIB, 101, 30000, 0L);

        assertThat(reader.findMissingIndexes("custom", 5 * MIB).candidates())
            .extracting(PostgresModels.MissingIndexCandidate::tableName)
            .containsExactly("boundary");
    }

    @Test
    void preservesThresholdsLargerThanAnInteger() throws Exception {
        long threshold = 4096 * MIB;
        addTable(1, "public", "below", threshold - 1, 101, 20000, 0L);
        addTable(2, "public", "boundary", threshold, 101, 20000, 0L);

        assertThat(reader.findMissingIndexes("public", threshold).candidates())
            .extracting(PostgresModels.MissingIndexCandidate::tableName)
            .containsExactly("boundary");
    }

    @Test
    void rejectsNegativeThresholdBeforeOpeningAConnection() {
        assertThatThrownBy(() -> reader.findMissingIndexes("public", -1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(pools.constructed()).isEmpty();
    }

    @Test
    void closingReaderDoesNotCloseSharedPool() throws Exception {
        addTable(99, "public", "cached_table", MIB, 101, 20000, 0L);

        reader.findMissingIndexes("public");
        HikariDataSource pool = pools.constructed().getFirst();

        reader.close();

        try (PostgresSchemaReader secondReader = new PostgresSchemaReader(jdbcUrl, null, null)) {
            assertThat(secondReader.findMissingIndexes("public").candidates())
                .extracting(PostgresModels.MissingIndexCandidate::tableName)
                .containsExactly("cached_table");
        }

        assertThat(pools.constructed()).hasSize(1);
        verify(pool, never()).close();
    }

    private void addTable(long id, String schema, String table, long bytes,
                          long scans, long rowsRead, Long indexScans) throws SQLException {
        try (PreparedStatement sizes = fixture.prepareStatement("INSERT INTO relation_sizes VALUES (?, ?)");
             PreparedStatement stats = fixture.prepareStatement("INSERT INTO pg_stat_user_tables VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            sizes.setLong(1, id);
            sizes.setLong(2, bytes);
            sizes.executeUpdate();
            stats.setString(1, schema);
            stats.setString(2, table);
            stats.setLong(3, id);
            stats.setLong(4, scans);
            stats.setLong(5, rowsRead);
            stats.setObject(6, indexScans);
            stats.setLong(7, 50);
            stats.executeUpdate();
        }
    }

    public static long relationSize(Connection connection, long relationId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT size_bytes FROM relation_sizes WHERE relid = ?")) {
            statement.setLong(1, relationId);
            try (var result = statement.executeQuery()) {
                result.next();
                return result.getLong(1);
            }
        }
    }
}
