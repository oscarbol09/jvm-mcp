package dev.jvmmcp;

import dev.jvmmcp.core.pg.PostgresModels;
import dev.jvmmcp.core.pg.PostgresSchemaReader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PgCommandTest {

    @Test
    void missingIndexesDefaultsToOneMiB() throws Exception {
        try (var readers = mockConstruction(PostgresSchemaReader.class, (reader, context) ->
            when(reader.findMissingIndexes(anyString(), anyLong()))
                .thenReturn(new PostgresModels.MissingIndexAnalysis(List.of())))) {
            int exitCode = new CommandLine(new JvmMcp()).execute("pg", "--url",
                "jdbc:postgresql://localhost:5432/db", "--action", "missing-indexes");

            assertThat(exitCode).isZero();
            assertThat(readers.constructed()).hasSize(1);
            verify(readers.constructed().getFirst()).findMissingIndexes("public", 1024L * 1024);
        }
    }


    @Test
    void acceptsSnakeCaseMissingIndexesAction() throws Exception {
        try (var readers = mockConstruction(PostgresSchemaReader.class, (reader, context) ->
            when(reader.findMissingIndexes(anyString(), anyLong()))
                .thenReturn(new PostgresModels.MissingIndexAnalysis(List.of())))) {

            int exitCode = new CommandLine(new JvmMcp()).execute(
                "pg", "--url", "jdbc:postgresql://localhost:5432/db",
                "--action", "missing_indexes"
            );

            assertThat(exitCode).isZero();
            verify(readers.constructed().getFirst())
                .findMissingIndexes("public", 1024L * 1024);
        }
    }

    @Test
    void acceptsUppercaseSlowQueriesAction() throws Exception {
        try (var readers = mockConstruction(PostgresSchemaReader.class)) {
            int exitCode = new CommandLine(new JvmMcp()).execute(
                "pg", "--url", "jdbc:postgresql://localhost:5432/db",
                "--action", "SLOW-QUERIES"
            );

            assertThat(exitCode).isZero();
            verify(readers.constructed().getFirst()).findSlowQueries(20);
        }
    }

    @Test
    void passesLimitToSlowQueries() throws Exception {
        try (var readers = mockConstruction(PostgresSchemaReader.class)) {
            int exitCode = new CommandLine(new JvmMcp()).execute(
                "pg", "--url", "jdbc:postgresql://localhost:5432/db",
                "--action", "slow-queries", "--limit", "5"
            );

            assertThat(exitCode).isZero();
            verify(readers.constructed().getFirst()).findSlowQueries(5);
        }
    }

    @Test
    void acceptsCapitalizedSchemaAction() throws Exception {
        try (var readers = mockConstruction(PostgresSchemaReader.class)) {
            int exitCode = new CommandLine(new JvmMcp()).execute(
                "pg", "--url", "jdbc:postgresql://localhost:5432/db",
                "--action", "Schema"
            );

            assertThat(exitCode).isZero();
            verify(readers.constructed().getFirst()).inspectSchema("public");
        }
    }


    @ParameterizedTest
    @ValueSource(longs = {0, 5, 4096})
    void missingIndexesConvertsMiBWithoutIntegerOverflow(long megabytes) throws Exception {
        try (var readers = mockConstruction(PostgresSchemaReader.class, (reader, context) ->
            when(reader.findMissingIndexes(anyString(), anyLong()))
                .thenReturn(new PostgresModels.MissingIndexAnalysis(List.of())))) {
            int exitCode = new CommandLine(new JvmMcp()).execute("pg", "--url",
                "jdbc:postgresql://localhost:5432/db", "--action", "missing-indexes",
                "--schema", "custom", "--min-table-size-mb", Long.toString(megabytes));

            assertThat(exitCode).isZero();
            assertThat(readers.constructed()).hasSize(1);
            verify(readers.constructed().getFirst()).findMissingIndexes("custom", megabytes * 1024 * 1024);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "8796093022208", "9223372036854775808", "1.5"})
    void rejectsInvalidMissingIndexSizeWithoutQuerying(String megabytes) throws Exception {
        try (var readers = mockConstruction(PostgresSchemaReader.class)) {
            int exitCode = new CommandLine(new JvmMcp()).execute("pg", "--url",
                "jdbc:postgresql://localhost:5432/db", "--action", "missing-indexes",
                "--min-table-size-mb", megabytes);

            assertThat(exitCode).isNotZero();
            for (PostgresSchemaReader reader : readers.constructed()) {
                verify(reader, never()).findMissingIndexes(anyString(), anyLong());
            }
        }
    }

    @Test
    @DisplayName("pg command with --help should display help options and return 0")
    void shouldDisplayHelp() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("pg", "--help");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("pg command with missing required options should return usage error code")
    void shouldFailOnMissingRequiredOptions() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("pg");

        assertThat(exitCode).isEqualTo(2); // Picocli parameter exception code
    }

    @Test
    @DisplayName("pg command with invalid/unreachable URL should return error code 1")
    void shouldFailOnInvalidUrl() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("pg", "--url", "jdbc:postgresql://localhost:9999/nonexistent", "--action", "schema");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Error inspecting PostgreSQL database");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("pg command with dangerous connection parameter should be rejected with exit code 1")
    void shouldRejectDangerousParameters() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("pg", "--url", "jdbc:postgresql://localhost:5432/db?socketFactory=evil", "--action", "schema");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Dangerous JDBC connection parameter");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("pg command with unknown action should return exit code 1")
    void shouldFailOnUnknownAction() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("pg", "--url", "jdbc:postgresql://localhost:5432/db", "--action", "unknown-action");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Unknown action");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("pg command falls back to PGUSER and PGPASSWORD when CLI options are omitted")
    void shouldFallbackToEnvironmentCredentialsWhenNotSupplied() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of("PGUSER", "env_user", "PGPASSWORD", "env_secret");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("env_user");
        assertThat(capturedPassword.get()).isEqualTo("env_secret");
        verify(mockReader).inspectSchema("public");
    }

    @Test
    @DisplayName("pg command explicit CLI options override PGUSER and PGPASSWORD environment variables")
    void shouldPreferCliCredentialsOverEnvironmentVariables() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of("PGUSER", "env_user", "PGPASSWORD", "env_secret");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "--user", "cli_user",
            "--password", "cli_secret",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("cli_user");
        assertThat(capturedPassword.get()).isEqualTo("cli_secret");
    }

    @Test
    @DisplayName("pg command allows partial CLI override with environment fallback for password")
    void shouldAllowPartialCliOverrideWithEnvFallback() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of("PGUSER", "env_user", "PGPASSWORD", "env_secret");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "-u", "cli_user",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("cli_user");
        assertThat(capturedPassword.get()).isEqualTo("env_secret");
    }

    @Test
    @DisplayName("pg command allows partial CLI override with environment fallback for user")
    void shouldAllowPartialCliOverrideWithEnvUserFallback() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of("PGUSER", "env_user", "PGPASSWORD", "env_secret");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "--password", "cli_secret",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("env_user");
        assertThat(capturedPassword.get()).isEqualTo("cli_secret");
    }

    @Test
    @DisplayName("pg command allows partial CLI override with short -p option and environment user fallback")
    void shouldAllowPartialCliOverrideWithShortPasswordOption() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of("PGUSER", "env_user", "PGPASSWORD", "env_secret");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "-p", "cli_secret",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("env_user");
        assertThat(capturedPassword.get()).isEqualTo("cli_secret");
    }

    @Test
    @DisplayName("pg command falls back to PG_USER and PG_PASSWORD when standard PGUSER/PGPASSWORD are absent")
    void shouldFallbackToUnderscoreEnvVariablesWhenStandardAbsent() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of("PG_USER", "underscore_user", "PG_PASSWORD", "underscore_pass");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("underscore_user");
        assertThat(capturedPassword.get()).isEqualTo("underscore_pass");
        verify(mockReader).inspectSchema("public");
    }

    @Test
    @DisplayName("pg command prefers standard PGUSER and PGPASSWORD over PG_USER and PG_PASSWORD aliases")
    void shouldPreferStandardEnvVariablesOverUnderscoreAliases() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of(
            "PGUSER", "std_user",
            "PG_USER", "alt_user",
            "PGPASSWORD", "std_pass",
            "PG_PASSWORD", "alt_pass"
        );
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("std_user");
        assertThat(capturedPassword.get()).isEqualTo("std_pass");
    }

    @Test
    @DisplayName("pg command CLI options override all environment aliases")
    void shouldPreferCliValuesOverAllEnvironmentAliases() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of(
            "PGUSER", "std_user",
            "PG_USER", "alt_user",
            "PGPASSWORD", "std_pass",
            "PG_PASSWORD", "alt_pass"
        );
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "--user", "cli_user",
            "--password", "cli_pass",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("cli_user");
        assertThat(capturedPassword.get()).isEqualTo("cli_pass");
    }

    @Test
    @DisplayName("pg command allows partial CLI user override with PG_PASSWORD fallback")
    void shouldAllowPartialCliOverrideWithUnderscoreEnvFallback() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of("PG_USER", "alt_user", "PG_PASSWORD", "alt_pass");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "-u", "cli_user",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("cli_user");
        assertThat(capturedPassword.get()).isEqualTo("alt_pass");
    }

    @Test
    @DisplayName("pg command allows partial CLI password override with PG_USER fallback")
    void shouldAllowPartialCliPasswordOverrideWithUnderscoreEnvUserFallback() throws Exception {
        PostgresSchemaReader mockReader = mock(PostgresSchemaReader.class);
        when(mockReader.inspectSchema("public")).thenReturn(new PostgresModels.SchemaInfo("public", List.of(), List.of(), List.of()));

        Map<String, String> env = Map.of("PG_USER", "alt_user", "PG_PASSWORD", "alt_pass");
        AtomicReference<String> capturedUser = new AtomicReference<>();
        AtomicReference<String> capturedPassword = new AtomicReference<>();

        PgCommand command = new PgCommand((url, user, pass) -> {
            capturedUser.set(user);
            capturedPassword.set(pass);
            return mockReader;
        }, env::get);

        int exitCode = new CommandLine(command).execute(
            "--url", "jdbc:postgresql://localhost:5432/db",
            "-p", "cli_pass",
            "--action", "schema"
        );

        assertThat(exitCode).isZero();
        assertThat(capturedUser.get()).isEqualTo("alt_user");
        assertThat(capturedPassword.get()).isEqualTo("cli_pass");
    }
}
