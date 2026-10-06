package dev.jvmmcp.core.pg;

import dev.jvmmcp.core.pg.PostgresModels.*;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class PostgresSchemaReader {

    private static final Map<String, HikariDataSource> DATA_SOURCES = new ConcurrentHashMap<>();

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresSchemaReader(String jdbcUrl, String username, String password) {
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    private Connection getConnection() throws SQLException {
        String cacheKey = jdbcUrl + "|" + (username != null ? username : "");
        
        HikariDataSource ds = DATA_SOURCES.computeIfAbsent(cacheKey, key -> {
            HikariConfig config = new HikariConfig();
            config.setJdbcUrl(jdbcUrl);
            if (username != null && !username.isBlank()) {
                config.setUsername(username);
            }
            if (password != null && !password.isBlank()) {
                config.setPassword(password);
            }
            config.setMaximumPoolSize(3);
            config.setConnectionTimeout(5000);
            config.setIdleTimeout(600000);
            return new HikariDataSource(config);
        });
        
        return ds.getConnection();
    }

    public SchemaInfo inspectSchema(String schemaName) throws SQLException {
        try (Connection conn = getConnection()) {
            List<TableInfo> tables = getTables(conn, schemaName);
            List<IndexInfo> indexes = getIndexes(conn, schemaName);
            List<ForeignKeyInfo> fks = getForeignKeys(conn, schemaName);
            return new SchemaInfo(schemaName, tables, indexes, fks);
        }
    }

    private List<TableInfo> getTables(Connection conn, String schemaName) throws SQLException {
        String sql = """
            SELECT
                t.table_name,
                c.reltuples::bigint AS estimated_row_count,
                pg_relation_size(t.table_name::regclass) AS size_bytes,
                (SELECT count(*) FROM information_schema.columns col WHERE col.table_schema = t.table_schema AND col.table_name = t.table_name) AS column_count
            FROM
                information_schema.tables t
            JOIN pg_class c ON c.relname = t.table_name
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = t.table_schema
            WHERE
                t.table_schema = ? AND t.table_type = 'BASE TABLE'
            """;
        List<TableInfo> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new TableInfo(
                        rs.getString("table_name"),
                        rs.getLong("estimated_row_count"),
                        rs.getInt("column_count"),
                        rs.getLong("size_bytes")
                    ));
                }
            }
        }
        return result;
    }

    private List<IndexInfo> getIndexes(Connection conn, String schemaName) throws SQLException {
        String sql = """
            SELECT
                i.tablename AS table_name,
                i.indexname AS index_name,
                i.indexdef AS index_def,
                pg_relation_size(i.indexname::regclass) AS size_bytes,
                ix.indisunique AS is_unique,
                ix.indisprimary AS is_primary
            FROM
                pg_indexes i
            JOIN pg_class c ON c.relname = i.indexname
            JOIN pg_index ix ON ix.indexrelid = c.oid
            WHERE
                i.schemaname = ?
            """;
        List<IndexInfo> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new IndexInfo(
                        rs.getString("table_name"),
                        rs.getString("index_name"),
                        rs.getString("index_def"),
                        rs.getLong("size_bytes"),
                        rs.getBoolean("is_unique"),
                        rs.getBoolean("is_primary")
                    ));
                }
            }
        }
        return result;
    }

    private List<ForeignKeyInfo> getForeignKeys(Connection conn, String schemaName) throws SQLException {
        String sql = """
            SELECT
                tc.table_name,
                tc.constraint_name,
                ccu.table_name AS foreign_table_name
            FROM
                information_schema.table_constraints AS tc
                JOIN information_schema.constraint_column_usage AS ccu
                  ON ccu.constraint_name = tc.constraint_name
            WHERE
                tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = ?
            """;
        List<ForeignKeyInfo> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(new ForeignKeyInfo(
                        rs.getString("table_name"),
                        rs.getString("constraint_name"),
                        rs.getString("foreign_table_name")
                    ));
                }
            }
        }
        return result;
    }

    public MissingIndexAnalysis findMissingIndexes() throws SQLException {
        String sql = """
            SELECT
                relname AS table_name,
                seq_scan,
                seq_tup_read,
                idx_scan,
                idx_tup_fetch
            FROM
                pg_stat_user_tables
            WHERE
                seq_scan > 100 AND seq_tup_read > 10000
                AND (idx_scan IS NULL OR seq_scan > idx_scan)
            ORDER BY
                seq_tup_read DESC
            LIMIT 20
            """;
        List<MissingIndexCandidate> candidates = new ArrayList<>();
        try (Connection conn = getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                candidates.add(new MissingIndexCandidate(
                    rs.getString("table_name"),
                    rs.getLong("seq_scan"),
                    rs.getLong("seq_tup_read"),
                    rs.getLong("idx_scan"),
                    rs.getLong("idx_tup_fetch")
                ));
            }
        }
        return new MissingIndexAnalysis(candidates);
    }

    public SlowQueryAnalysis findSlowQueries() throws SQLException {
        boolean hasExtension = false;
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'")) {
                if (rs.next()) {
                    hasExtension = true;
                }
            } catch (SQLException e) {
                // Ignore, might not have permission to read pg_extension
            }

            if (!hasExtension) {
                return new SlowQueryAnalysis(false, List.of());
            }

            String sql = """
                SELECT
                    query,
                    calls,
                    mean_exec_time AS mean_time_ms,
                    max_exec_time AS max_time_ms,
                    rows
                FROM
                    pg_stat_statements
                ORDER BY
                    mean_exec_time DESC
                LIMIT 20
                """;
            List<SlowQueryInfo> slowQueries = new ArrayList<>();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(sql)) {
                while (rs.next()) {
                    slowQueries.add(new SlowQueryInfo(
                        rs.getString("query"),
                        rs.getLong("calls"),
                        rs.getDouble("mean_time_ms"),
                        rs.getDouble("max_time_ms"),
                        rs.getLong("rows")
                    ));
                }
            } catch (SQLException e) {
                // Fallback for older Postgres versions where it's mean_time instead of mean_exec_time
                return new SlowQueryAnalysis(false, List.of());
            }
            return new SlowQueryAnalysis(true, slowQueries);
        }
    }
}
