package dev.jvmmcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class ThreadsCommandTest {

    @Test
    @DisplayName("threads command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("threads", "--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Inspects JVM thread states");
        assertThat(out.toString()).contains("--deadlocks");
        assertThat(out.toString()).contains("--dump");
        assertThat(out.toString()).contains("--blocked");
    }

    @Test
    @DisplayName("threads command with invalid or missing PID should return error exit code")
    void shouldFailOnMissingOrInvalidPid() {
        CommandLine cmd = new CommandLine(new JvmMcp());

        int noPidExit = cmd.execute("threads");
        int negativePidExit = cmd.execute("threads", "-1");
        int zeroPidExit = cmd.execute("threads", "0");

        assertThat(noPidExit).isNotZero();
        assertThat(negativePidExit).isEqualTo(1);
        assertThat(zeroPidExit).isEqualTo(1);
    }

    @Test
    @DisplayName("threads command with non-existent PID should return error exit code")
    void shouldFailGracefullyOnNonExistentPid() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("threads", "999999999");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("threads command on current PID should display thread summary and deadlocks")
    void shouldInspectCurrentProcessThreads() {
        long currentPid = ProcessHandle.current().pid();
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("threads", String.valueOf(currentPid));

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("JVM THREAD DIAGNOSTICS FOR PID " + currentPid);
        assertThat(output).contains("Total Threads");
        assertThat(output).contains("RUNNABLE");
        assertThat(output).contains("DEADLOCK STATUS");
    }

    @Test
    @DisplayName("threads command with --deadlocks flag should report deadlock status exclusively")
    void shouldInspectDeadlocksExclusively() {
        long currentPid = ProcessHandle.current().pid();
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("threads", String.valueOf(currentPid), "--deadlocks");

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("DEADLOCK STATUS");
    }

    @Test
    @DisplayName("threads command with --dump flag should output thread dump and stack traces")
    void shouldOutputThreadDump() {
        long currentPid = ProcessHandle.current().pid();
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("threads", String.valueOf(currentPid), "--dump");

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("THREAD DUMP FOR PID " + currentPid);
    }

    @Test
    @DisplayName("threads command with --blocked flag should list blocked threads")
    void shouldListBlockedThreads() {
        long currentPid = ProcessHandle.current().pid();
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("threads", String.valueOf(currentPid), "--blocked");

        assertThat(exitCode).isZero();
        String output = out.toString();
        assertThat(output).contains("BLOCKED THREADS FOR PID " + currentPid);
    }

    @Test
    @DisplayName("threads aliases thread and th should execute command")
    void shouldSupportAliases() {
        long currentPid = ProcessHandle.current().pid();
        CommandLine cmd = new CommandLine(new JvmMcp());

        int threadExit = cmd.execute("thread", String.valueOf(currentPid));
        int thExit = cmd.execute("th", String.valueOf(currentPid));

        assertThat(threadExit).isZero();
        assertThat(thExit).isZero();
    }

    @Test
    @DisplayName("Direct invocation of ThreadsCommand with invalid PID should return error 1")
    void shouldReturnErrorOnDirectCallWithInvalidPid() {
        ThreadsCommand command = new ThreadsCommand();
        command.pid = -1;

        Integer exitCode = command.call();
        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("Direct invocation of ThreadsCommand on current PID should succeed")
    void shouldSucceedOnDirectCallWithCurrentPid() {
        ThreadsCommand command = new ThreadsCommand();
        command.pid = ProcessHandle.current().pid();

        Integer exitCode = command.call();
        assertThat(exitCode).isZero();
    }
}
