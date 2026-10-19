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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
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
}
