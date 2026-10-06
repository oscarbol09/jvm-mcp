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
class ThreadsCommandTest {

    @Mock
    private JvmAttachService mockAttachService;

    @Test
    @DisplayName("threads command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("threads", "--help");

        assertThat(exitCode).isZero();
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
        when(mockAttachService.attach("999999999")).thenReturn(AttachResult.processNotFound("999999999"));

        ThreadsCommand command = new ThreadsCommand(mockAttachService);
        command.pid = 999999999L;

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("[jvm-mcp] Target JVM process 999999999 not found.");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("threads command on attached PID should display summary and deadlock status")
    void shouldDisplayThreadSummaryAndDeadlocks() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ThreadsCommand command = new ThreadsCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("JVM THREAD DIAGNOSTICS FOR PID " + targetPid);
            assertThat(output).contains("Total Threads");
            assertThat(output).contains("RUNNABLE");
            assertThat(output).contains("DEADLOCK STATUS");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("threads command with deadlocksOnly flag should report deadlock status exclusively")
    void shouldReportDeadlocksExclusively() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ThreadsCommand command = new ThreadsCommand(mockAttachService);
        command.pid = targetPid;
        command.deadlocksOnly = true;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("DEADLOCK STATUS");
            assertThat(output).doesNotContain("JVM THREAD DIAGNOSTICS FOR PID");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("threads command with dump flag should display thread dump")
    void shouldDisplayThreadDump() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ThreadsCommand command = new ThreadsCommand(mockAttachService);
        command.pid = targetPid;
        command.dump = true;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("THREAD DUMP FOR PID " + targetPid);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("threads command with blockedOnly flag should list blocked threads")
    void shouldListBlockedThreads() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ThreadsCommand command = new ThreadsCommand(mockAttachService);
        command.pid = targetPid;
        command.blockedOnly = true;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("BLOCKED THREADS FOR PID " + targetPid);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("threads command should catch unexpected JMX query failures")
    void shouldCatchJmxQueryFailure() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenThrow(new RuntimeException("JMX query failure"));

        ThreadsCommand command = new ThreadsCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("JMX query failure");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("Default constructor should initialize properly")
    void shouldInitializeWithDefaultConstructor() {
        ThreadsCommand command = new ThreadsCommand();
        assertThat(command.attachService).isNotNull();
    }
}
