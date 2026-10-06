package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServeCommandTest {

    @Mock
    private JvmAttachService mockAttachService;

    @Test
    @DisplayName("serve command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("serve", "--help");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("serve command with unimplemented sse transport should return exit code 1")
    void shouldFailOnUnimplementedSseTransport() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("serve", "--transport", "sse");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Spring layer not yet implemented");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("serve command with --spring option should return exit code 1")
    void shouldFailOnSpringOption() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("serve", "--spring");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Spring layer not yet implemented");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("serve command with stdio transport should return exit code 0")
    void shouldAcceptStdioTransport() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        java.io.InputStream originalIn = System.in;
        try {
            System.setErr(new PrintStream(err));
            System.setIn(new java.io.ByteArrayInputStream(new byte[0])); // Provide immediate EOF
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("serve", "--transport", "stdio");

            assertThat(exitCode).isZero();
            assertThat(err.toString()).contains("Starting server via transport: stdio");
        } finally {
            System.setErr(originalErr);
            System.setIn(originalIn);
        }
    }

    @Test
    @DisplayName("serve command with valid --attach PID should attach and return 0")
    void shouldAttachToValidPid() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ServeCommand command = new ServeCommand(mockAttachService);
        command.targetPid = (int) targetPid;
        command.transport = "stdio";

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        java.io.InputStream originalIn = System.in;
        try {
            System.setErr(new PrintStream(err));
            System.setIn(new java.io.ByteArrayInputStream(new byte[0])); // Provide immediate EOF
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(err.toString()).contains("Successfully attached to target PID " + targetPid);
            assertThat(err.toString()).contains("Starting server via transport: stdio");
        } finally {
            System.setErr(originalErr);
            System.setIn(originalIn);
        }
    }

    @Test
    @DisplayName("serve command with non-existent --attach PID should return exit code 1")
    void shouldFailOnNonExistentAttachPid() {
        long targetPid = 999999999L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.processNotFound(String.valueOf(targetPid)));

        ServeCommand command = new ServeCommand(mockAttachService);
        command.targetPid = (int) targetPid;
        command.transport = "stdio";

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Error attaching to target PID " + targetPid);
        } finally {
            System.setErr(originalErr);
        }
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
