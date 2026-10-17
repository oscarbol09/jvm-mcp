package dev.jvmmcp;

import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.model.Framework;
import dev.jvmmcp.core.model.JvmProcess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DoctorCommandTest {

    @Test
    @DisplayName("doctor command with --help should display options and return 0")
    void shouldDisplayHelp() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("doctor", "--help");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("doctor command should run diagnostics and report system readiness")
    void shouldRunDoctorDiagnostics() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("doctor");

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("JVM-MCP Doctor");
            assertThat(output).contains("--------------");
            assertThat(output).contains("OS Name:       " + System.getProperty("os.name"));
            assertThat(output).contains("OS Arch:       " + System.getProperty("os.arch"));
            assertThat(output).contains("OS Version:    " + System.getProperty("os.version"));
            assertThat(output).contains("Java Version:  " + System.getProperty("java.version"));
            assertThat(output).contains("Java Vendor:   " + System.getProperty("java.vendor"));
            assertThat(output).contains("Java Home:     " + System.getProperty("java.home"));
            assertThat(output).contains("User Name:     " + System.getProperty("user.name"));
            assertThat(output).contains("Checking Attach API Capabilities...");
            assertThat(output).contains("[OK] Attach API is functioning.");
            assertThat(output).contains("Your system is ready to use JVM-MCP.");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("doctor reports when no JVM processes are found")
    void shouldReportNoJvmsFound() {
        JvmAttachService mockAttach = mock(JvmAttachService.class);
        when(mockAttach.listJvms()).thenReturn(List.of());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            DoctorCommand doctor = new DoctorCommand(mockAttach);
            int exitCode = doctor.call();

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("Note: No Java processes found. Ensure your target applications are running as the same OS user.");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("doctor reports when JVM processes are found")
    void shouldReportJvmsFound() {
        JvmAttachService mockAttach = mock(JvmAttachService.class);
        when(mockAttach.listJvms()).thenReturn(List.of(
            new JvmProcess(101L, "App", "App", "21", Framework.PLAIN_JAVA, true)
        ));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            DoctorCommand doctor = new DoctorCommand(mockAttach);
            int exitCode = doctor.call();

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("[OK] Found 1 local Java processes accessible to user '" + System.getProperty("user.name") + "'.");
            assertThat(out.toString()).doesNotContain("Note: No Java processes found.");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("doctor handles attach failure and returns 1")
    void shouldHandleAttachFailure() {
        JvmAttachService mockAttach = mock(JvmAttachService.class);
        when(mockAttach.listJvms()).thenThrow(new RuntimeException("Access denied"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            DoctorCommand doctor = new DoctorCommand(mockAttach);
            int exitCode = doctor.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(out.toString()).contains("[ERROR] Attach API failed to initialize: Access denied");
            assertThat(out.toString()).contains("Resolution: Ensure you are running this CLI as the same user as the target JVM.");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("checkJvmVendor identifies HotSpot and OpenJ9 vendors correctly")
    void shouldCheckJvmVendor() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out);

        DoctorCommand.checkJvmVendor("Eclipse Adoptium", "OpenJDK 64-Bit Server VM", ps);
        assertThat(out.toString()).contains("[OK] HotSpot-compatible JVM detected (Eclipse Adoptium)");

        out.reset();
        DoctorCommand.checkJvmVendor("IBM Corporation", "Eclipse OpenJ9 VM", ps);
        assertThat(out.toString()).contains("Detected OpenJ9 / IBM JVM");

        out.reset();
        DoctorCommand.checkJvmVendor(null, null, ps);
        assertThat(out.toString()).contains("HotSpot-compatible JVM detected (Standard)");
    }

    @Test
    @DisplayName("checkContainerEnvironment identifies container indicator files")
    void shouldCheckContainerEnvironment(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out);

        java.nio.file.Path dockerenv = tempDir.resolve(".dockerenv");
        java.nio.file.Path containerenv = tempDir.resolve(".containerenv");

        // When neither exists
        DoctorCommand.checkContainerEnvironment(dockerenv, containerenv, ps);
        assertThat(out.toString()).isEmpty();

        // When .dockerenv exists
        java.nio.file.Files.createFile(dockerenv);
        DoctorCommand.checkContainerEnvironment(dockerenv, containerenv, ps);
        assertThat(out.toString()).contains("Container environment detected");
    }

    @Test
    @DisplayName("checkLinuxPtraceScope reports ptrace levels accurately")
    void shouldCheckLinuxPtraceScope(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream ps = new PrintStream(out);

        java.nio.file.Path ptrace = tempDir.resolve("ptrace_scope");

        // Non existent
        DoctorCommand.checkLinuxPtraceScope(ptrace, ps);
        assertThat(out.toString()).isEmpty();

        // Scope 0
        java.nio.file.Files.writeString(ptrace, "0\n");
        DoctorCommand.checkLinuxPtraceScope(ptrace, ps);
        assertThat(out.toString()).contains("Linux yama/ptrace_scope is 0 (classic ptrace permissions; unrestricted same-user attach)");

        // Scope 1
        out.reset();
        java.nio.file.Files.writeString(ptrace, "1\n");
        DoctorCommand.checkLinuxPtraceScope(ptrace, ps);
        assertThat(out.toString()).contains("Linux yama/ptrace_scope is set to 1");
    }
}
