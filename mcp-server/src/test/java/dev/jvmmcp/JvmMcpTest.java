package dev.jvmmcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class JvmMcpTest {

    @Test
    @DisplayName("Root command should display help usage and return exit code 0")
    void shouldDisplayHelpUsage() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Live JVM inspection via Model Context Protocol");
        assertThat(out.toString()).contains("Commands:");
        assertThat(out.toString()).contains("serve");
        assertThat(out.toString()).contains("list");
        assertThat(out.toString()).contains("memory");
        assertThat(out.toString()).contains("threads");
        assertThat(out.toString()).contains("beans");
    }

    @Test
    @DisplayName("Root command should display version and return exit code 0")
    void shouldDisplayVersion() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("--version");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains(JvmMcp.VERSION);
    }

    @Test
    @DisplayName("Calling root command directly displays usage and returns 0")
    void shouldCallRootCommandDirectly() {
        JvmMcp root = new JvmMcp();
        Integer exitCode = root.call();
        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("Invoking with invalid subcommand should return error exit code 2")
    void shouldReturnErrorOnInvalidSubcommand() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("non-existent-subcommand");

        assertThat(exitCode).isNotZero();
    }
}
