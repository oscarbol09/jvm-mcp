package dev.jvmmcp.core.pg;

import dev.jvmmcp.core.pg.PostgresModels.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class PostgresSchemaReaderTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("public.ecr.aws/docker/library/postgres:15-alpine");

    private static PostgresSchemaReader reader;

    @BeforeAll
    static void setUp() throws Exception {
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
             
            stmt.execute("CREATE TABLE users (id SERIAL PRIMARY KEY, username VARCHAR(50) UNIQUE NOT NULL)");
            stmt.execute("CREATE TABLE orders (id SERIAL PRIMARY KEY, user_id INT REFERENCES users(id), total DECIMAL)");
            stmt.execute("CREATE INDEX idx_orders_user_id ON orders(user_id)");
            
            stmt.execute("INSERT INTO users (username) SELECT 'test' || i FROM generate_series(1, 150) AS i");
            stmt.execute("INSERT INTO orders (user_id, total) SELECT i, i * 10.0 FROM generate_series(1, 150) AS i");
            
            // Create a "Large" table > 10MB to test the missing index size heuristic (Trick A)
            stmt.execute("CREATE TABLE large_table AS SELECT i AS id, md5(i::text) AS dummy_data FROM generate_series(1, 400000) AS i");
            // Do sequential scans on large_table to trigger missing index heuristics (needs > 100 seq scans)
            for (int i = 0; i < 105; i++) {
                stmt.execute("SELECT count(*) FROM large_table WHERE dummy_data = 'nonexistent'");
                stmt.execute("SELECT count(*) FROM users WHERE username = 'nonexistent'");
                stmt.execute("SELECT count(*) FROM orders WHERE total = -1");
            }
            
            // Generate some stats for pg_stat_user_tables
            stmt.execute("ANALYZE users");
            stmt.execute("ANALYZE orders");
            stmt.execute("ANALYZE large_table");
        }

        reader = new PostgresSchemaReader(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    static void tearDown() {
        // Container is stopped automatically by Testcontainers
    }

    @Test
    void testInspectSchema() throws Exception {
        SchemaInfo schemaInfo = reader.inspectSchema("public");
        assertThat(schemaInfo.schemaName()).isEqualTo("public");
        assertThat(schemaInfo.tables()).extracting(TableInfo::tableName).contains("users", "orders", "large_table");
        
        assertThat(schemaInfo.foreignKeys()).hasSize(1);
        assertThat(schemaInfo.foreignKeys().getFirst().tableName()).isEqualTo("orders");
        assertThat(schemaInfo.foreignKeys().getFirst().foreignTableName()).isEqualTo("users");
        assertThat(schemaInfo.foreignKeys().getFirst().sourceColumn()).isEqualTo("user_id");
        assertThat(schemaInfo.foreignKeys().getFirst().foreignColumn()).isEqualTo("id");
    }

    @Test
    void testFindMissingIndexesThreshold() throws Exception {
        MissingIndexAnalysis analysis = reader.findMissingIndexes("public");
        assertThat(analysis).isNotNull();
        
        // large_table should appear because it exceeds 10MB and we did a Seq Scan
        assertThat(analysis.candidates()).extracting(MissingIndexCandidate::tableName).contains("large_table");
        
        // users and orders MUST NOT appear, even if they had seq scans, because they are < 10MB
        assertThat(analysis.candidates()).extracting(MissingIndexCandidate::tableName).doesNotContain("users", "orders");
    }

    @Test
    void testLruDataSourceEviction() throws Exception {
        // We simulate 6 distinct logical DBs by appending an application_name param
        for (int i = 1; i <= 6; i++) {
            String dynamicUrl = postgres.getJdbcUrl() + "&ApplicationName=tenant_" + i;
            PostgresSchemaReader dynamicReader = new PostgresSchemaReader(dynamicUrl, postgres.getUsername(), postgres.getPassword());
            dynamicReader.inspectSchema("public"); // triggers getConnection()
        }
        
        // The cache capacity is 5. We inserted 6. The 1st one (tenant_1) should have been evicted.
        // We can't easily assert the static map is size 5 without reflection, but we can verify
        // no OutOfMemory occurs and the operations succeed. We can verify the logic via reflection if necessary:
        try {
            java.lang.reflect.Field field = PostgresSchemaReader.class.getDeclaredField("DATA_SOURCES");
            field.setAccessible(true);
            java.util.Map<?, ?> cache = (java.util.Map<?, ?>) field.get(null);
            assertThat(cache).hasSize(5);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testFindSlowQueries() throws Exception {
        SlowQueryAnalysis analysis = reader.findSlowQueries();
        assertThat(analysis).isNotNull();
        assertThat(analysis.pgStatStatementsAvailable()).isFalse();
    }
}

