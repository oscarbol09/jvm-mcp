package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.jmx.ThreadMXBeanClient;
import dev.jvmmcp.core.model.*;
import dev.jvmmcp.core.util.SimpleJson;
import java.util.List;
import java.util.concurrent.Callable;
import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

@Command(
        name = "threads",
        aliases = {"thread", "th"},
        description =
                "Inspects JVM thread states, captures thread dumps, and detects circular deadlocks",
        mixinStandardHelpOptions = true)
public class ThreadsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Target JVM Process ID (PID)")
    long pid;

    @ArgGroup(multiplicity = "0..1")
    ThreadFlags threadFlags;

    @Option(
            names = {"--threshold-ms", "-t"},
            description = "Threshold for blocked threads, default to 0")
    int threshold = 0;

    @Option(
            names = {"--json"},
            description = "Output thread summary in JSON format")
    boolean json;

    @Option(
            names = {"--virtual"},
            description =
                    "Include virtual-thread counts (Java 21+; may create a large diagnostic dump)")
    boolean includeVirtualThreads;

    private final JvmAttachService attachService;

    public ThreadsCommand() {
        this.attachService = new JvmAttachService();
        this.threadFlags = new ThreadFlags();
    }

    public ThreadsCommand(JvmAttachService attachService) {
        this.attachService = attachService;
        this.threadFlags = new ThreadFlags();
    }

    @Override
    public Integer call() {
        if (pid <= 0) {
            System.err.println("[jvm-mcp] Error: A valid target PID must be specified.");
            return 1;
        }

        ThreadFlags flags = threadFlags != null ? threadFlags : new ThreadFlags();

        try {
            AttachResult attachResult = attachService.attach(String.valueOf(pid));

            if (!attachResult.isSuccessful()) {
                System.err.println("[jvm-mcp] " + attachResult.message());
                return 1;
            }

            com.sun.tools.attach.VirtualMachine vm = attachResult.virtualMachine().orElse(null);
            boolean[] jmxManagerOwnsVm = {false};
            try {
                try (JmxConnectionManager jmxManager =
                        vm != null
                                ? JmxConnectionManager.connect(vm, pid)
                                : JmxConnectionManager.connectLocal()) {
                    jmxManagerOwnsVm[0] = true;

                    ThreadMXBeanClient client = createThreadMXBeanClient(jmxManager);
                    String javaSpecificationVersion =
                            includeVirtualThreads ? getJavaSpecificationVersion(vm) : null;

                    if (json) {
                        ThreadSummary summary =
                                includeVirtualThreads
                                        ? client.getThreadSummary(
                                                pid, true, javaSpecificationVersion)
                                        : client.getThreadSummary(pid);
                        System.out.println(SimpleJson.toJson(summary));
                        return 0;
                    }

                    if (flags.deadlocksOnly) {
                        printDeadlocks(client);
                        return 0;
                    }

                    if (threshold > 0 || flags.blockedOnly) {
                        printBlockedThreads(client);
                    }

                    if (flags.dump) {
                        printThreadDump(client);
                    }

                    if (!flags.blockedOnly
                            && !flags.deadlocksOnly
                            && !flags.dump
                            && threshold == 0) {
                        printThreadSummary(client, javaSpecificationVersion);
                        printDeadlocks(client);
                    }
                    return 0;
                }

            } finally {
                if (vm != null && !jmxManagerOwnsVm[0]) {
                    try {
                        attachService.detach(vm);
                    } catch (Exception ignored) {
                    }
                }
            }

        } catch (Exception e) {
            System.err.println("[jvm-mcp] Error querying thread metrics: " + e.getMessage());
            return 1;
        }
    }

    private void printThreadSummary(ThreadMXBeanClient client, String javaSpecificationVersion)
            throws Exception {
        ThreadSummary summary =
                includeVirtualThreads
                        ? client.getThreadSummary(pid, true, javaSpecificationVersion)
                        : client.getThreadSummary(pid);

        System.out.println("=".repeat(80));
        System.out.printf(" JVM THREAD DIAGNOSTICS FOR PID %d%n", pid);
        System.out.println("=".repeat(80));
        System.out.printf(
                "Total Threads : %-6d (Peak: %d, Daemon: %d)%n",
                summary.totalCount(), summary.peakCount(), summary.daemonCount());
        System.out.printf(
                "RUNNABLE      : %-6d WAITING : %-6d%n",
                summary.runnableCount(), summary.waitingCount());
        System.out.printf(
                "TIMED_WAITING : %-6d BLOCKED : %-6d%n",
                summary.timedWaitingCount(), summary.blockedCount());
        if (summary.virtualThreads() != null) {
            System.out.printf("Virtual Threads: %d%n", summary.virtualThreads().totalCount());
        }
    }

    private String getJavaSpecificationVersion(com.sun.tools.attach.VirtualMachine vm) {
        try {
            return vm == null
                    ? System.getProperty("java.specification.version")
                    : vm.getSystemProperties().getProperty("java.specification.version");
        } catch (Exception ignored) {
            return null;
        }
    }

    private void printDeadlocks(ThreadMXBeanClient client) throws Exception {
        DeadlockReport report = client.detectDeadlocks();

        System.out.println("-".repeat(80));
        System.out.printf("DEADLOCK STATUS: %s%n", report.status());
        if ("DETECTED".equals(report.status())) {
            System.out.printf("Deadlock Count : %d threads involved%n", report.deadlockCount());
            System.out.printf("Recommendation : %s%n", report.recommendation());
            System.out.println("-".repeat(80));
            System.out.printf(
                    "%-10s %-25s %-30s %s%n",
                    "THREAD ID", "THREAD NAME", "WAITING FOR", "STACK TOP");
            System.out.println("-".repeat(80));
            for (DeadlockedThreadDetail detail : report.chain()) {
                System.out.printf(
                        "%-10d %-25s %-30s %s%n",
                        detail.threadId(),
                        detail.threadName(),
                        detail.waitingToAcquire(),
                        detail.stackTop());
            }
        } else {
            System.out.println("No deadlocked threads detected.");
        }
    }

    private void printBlockedThreads(ThreadMXBeanClient client) throws Exception {
        List<BlockedThreadDetail> blocked = client.findBlockedThreads(threshold);

        System.out.println("=".repeat(80));
        System.out.printf(" BLOCKED THREADS FOR PID %d (%d found)%n", pid, blocked.size());
        System.out.println("=".repeat(80));

        for (BlockedThreadDetail b : blocked) {
            System.out.printf("Thread #%d [%s]%n", b.threadId(), b.threadName());
            System.out.printf(
                    "  Waiting on lock: %s (Held by: %s)%n",
                    b.lockName(),
                    b.lockOwnerName() != null
                            ? b.lockOwnerName() + " [ID " + b.lockOwnerId() + "]"
                            : "None");
            System.out.printf(
                    "  Blocked: %s (count: %d)%n",
                    b.blockedTimeMs() != null
                            ? b.blockedTimeMs() + " ms"
                            : "Contention timing not monitored",
                    b.blockedCount());
            System.out.println("  Stack:");
            for (ThreadStackFrame f : b.stackTrace()) {
                System.out.println("    at " + f);
            }
            System.out.println();
        }
    }

    private void printThreadDump(ThreadMXBeanClient client) throws Exception {
        ThreadDump dump = client.getThreadDump(pid);

        System.out.println("=".repeat(80));
        System.out.printf(
                " THREAD DUMP FOR PID %d (Timestamp: %s, Total: %d)%n",
                pid, dump.timestamp(), dump.totalCount());
        System.out.println("=".repeat(80));

        for (ThreadDetail t : dump.threads()) {
            System.out.printf("\"%s\" #%d [%s]%n", t.threadName(), t.threadId(), t.state());
            if (t.lockName() != null) {
                if (t.lockOwnerId() != null) {
                    System.out.printf(
                            "  waiting on %s held by %s (ID %s)%n",
                            t.lockName(), t.lockOwnerName(), t.lockOwnerId());
                } else {
                    System.out.printf("  waiting on %s%n", t.lockName());
                }
            }
            for (ThreadStackFrame frame : t.stackTrace()) {
                System.out.println("    at " + frame);
            }
            System.out.println();
        }

        if (dump.virtualThreads() != null && !dump.virtualThreads().isEmpty()) {
            System.out.println("-".repeat(80));
            System.out.printf(" VIRTUAL THREADS (%d found)%n", dump.virtualThreads().size());
            System.out.println("-".repeat(80));
            for (ThreadDetail t : dump.virtualThreads()) {
                System.out.printf("\"%s\" #%d [%s]%n", t.threadName(), t.threadId(), t.state());
                for (ThreadStackFrame frame : t.stackTrace()) {
                    System.out.println("    at " + frame);
                }
                System.out.println();
            }
        }
    }

    protected ThreadMXBeanClient createThreadMXBeanClient(JmxConnectionManager jmxManager) {
        return new ThreadMXBeanClient(jmxManager.getMBeanServerConnection());
    }
}
