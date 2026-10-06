package dev.jvmmcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class ServeCommandTest {

    @Test
    @DisplayName("serve command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("serve", "--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Starts the JVM-MCP server");
        assertThat(out.toString()).contains("--transport");
        assertThat(out.toString()).contains("--spring");
        assertThat(out.toString()).contains("--attach");
    }

    @Test
    @DisplayName("serve command with unimplemented sse transport should return exit code 1")
    void shouldFailOnUnimplementedSseTransport() {
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("serve", "--transport", "sse");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("Spring layer not yet implemented");
    }

    @Test
    @DisplayName("serve command with --spring option should return exit code 1")
    void shouldFailOnSpringOption() {
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("serve", "--spring");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("Spring layer not yet implemented");
    }

    @Test
    @DisplayName("serve command with stdio transport should return exit code 0")
    void shouldAcceptStdioTransport() {
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("serve", "--transport", "stdio");

        assertThat(exitCode).isZero();
        assertThat(err.toString()).contains("Starting server via transport: stdio");
    }

    @Test
    @DisplayName("serve command with valid --attach PID should attach and return 0")
    void shouldAttachToValidPid() {
        long currentPid = ProcessHandle.current().pid();
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("serve", "--attach", String.valueOf(currentPid));

        assertThat(exitCode).isZero();
        assertThat(err.toString()).contains("Successfully attached to target PID " + currentPid);
        assertThat(err.toString()).contains("Starting server via transport: stdio");
    }

    @Test
    @DisplayName("serve command with non-existent --attach PID should return exit code 1")
    void shouldFailOnNonExistentAttachPid() {
        StringWriter err = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setErr(new PrintWriter(err));

        int exitCode = cmd.execute("serve", "--attach", "999999999");

        assertThat(exitCode).isEqualTo(1);
        assertThat(err.toString()).contains("Error attaching to target PID 999999999");
    }

    @Test
    @DisplayName("Direct invocation of ServeCommand should return exit code 0")
    void shouldCallDirectly() {
        ServeCommand command = new ServeCommand();
        command.transport = "stdio";

        Integer exitCode = command.call();
        assertThat(exitCode).isZero();
    }
}
