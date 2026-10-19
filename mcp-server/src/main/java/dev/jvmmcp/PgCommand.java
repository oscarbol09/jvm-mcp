package dev.jvmmcp;

import dev.jvmmcp.core.pg.PostgresModels.*;
import dev.jvmmcp.core.pg.PostgresSchemaReader;
import dev.jvmmcp.core.util.SimpleJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(
    name = "pg",
    mixinStandardHelpOptions = true,
    description = "Inspect PostgreSQL schema, missing indexes, and slow queries."
)
public class PgCommand implements Callable<Integer> {

    @Option(names = {"--url"}, required = true, description = "JDBC URL (e.g. jdbc:postgresql://localhost:5432/mydb)")
    private String url;

    @Option(names = {"--user", "-u"}, description = "Database username")
    private String user;

    @Option(names = {"--password", "-p"}, description = "Database password")
    private String password;

    @Option(names = {"--schema"}, defaultValue = "public", description = "Target schema (default: public)")
    private String schema;

    @Option(names = {"--action"}, required = true, description = "Action to perform: schema, missing-indexes, slow-queries")
    private String action;

    @Option(names = {"--min-table-size-mb"}, defaultValue = "1",
        description = "Minimum table size in MiB for missing-indexes (default: 1, 0 disables the size filter)")
    private long minTableSizeMb;

    @Override
    public Integer call() {
        try (PostgresSchemaReader reader = new PostgresSchemaReader(url, user, password)) {
            switch (action) {
                case "schema" -> {
                    SchemaInfo info = reader.inspectSchema(schema);
                    System.out.println(SimpleJson.toJson(info));
                }
                case "missing-indexes" -> {
                    if (minTableSizeMb < 0 || minTableSizeMb > Long.MAX_VALUE / (1024L * 1024L)) {
                        throw new IllegalArgumentException("--min-table-size-mb must be a non-negative integer that fits in bytes");
                    }
                    MissingIndexAnalysis analysis = reader.findMissingIndexes(schema, minTableSizeMb * 1024L * 1024L);
                    System.out.println(SimpleJson.toJson(analysis));
                }
                case "slow-queries" -> {
                    SlowQueryAnalysis analysis = reader.findSlowQueries();
                    System.out.println(SimpleJson.toJson(analysis));
                }
                default -> {
                    System.err.println("Unknown action: " + action + ". Use schema, missing-indexes, or slow-queries.");
                    return 1;
                }
            }

            return 0;
        } catch (Exception e) {
            System.err.println("Error inspecting PostgreSQL database: " + e.getMessage());
            return 1;
        }
    }
}
