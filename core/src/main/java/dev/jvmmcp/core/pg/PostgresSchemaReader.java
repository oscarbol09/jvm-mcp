package dev.jvmmcp.core.pg;

import dev.jvmmcp.core.pg.PostgresModels.*;
import dev.jvmmcp.core.util.SensitiveDataRedactor;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PostgresSchemaReader implements AutoCloseable {

    public static final long DEFAULT_MIN_TABLE_SIZE_BYTES = 1024L * 1024L;
    public static final int DEFAULT_SLOW_QUERY_LIMIT = 20;
    public static final int MAX_SLOW_QUERY_LIMIT = 100;

    private static final int MAX_CACHED_POOLS = 5;
    private static final int QUERY_TIMEOUT_SECONDS = 10;
    
    // LRU Cache for DataSources to prevent connection leaks across different databases
    private static final Map<String, HikariDataSource> DATA_SOURCES = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, HikariDataSource> eldest) {
            if (size() > MAX_CACHED_POOLS) {
                try {
                    eldest.getValue().close();
                } catch (Exception ignored) {}
                return true;
            }
            return false;
        }
    };

    static {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            synchronized (DATA_SOURCES) {
                DATA_SOURCES.values().forEach(ds -> {
                    try { ds.close(); } catch (Exception ignored) {}
                });
            }
        }));
    }

    private final String jdbcUrl;
    private final String username;
    private final String password;

    public PostgresSchemaReader(String jdbcUrl, String username, String password) {
        validateJdbcUrl(jdbcUrl);
        this.jdbcUrl = jdbcUrl;
        this.username = username;
        this.password = password;
    }

    public static void validateJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || !jdbcUrl.startsWith("jdbc:postgresql://")) {
            throw new IllegalArgumentException("Invalid PostgreSQL JDBC URL: must start with jdbc:postgresql://");
        }
        if (jdbcUrl.contains(",")) {
            throw new IllegalArgumentException("SSRF Protection: Multi-host JDBC URLs are prohibited");
        }
        
        String uriStr = jdbcUrl.substring(5); // strip "jdbc:"
        java.net.URI uri;
        try {
            uri = java.net.URI.create(uriStr);
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed PostgreSQL JDBC URL: " + e.getMessage(), e);
        }

        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("SSRF Protection: Embedded userinfo in JDBC URL is prohibited");
        }

        String rawAuthority = uri.getRawAuthority();
        if (rawAuthority != null && rawAuthority.contains(",")) {
            throw new IllegalArgumentException("SSRF Protection: Multi-host JDBC URLs are prohibited");
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("SSRF Protection: Missing host in JDBC URL");
        }
        String cleanHost = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean isLiteralLoopback = cleanHost.equalsIgnoreCase("localhost")
            || cleanHost.equals("127.0.0.1")
            || cleanHost.equals("::1")
            || cleanHost.equals("0:0:0:0:0:0:0:1");

        if (!isLiteralLoopback) {
            throw new IllegalArgumentException("SSRF Protection: JDBC URL host must target localhost or loopback (got: " + host + ")");
        }

        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(cleanHost);
            for (java.net.InetAddress addr : addresses) {
                if (!addr.isLoopbackAddress()) {
                    throw new IllegalArgumentException("SSRF Protection: Resolved address " + addr.getHostAddress() + " for host '" + host + "' is not a loopback address");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("SSRF Protection: Unable to resolve host '" + host + "': " + e.getMessage(), e);
        }

        String query = uri.getQuery();
        if (query != null && !query.isBlank()) {
            String lower = query.toLowerCase();
            if (lower.contains("socketfactory") ||
                lower.contains("socketfactoryarg") ||
                lower.contains("sslfactory") ||
                lower.contains("sslfactoryarg") ||
                lower.contains("sslrootcert") ||
                lower.contains("sslcert") ||
                lower.contains("sslkey") ||
                lower.contains("sslpasswordcallback") ||
                lower.contains("loggerfile") ||
                lower.contains("loggerlevel") ||
                lower.contains("allowencodingchanges") ||
                lower.contains("options=") ||
                lower.contains("sslmode=disable")) {
                throw new IllegalArgumentException("Security Protection: Dangerous JDBC connection parameter detected in URL: " + query);
            }
        }
    }

    public static String validateSchemaName(String schemaName) {
        if (schemaName == null || schemaName.isBlank()) {
            return "public";
        }
        String trimmed = schemaName.trim();
        if (trimmed.length() > 63 || !trimmed.matches("^[a-zA-Z_][a-zA-Z0-9_]*$")) {
            throw new IllegalArgumentException("Invalid schema name: must be a valid SQL identifier (alphanumeric and underscore, starting with letter/underscore, max 63 chars)");
        }
        // PostgreSQL folds unquoted identifiers to lowercase, and the catalogs this name is
        // compared against store them that way: "Sales" names the schema sales.
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private static String buildCacheKey(String jdbcUrl, String username, String password) {
        String secretHash = "none";
        if (password != null && !password.isEmpty()) {
            try {
                java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                StringBuilder hex = new StringBuilder();
                for (byte b : digest) {
                    hex.append(String.format("%02x", b));
                }
                secretHash = hex.toString();
            } catch (Exception e) {
                secretHash = String.valueOf(password.hashCode());
            }
        }
        return jdbcUrl + "|" + (username != null ? username : "") + "|" + secretHash;
    }

    @Override
    public void close() {
        String cacheKey = buildCacheKey(jdbcUrl, username, password);
        synchronized (DATA_SOURCES) {
            HikariDataSource ds = DATA_SOURCES.remove(cacheKey);
            if (ds != null) {
                try {
                    ds.close();
                } catch (Exception ignored) {}
            }
        }
    }

    private Connection getConnection() throws SQLException {
        String cacheKey = buildCacheKey(jdbcUrl, username, password);
        
        HikariDataSource ds;
        synchronized (DATA_SOURCES) {
            ds = DATA_SOURCES.get(cacheKey);
            if (ds == null) {
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
                config.setReadOnly(true); // Optimization: Schema introspection is strictly read-only
                
                ds = new HikariDataSource(config);
                DATA_SOURCES.put(cacheKey, ds);
            }
        }
        
        return ds.getConnection();
    }

    public SchemaInfo inspectSchema(String schemaName) throws SQLException {
        String safeSchema = validateSchemaName(schemaName);
        try (Connection conn = getConnection()) {
            List<TableInfo> tables = getTables(conn, safeSchema);
            List<IndexInfo> indexes = getIndexes(conn, safeSchema);
            List<ForeignKeyInfo> fks = getForeignKeys(conn, safeSchema);
            return new SchemaInfo(safeSchema, tables, indexes, fks);
        }
    }

    private List<TableInfo> getTables(Connection conn, String schemaName) throws SQLException {
        String sql = """
            SELECT
                t.table_name,
                c.reltuples::bigint AS estimated_row_count,
                pg_relation_size(c.oid) AS size_bytes,
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
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
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
                pg_relation_size(c.oid) AS size_bytes,
                ix.indisunique AS is_unique,
                ix.indisprimary AS is_primary
            FROM
                pg_indexes i
            JOIN pg_class c ON c.relname = i.indexname
            JOIN pg_namespace n ON n.oid = c.relnamespace AND n.nspname = i.schemaname
            JOIN pg_index ix ON ix.indexrelid = c.oid
            WHERE
                i.schemaname = ?
            """;
        List<IndexInfo> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
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
        // Optimized pg_catalog query using LATERAL unnest to accurately map composite keys
        String sql = """
            SELECT 
                c.conname AS constraint_name,
                c.conrelid::regclass::text AS source_table,
                a_src.attname AS source_column,
                c.confrelid::regclass::text AS foreign_table,
                a_tgt.attname AS foreign_column
            FROM pg_constraint c
            JOIN pg_namespace n ON n.oid = c.connamespace
            CROSS JOIN LATERAL unnest(c.conkey, c.confkey) AS u(src_attnum, tgt_attnum)
            JOIN pg_attribute a_src ON a_src.attrelid = c.conrelid AND a_src.attnum = u.src_attnum
            JOIN pg_attribute a_tgt ON a_tgt.attrelid = c.confrelid AND a_tgt.attnum = u.tgt_attnum
            WHERE c.contype = 'f' 
            AND n.nspname = ?
            """;
        List<ForeignKeyInfo> result = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setString(1, schemaName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // Extract just the table name without schema prefix if it exists
                    String srcTable = stripSchemaPrefix(rs.getString("source_table"), schemaName);
                    String tgtTable = stripSchemaPrefix(rs.getString("foreign_table"), schemaName);
                    
                    result.add(new ForeignKeyInfo(
                        srcTable,
                        rs.getString("constraint_name"),
                        rs.getString("source_column"),
                        tgtTable,
                        rs.getString("foreign_column")
                    ));
                }
            }
        }
        return result;
    }

    private String stripSchemaPrefix(String tableName, String currentSchema) {
        if (tableName != null && tableName.startsWith(currentSchema + ".")) {
            return tableName.substring(currentSchema.length() + 1);
        }
        return tableName;
    }

    public MissingIndexAnalysis findMissingIndexes(String schemaName) throws SQLException {
        return findMissingIndexes(schemaName, DEFAULT_MIN_TABLE_SIZE_BYTES);
    }

    /**
     * Finds scan-heavy tables at least {@code minTableSizeBytes} bytes in size.
     * Use zero to disable the table-size filter.
     */
    public MissingIndexAnalysis findMissingIndexes(String schemaName, long minTableSizeBytes) throws SQLException {
        if (minTableSizeBytes < 0) {
            throw new IllegalArgumentException("Minimum table size must be non-negative");
        }
        String safeSchema = validateSchemaName(schemaName);
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
                schemaname = ?
                AND seq_scan > 100 AND seq_tup_read > 10000
                AND (idx_scan IS NULL OR seq_scan > idx_scan)
                AND pg_relation_size(relid) >= ?
            ORDER BY
                seq_tup_read DESC
            LIMIT 20
            """;
        List<MissingIndexCandidate> candidates = new ArrayList<>();
        try (Connection conn = getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
            ps.setString(1, safeSchema);
            ps.setLong(2, minTableSizeBytes);
            try (ResultSet rs = ps.executeQuery()) {
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
        }
        return new MissingIndexAnalysis(candidates);
    }

    public SlowQueryAnalysis findSlowQueries() throws SQLException {
        return findSlowQueries(DEFAULT_SLOW_QUERY_LIMIT);
    }

    /**
     * Finds the slowest statements by mean execution time, returning at most {@code limit}
     * of them. The limit is clamped to between 1 and {@link #MAX_SLOW_QUERY_LIMIT}.
     */
    public SlowQueryAnalysis findSlowQueries(int limit) throws SQLException {
        int effectiveLimit = slowQueryLimit(limit);
        boolean hasExtension = false;
        try (Connection conn = getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                try (ResultSet rs = stmt.executeQuery("SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'")) {
                    if (rs.next()) {
                        hasExtension = true;
                    }
                }
            } catch (SQLException e) {
                // Ignore, might not have permission to read pg_extension
            }

            if (!hasExtension) {
                return new SlowQueryAnalysis(false, List.of());
            }

            // Universal JSON-based query bypassing parse-time column validation for backward compatibility (PG 12 / 13+)
            String sql = """
                SELECT
                    query,
                    calls,
                    COALESCE(
                        (row_to_json(pss)->>'mean_exec_time')::numeric, 
                        (row_to_json(pss)->>'mean_time')::numeric,
                        0.0
                    ) AS compatible_mean_time,
                    COALESCE(
                        (row_to_json(pss)->>'max_exec_time')::numeric, 
                        (row_to_json(pss)->>'max_time')::numeric,
                        0.0
                    ) AS compatible_max_time,
                    rows
                FROM
                    pg_stat_statements pss
                WHERE 
                    dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
                ORDER BY
                    compatible_mean_time DESC
                LIMIT ?
                """;
            List<SlowQueryInfo> slowQueries = new ArrayList<>();
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
                stmt.setInt(1, effectiveLimit);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String rawQuery = rs.getString("query");
                        String sanitizedQuery = sanitizeQuery(rawQuery);
                        slowQueries.add(new SlowQueryInfo(
                            sanitizedQuery,
                            rs.getLong("calls"),
                            rs.getDouble("compatible_mean_time"),
                            rs.getDouble("compatible_max_time"),
                            rs.getLong("rows")
                        ));
                    }
                }
            } catch (SQLException e) {
                throw new SQLException("Failed to read pg_stat_statements: " + e.getMessage(), e);
            }
            return new SlowQueryAnalysis(true, slowQueries);
        }
    }

    static int slowQueryLimit(int limit) {
        return Math.clamp(limit, 1, MAX_SLOW_QUERY_LIMIT);
    }

    static String sanitizeQuery(String query) {
        if (query == null) return null;
        // Redact literals in password statements, then every credential pattern the other
        // tool outputs are scrubbed for (bearer and basic tokens, API keys, JWTs, JDBC URLs).
        String sanitized = query.replaceAll("(?i)(password\\s*(?:=|with)\\s*)'[^']*'", "$1'***'");
        return SensitiveDataRedactor.redact(sanitized);
    }
}
