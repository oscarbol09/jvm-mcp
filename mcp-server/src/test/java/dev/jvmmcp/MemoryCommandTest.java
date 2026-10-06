package dev.jvmmcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryCommandTest {

    @Test
    @DisplayName("memory command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("memory", "--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Inspects JVM heap memory");
        assertThat(out.toString()).contains("--histogram");
        assertThat(out.toString()).contains("--top");
    }

    @Test
    @DisplayName("memory command with invalid or missing PID should return error exit code")
    void shouldFailOnMissingOrInvalidPid() {
        CommandLine cmd = new CommandLine(new JvmMcp());

        int noPidExit = cmd.execute("memory");
        int negativePidExit = cmd.execute("memory", "-1");
        int zeroPidExit = cmd.execute("memory", "0");

        assertThat(noPidExit).isNotZero();
        assertThat(negativePidExit).isEqualTo(1);
        assertThat(zeroPidExit).isEqualTo(1);
    }

    @Test
    @DisplayName("memory command with non-existent PID should return error exit code")
    void shouldFailGracefullyOnNonExistentPid() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("memory", "999999999");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("memory command on current process PID should report memory metrics successfully")
    void shouldInspectCurrentProcessMemory() {
        long currentPid = ProcessHandle.current().pid();
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("memory", String.valueOf(currentPid));

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("JVM MEMORY DIAGNOSTICS FOR PID " + currentPid);
        assertThat(output).contains("Heap Usage");
        assertThat(output).contains("Non-Heap Usage");
        assertThat(output).contains("Pressure Status");
        assertThat(output).contains("MEMORY POOLS");
    }

    @Test
    @DisplayName("memory command with --histogram and --top options on current process PID")
    void shouldInspectHistogramOnCurrentProcess() {
        long currentPid = ProcessHandle.current().pid();
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("memory", String.valueOf(currentPid), "--histogram", "--top", "5");

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("JVM MEMORY DIAGNOSTICS FOR PID " + currentPid);
    }

    @Test
    @DisplayName("memory aliases heap and mem should execute memory command")
    void shouldSupportAliases() {
        long currentPid = ProcessHandle.current().pid();
        CommandLine cmd = new CommandLine(new JvmMcp());

        int heapExit = cmd.execute("heap", String.valueOf(currentPid));
        int memExit = cmd.execute("mem", String.valueOf(currentPid));

        assertThat(heapExit).isZero();
        assertThat(memExit).isZero();
    }

    @Test
    @DisplayName("Direct invocation of MemoryCommand with invalid PID should return error 1")
    void shouldReturnErrorOnDirectCallWithInvalidPid() {
        MemoryCommand command = new MemoryCommand();
        command.pid = -5;

        Integer exitCode = command.call();
        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("Direct invocation of MemoryCommand on current PID should succeed")
    void shouldSucceedOnDirectCallWithCurrentPid() {
        MemoryCommand command = new MemoryCommand();
        command.pid = ProcessHandle.current().pid();
        command.histogram = false;
        command.topN = 10;

        Integer exitCode = command.call();
        assertThat(exitCode).isZero();
    }
}
