package dev.jvmmcp.server.tool;

import dev.jvmmcp.core.pg.PostgresSchemaReader;
import dev.jvmmcp.core.util.SimpleJson;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Handles PostgreSQL inspection tools: inspect_pg_schema, find_missing_indexes, find_slow_queries.
 */
public class PostgresToolHandler implements ToolHandler {

    @FunctionalInterface
    public interface SchemaReaderFactory {
        PostgresSchemaReader create(String url, String user, String password) throws Exception;
    }

    private final SchemaReaderFactory readerFactory;
    private final Function<String, String> envProvider;

    public PostgresToolHandler() {
        this(PostgresSchemaReader::new, System::getenv);
    }

    public PostgresToolHandler(SchemaReaderFactory readerFactory) {
        this(readerFactory, System::getenv);
    }

    public PostgresToolHandler(SchemaReaderFactory readerFactory, Function<String, String> envProvider) {
        this.readerFactory = readerFactory;
        this.envProvider = envProvider;
    }

    @Override
    public List<String> toolNames() {
        return List.of("inspect_pg_schema", "find_missing_indexes", "find_slow_queries");
    }

    @Override
    public List<Map<String, Object>> toolDefinitions() {
        return List.of(
            Map.of(
                "name", "inspect_pg_schema",
                "description", "Inspects PostgreSQL schema, tables, indexes, and foreign keys",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "JDBC URL (e.g. jdbc:postgresql://localhost:5432/db)"),
                        "schema", Map.of("type", "string", "description", "Target schema (default: public)")
                    ),
                    "required", List.of("url")
                )
            ),
            Map.of(
                "name", "find_missing_indexes",
                "description", "Finds missing indexes by analyzing seq_scan and idx_scan metrics",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "JDBC URL (e.g. jdbc:postgresql://localhost:5432/db)"),
                        "schema", Map.of("type", "string", "description", "Target schema (default: public)"),
                        "minTableSizeMb", Map.of("type", "integer", "minimum", 0, "default", 1,
                            "description", "Minimum table size in MiB (default: 1, 0 disables the size filter)")
                    ),
                    "required", List.of("url")
                )
            ),
            Map.of(
                "name", "find_slow_queries",
                "description", "Finds slow queries using pg_stat_statements",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "JDBC URL (e.g. jdbc:postgresql://localhost:5432/db)")
                    ),
                    "required", List.of("url")
                )
            )
        );
    }

    @Override
    public Map<String, Object> execute(String toolName, Map<String, Object> args) throws Exception {
        String url = ToolArgs.getStringArg(args, "url", true);
        PostgresSchemaReader.validateJdbcUrl(url);
        String user = envProvider.apply("PG_USER");
        String password = envProvider.apply("PG_PASSWORD");

        List<Map<String, Object>> content = new ArrayList<>();
        try (PostgresSchemaReader reader = readerFactory.create(url, user, password)) {
            if ("inspect_pg_schema".equals(toolName)) {
                String rawSchema = ToolArgs.getStringArg(args, "schema", false);
                String schema = PostgresSchemaReader.validateSchemaName(rawSchema);
                content.add(Map.of("type", "text", "text", SimpleJson.toJson(reader.inspectSchema(schema))));
            } else if ("find_missing_indexes".equals(toolName)) {
                String rawSchema = ToolArgs.getStringArg(args, "schema", false);
                String schema = PostgresSchemaReader.validateSchemaName(rawSchema);
                long minTableSizeBytes = PostgresSchemaReader.DEFAULT_MIN_TABLE_SIZE_BYTES;
                if (args.containsKey("minTableSizeMb")) {
                    long minTableSizeMb = ToolArgs.getLongArg(args, "minTableSizeMb", true);
                    boolean wholeNumber;
                    try {
                        wholeNumber = new BigDecimal(args.get("minTableSizeMb").toString())
                            .compareTo(BigDecimal.valueOf(minTableSizeMb)) == 0;
                    } catch (NumberFormatException e) {
                        wholeNumber = false;
                    }
                    if (minTableSizeMb < 0 || minTableSizeMb > Long.MAX_VALUE / (1024L * 1024L)
                        || !wholeNumber) {
                        throw new IllegalArgumentException("Argument 'minTableSizeMb' must be a non-negative integer that fits in bytes");
                    }
                    minTableSizeBytes = minTableSizeMb * 1024L * 1024L;
                }
                content.add(Map.of("type", "text", "text", SimpleJson.toJson(reader.findMissingIndexes(schema, minTableSizeBytes))));
            } else if ("find_slow_queries".equals(toolName)) {
                content.add(Map.of("type", "text", "text", SimpleJson.toJson(reader.findSlowQueries())));
            } else {
                throw new IllegalArgumentException("Unknown postgres tool: " + toolName);
            }
        }

        return Map.of("content", content);
    }
}
