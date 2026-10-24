
package dev.jvmmcp;

import dev.jvmmcp.core.pg.PostgresModels.*;
import dev.jvmmcp.core.pg.PostgresSchemaReader;
import dev.jvmmcp.core.util.SimpleJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Function;

@Command(
    name = "pg",
    mixinStandardHelpOptions = true,
    description = "Inspect PostgreSQL schema, missing indexes, and slow queries."
)
public class PgCommand implements Callable<Integer> {

    @FunctionalInterface
    public interface SchemaReaderFactory {
        PostgresSchemaReader create(String url, String user, String password) throws Exception;
    }

    private final SchemaReaderFactory readerFactory;
    private final Function<String, String> envProvider;

    public PgCommand() {
        this(PostgresSchemaReader::new, System::getenv);
    }

    public PgCommand(Function<String, String> envProvider) {
        this(PostgresSchemaReader::new, envProvider);
    }

    public PgCommand(SchemaReaderFactory readerFactory, Function<String, String> envProvider) {
        this.readerFactory = readerFactory;
        this.envProvider = envProvider;
    }

    @Option(names = {"--url"}, required = true, description = "JDBC URL (e.g. jdbc:postgresql://localhost:5432/mydb)")
    private String url;

    @Option(names = {"--user", "-u"}, description = "Database username")
    private String user;

    @Option(names = {"--password", "-p"}, description = "Database password")
    private String password;

    @Option(names = {"--schema"}, defaultValue = "public", description = "Target schema (default: public)")
    private String schema;

    @Option(
        names = {"--action"},
        required = true,
        description = "Action to perform: schema, missing-indexes (or missing_indexes), slow-queries (or slow_queries)"
    )
    private String action;

    @Option(names = {"--min-table-size-mb"}, defaultValue = "1",
        description = "Minimum table size in MiB for missing-indexes (default: 1, 0 disables the size filter)")
    private long minTableSizeMb;

    @Option(names = {"--limit"}, defaultValue = "20",
        description = "Maximum number of slow queries to return for slow-queries (default: 20, max: 100)")
    private int limit;

    @Override
    public Integer call() {
        String effectiveUser;
        if (this.user != null) {
            effectiveUser = this.user;
        } else {
            String envUser = envProvider.apply("PGUSER");
            effectiveUser = envUser != null ? envUser : envProvider.apply("PG_USER");
        }

        String effectivePassword;
        if (this.password != null) {
            effectivePassword = this.password;
        } else {
            String envPassword = envProvider.apply("PGPASSWORD");
            effectivePassword = envPassword != null ? envPassword : envProvider.apply("PG_PASSWORD");
        }

        String normalizedAction = action == null
            ? ""
            : action.trim().toLowerCase(Locale.ROOT).replace('_', '-');

        try (PostgresSchemaReader reader =
                 readerFactory.create(url, effectiveUser, effectivePassword)) {
            switch (normalizedAction) {
                case "schema" -> {
                    SchemaInfo info = reader.inspectSchema(schema);
                    System.out.println(SimpleJson.toJson(info));
                }
                case "missing-indexes" -> {
                    if (minTableSizeMb < 0 || minTableSizeMb > Long.MAX_VALUE / (1024L * 1024L)) {
                        throw new IllegalArgumentException("--min-table-size-mb must be a non-negative integer that fits in bytes");
                    }
                    MissingIndexAnalysis analysis =
                        reader.findMissingIndexes(schema, minTableSizeMb * 1024L * 1024L);
                    System.out.println(SimpleJson.toJson(analysis));
                }
                case "slow-queries" -> {
                    SlowQueryAnalysis analysis = reader.findSlowQueries(limit);
                    System.out.println(SimpleJson.toJson(analysis));
                }
                default -> {
                    System.err.println(
                        "Unknown action: " + action
                            + ". Use schema, missing-indexes (or missing_indexes), "
                            + "or slow-queries (or slow_queries)."
                    );
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
