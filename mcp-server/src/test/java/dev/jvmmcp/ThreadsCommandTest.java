package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;
import dev.jvmmcp.core.jmx.ThreadMXBeanClient;
import dev.jvmmcp.core.model.DeadlockReport;
import dev.jvmmcp.core.model.ThreadDump;

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
            assertThat(err.toString()).contains("Process with PID 999999999 was not found");
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
    void shouldReportDeadlocksExclusively() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ThreadMXBeanClient mockClient = org.mockito.Mockito.mock(ThreadMXBeanClient.class);
        DeadlockReport report = new DeadlockReport("DETECTED", 1, java.util.List.of(
            new dev.jvmmcp.core.model.DeadlockedThreadDetail(1L, "Thread-1", "BLOCKED", "Lock-A", 2L, "Thread-2", "Class.method(Class.java:10)")
        ), "Fix it");
        when(mockClient.detectDeadlocks()).thenReturn(report);

        ThreadsCommand command = org.mockito.Mockito.spy(new ThreadsCommand(mockAttachService));
        command.pid = targetPid;
        command.deadlocksOnly = true;
        org.mockito.Mockito.doReturn(mockClient).when(command).createThreadMXBeanClient(org.mockito.ArgumentMatchers.any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("DEADLOCK STATUS: DETECTED");
            assertThat(output).contains("Thread-1");
            assertThat(output).contains("Lock-A");
            assertThat(output).doesNotContain("JVM THREAD DIAGNOSTICS FOR PID");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("threads command with dump flag should display thread dump")
    void shouldDisplayThreadDump() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ThreadMXBeanClient mockClient = org.mockito.Mockito.mock(ThreadMXBeanClient.class);
        ThreadDump dump = new ThreadDump(targetPid, java.time.Instant.now(), 2, java.util.List.of(
            new dev.jvmmcp.core.model.ThreadDetail(2L, "Worker", "WAITING", null, null, null, 0L, 0L, 0L, 0L, false, false, java.util.List.of(
                new dev.jvmmcp.core.model.ThreadStackFrame("Class", "method", "Class.java", 10, false)
            )),
            new dev.jvmmcp.core.model.ThreadDetail(3L, "Worker2", "WAITING", "Lock-B", 3L, "Owner", 0L, 0L, 0L, 0L, false, false, java.util.List.of())
        ));
        when(mockClient.getThreadDump(targetPid)).thenReturn(dump);

        ThreadsCommand command = org.mockito.Mockito.spy(new ThreadsCommand(mockAttachService));
        command.pid = targetPid;
        command.dump = true;
        org.mockito.Mockito.doReturn(mockClient).when(command).createThreadMXBeanClient(org.mockito.ArgumentMatchers.any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("THREAD DUMP FOR PID " + targetPid);
            assertThat(output).contains("waiting on Lock-B held by Owner (ID 3)");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("threads command on attached remote PID should attempt connect")
    void shouldAttemptRemoteConnectOnRemoteVm() throws Exception {
        long targetPid = 100L;
        com.sun.tools.attach.VirtualMachine mockVm = org.mockito.Mockito.mock(com.sun.tools.attach.VirtualMachine.class);
        java.util.Properties props = new java.util.Properties();
        props.setProperty("com.sun.management.jmxremote.localConnectorAddress", "service:jmx:rmi:///jndi/rmi://localhost:9000/jmxrmi");
        when(mockVm.getAgentProperties()).thenReturn(props);
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), mockVm));

        ThreadsCommand command = new ThreadsCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();
            assertThat(exitCode).isEqualTo(1);
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("threads command with blockedOnly flag should list blocked threads")
    void shouldListBlockedThreads() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ThreadMXBeanClient mockClient = org.mockito.Mockito.mock(ThreadMXBeanClient.class);
        java.util.List<dev.jvmmcp.core.model.BlockedThreadDetail> blocked = java.util.List.of(
            new dev.jvmmcp.core.model.BlockedThreadDetail(4L, "BlockedThread", 0L, 0L, "Lock-C", 5L, "OtherOwner", java.util.List.of(
                new dev.jvmmcp.core.model.ThreadStackFrame("Class", "method", "Class.java", 20, false)
            )),
            new dev.jvmmcp.core.model.BlockedThreadDetail(5L, "BlockedThread2", 0L, 0L, "Lock-D", null, null, java.util.List.of())
        );
        when(mockClient.findBlockedThreads(0)).thenReturn(blocked);

        ThreadsCommand command = org.mockito.Mockito.spy(new ThreadsCommand(mockAttachService));
        command.pid = targetPid;
        command.blockedOnly = true;
        org.mockito.Mockito.doReturn(mockClient).when(command).createThreadMXBeanClient(org.mockito.ArgumentMatchers.any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("BLOCKED THREADS FOR PID " + targetPid);
            assertThat(output).contains("BlockedThread");
            assertThat(output).contains("Lock-C");
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
        assertThat(command).isNotNull();
    }
}
