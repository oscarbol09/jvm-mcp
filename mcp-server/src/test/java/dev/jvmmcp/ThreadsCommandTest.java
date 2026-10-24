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
import dev.jvmmcp.core.model.ThreadSummary;
import dev.jvmmcp.core.util.SimpleJson;

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
            assertThat(output).contains("=".repeat(80));
            assertThat(output).contains("JVM THREAD DIAGNOSTICS FOR PID " + targetPid);
            assertThat(output).contains("Total Threads");
            assertThat(output).contains("RUNNABLE");
            assertThat(output).contains("DEADLOCK STATUS");
            assertThat(output).contains("No deadlocked threads detected.");
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
            assertThat(output).contains("-".repeat(80));
            assertThat(output).contains("DEADLOCK STATUS: DETECTED");
            assertThat(output).contains("Deadlock Count : 1 threads involved");
            assertThat(output).contains("Recommendation : Fix it");
            assertThat(output).contains("THREAD ID");
            assertThat(output).contains("Thread-1");
            assertThat(output).contains("Lock-A");
            assertThat(output).contains("Class.method(Class.java:10)");
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
        String parkedLock = "java.util.concurrent.locks.AbstractQueuedSynchronizer$ConditionObject@6f1c4a3";
        ThreadDump dump = new ThreadDump(targetPid, java.time.Instant.now(), 3, java.util.List.of(
            new dev.jvmmcp.core.model.ThreadDetail(2L, "Worker", "WAITING", null, null, null, 0L, 0L, 0L, 0L, false, false, java.util.List.of(
                new dev.jvmmcp.core.model.ThreadStackFrame("Class", "method", "Class.java", 10, false)
            )),
            new dev.jvmmcp.core.model.ThreadDetail(3L, "Worker2", "WAITING", "Lock-B", 3L, "Owner", 0L, 0L, 0L, 0L, false, false, java.util.List.of()),
            new dev.jvmmcp.core.model.ThreadDetail(4L, "Parked", "WAITING", parkedLock, null, null, 0L, 0L, 0L, 0L, false, false, java.util.List.of(
                new dev.jvmmcp.core.model.ThreadStackFrame("java.lang.Object", "wait", "Object.java", 480, true)
            ))
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
            assertThat(output).contains("=".repeat(80));
            assertThat(output).contains("THREAD DUMP FOR PID " + targetPid);
            assertThat(output).contains("\"Worker\" #2 [WAITING]");
            assertThat(output).contains("at Class.method(Class.java:10)");
            assertThat(output).contains("waiting on Lock-B held by Owner (ID 3)");

            // Owner present: lock owner name and ID must stay intact.
            String ownedLockBlock = dumpBlock(output, "\"Worker2\" #3 [WAITING]");
            assertThat(ownedLockBlock).contains("waiting on Lock-B held by Owner (ID 3)");

            // Owner absent but lock name present (WAITING/parked condition threads):
            // the lock must be reported without materialising "null" owner details.
            String parkedBlock = dumpBlock(output, "\"Parked\" #4 [WAITING]");
            assertThat(parkedBlock).contains("waiting on");
            assertThat(parkedBlock).contains(parkedLock);
            assertThat(parkedBlock).doesNotContain("held by null");
            assertThat(parkedBlock).doesNotContain("ID null");
            assertThat(parkedBlock).doesNotContain("null");

            // No lock at all: the waiting line must be omitted entirely.
            String noLockBlock = dumpBlock(output, "\"Worker\" #2 [WAITING]");
            assertThat(noLockBlock).contains("at Class.method(Class.java:10)");
            assertThat(noLockBlock).doesNotContain("waiting on");
            assertThat(noLockBlock).doesNotContain("held by");
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
            new dev.jvmmcp.core.model.BlockedThreadDetail(5L, "BlockedThread2", null, 0L, "Lock-D", null, null, java.util.List.of())
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
            assertThat(output).contains("=".repeat(80));
            assertThat(output).contains("BLOCKED THREADS FOR PID " + targetPid + " (2 found)");
            assertThat(output).contains("Thread #4 [BlockedThread]");
            assertThat(output).contains("Waiting on lock: Lock-C (Held by: OtherOwner [ID 5])");
            assertThat(output).contains("Blocked: 0 ms (count: 0)");
            assertThat(output).contains("Waiting on lock: Lock-D (Held by: None)");
            assertThat(output).contains("Blocked: Contention timing not monitored (count: 0)");
            assertThat(output).contains("at Class.method(Class.java:20)");
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

    @Test
    @DisplayName("detaches VirtualMachine when JMX connection fails")
    void shouldDetachWhenJmxConnectFails() throws Exception {
        long targetPid = 999999L;
        MemoryCommandTest.StubVirtualMachine mockVm = new MemoryCommandTest.StubVirtualMachine(false);
        when(mockAttachService.attach(String.valueOf(targetPid)))
            .thenReturn(AttachResult.success(String.valueOf(targetPid), mockVm));

        ThreadsCommand command = new ThreadsCommand(mockAttachService);
        command.pid = targetPid;

        int exitCode = command.call();
        assertThat(exitCode).isEqualTo(1);
        org.mockito.Mockito.verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("threads command displays virtual thread counts in summary when available")
    void shouldDisplayVirtualThreadCountInSummary() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ThreadMXBeanClient mockClient = org.mockito.Mockito.mock(ThreadMXBeanClient.class);
        dev.jvmmcp.core.model.ThreadSummary summary = new dev.jvmmcp.core.model.ThreadSummary(
            targetPid, 10, 2, 12, 50L, 5, 0, 3, 2,
            new dev.jvmmcp.core.model.VirtualThreadSummary(42)
        );
        when(mockClient.getThreadSummary(targetPid)).thenReturn(summary);
        when(mockClient.detectDeadlocks()).thenReturn(DeadlockReport.none());

        ThreadsCommand command = org.mockito.Mockito.spy(new ThreadsCommand(mockAttachService));
        command.pid = targetPid;
        org.mockito.Mockito.doReturn(mockClient).when(command).createThreadMXBeanClient(org.mockito.ArgumentMatchers.any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("Virtual Threads: 42");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("threads command displays virtual thread dump section when present")
    void shouldDisplayVirtualThreadDumpSection() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ThreadMXBeanClient mockClient = org.mockito.Mockito.mock(ThreadMXBeanClient.class);
        ThreadDump dump = new ThreadDump(
            targetPid,
            java.time.Instant.now(),
            1,
            java.util.List.of(
                new dev.jvmmcp.core.model.ThreadDetail(1L, "main", "RUNNABLE", null, null, null, 0L, 0L, 0L, 0L, false, false, java.util.List.of())
            ),
            java.util.List.of(
                new dev.jvmmcp.core.model.ThreadDetail(
                    99L, "vthread-test", "VIRTUAL", null, null, null, 0L, 0L, 0L, 0L, false, false,
                    java.util.List.of(new dev.jvmmcp.core.model.ThreadStackFrame("pkg.Task", "run", "Task.java", 15, false))
                )
            )
        );
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
            assertThat(output).contains("VIRTUAL THREADS (1 found)");
            assertThat(output).contains("\"vthread-test\" #99 [VIRTUAL]");
            assertThat(output).contains("at pkg.Task.run(Task.java:15)");
        } finally {
            System.setOut(originalOut);
        }
    }

    /**
     * Extracts the printed dump block of a single thread, from its {@code "name" #id [STATE]} header
     * up to the blank line that terminates the block.
     */
    private static String dumpBlock(String output, String threadHeader) {
        int start = output.indexOf(threadHeader);
        assertThat(start).as("thread header '%s' present in dump", threadHeader).isNotNegative();
        String blankLine = System.lineSeparator() + System.lineSeparator();
        int end = output.indexOf(blankLine, start);
        return end < 0 ? output.substring(start) : output.substring(start, end);
    }
    @Test
    @DisplayName("threads command should output only a JSON summary with --json")
    void shouldOutputJsonThreadSummary() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));
        ThreadMXBeanClient mockClient = org.mockito.Mockito.mock(ThreadMXBeanClient.class);
        ThreadSummary summary = new ThreadSummary(targetPid, 10, 2, 12, 50L, 5, 0, 3, 2);
        when(mockClient.getThreadSummary(targetPid)).thenReturn(summary);

        ThreadsCommand command = org.mockito.Mockito.spy(new ThreadsCommand(mockAttachService));
        command.pid = targetPid;
        command.json = true;
        org.mockito.Mockito.doReturn(mockClient).when(command).createThreadMXBeanClient(org.mockito.ArgumentMatchers.any());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            assertThat(command.call()).isZero();
            String output = out.toString().trim();
            assertThat(SimpleJson.parse(output)).isInstanceOf(java.util.Map.class);
            assertThat(output).contains("\"pid\":100").doesNotContain("JVM THREAD DIAGNOSTICS", "DEADLOCK STATUS", "====");
            org.mockito.Mockito.verify(mockClient, org.mockito.Mockito.never()).detectDeadlocks();
        } finally {
            System.setOut(originalOut);
        }
    }
}
