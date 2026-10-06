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

@Testcontainers
class PostgresSchemaReaderTest {

    @Container
    private static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine");

    private static PostgresSchemaReader reader;

    @BeforeAll
    static void setUp() throws Exception {
        postgres.start();
        
        try (Connection conn = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement stmt = conn.createStatement()) {
             
            stmt.execute("CREATE TABLE users (id SERIAL PRIMARY KEY, username VARCHAR(50) UNIQUE NOT NULL)");
            stmt.execute("CREATE TABLE orders (id SERIAL PRIMARY KEY, user_id INT REFERENCES users(id), total DECIMAL)");
            stmt.execute("CREATE INDEX idx_orders_user_id ON orders(user_id)");
            
            stmt.execute("INSERT INTO users (username) VALUES ('test1'), ('test2')");
            stmt.execute("INSERT INTO orders (user_id, total) VALUES (1, 100.0), (2, 200.0)");
            
            // Generate some stats for pg_stat_user_tables
            stmt.execute("ANALYZE users");
            stmt.execute("ANALYZE orders");
        }

        reader = new PostgresSchemaReader(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    @AfterAll
    static void tearDown() {
        postgres.stop();
    }

    @Test
    void testInspectSchema() throws Exception {
        SchemaInfo schemaInfo = reader.inspectSchema("public");
        
        assertThat(schemaInfo.schemaName()).isEqualTo("public");
        
        // Check tables
        assertThat(schemaInfo.tables()).hasSize(2);
        assertThat(schemaInfo.tables()).extracting(TableInfo::tableName).containsExactlyInAnyOrder("users", "orders");
        
        // Check indexes
        assertThat(schemaInfo.indexes()).extracting(IndexInfo::indexName)
            .contains("users_pkey", "users_username_key", "orders_pkey", "idx_orders_user_id");
            
        // Check foreign keys
        assertThat(schemaInfo.foreignKeys()).hasSize(1);
        assertThat(schemaInfo.foreignKeys().getFirst().tableName()).isEqualTo("orders");
        assertThat(schemaInfo.foreignKeys().getFirst().foreignTableName()).isEqualTo("users");
    }

    @Test
    void testFindMissingIndexes() throws Exception {
        // Just verify it doesn't crash, missing index requires specific usage stats to show up
        MissingIndexAnalysis analysis = reader.findMissingIndexes();
        assertThat(analysis).isNotNull();
        assertThat(analysis.candidates()).isEmpty(); // No missing indexes generated yet
    }

    @Test
    void testFindSlowQueries() throws Exception {
        // Without pg_stat_statements extension installed in test container, it should return gracefully
        SlowQueryAnalysis analysis = reader.findSlowQueries();
        assertThat(analysis).isNotNull();
        assertThat(analysis.pgStatStatementsAvailable()).isFalse();
        assertThat(analysis.slowQueries()).isEmpty();
    }
}
