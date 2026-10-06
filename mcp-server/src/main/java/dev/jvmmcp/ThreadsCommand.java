package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.jmx.ThreadMXBeanClient;
import dev.jvmmcp.core.model.*;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "threads",
    aliases = {"thread", "th"},
    description = "Inspects JVM thread states, captures thread dumps, and detects circular deadlocks",
    mixinStandardHelpOptions = true
)
public class ThreadsCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Target JVM Process ID (PID)")
    long pid;

    @Option(names = {"--deadlocks", "-d"}, description = "Scan exclusively for deadlocks")
    boolean deadlocksOnly;

    @Option(names = {"--dump", "-D"}, description = "Display full thread dump with stack traces")
    boolean dump;

    @Option(names = {"--blocked", "-b"}, description = "List only blocked threads")
    boolean blockedOnly;

    private final JvmAttachService attachService;

    public ThreadsCommand() {
        this.attachService = new JvmAttachService();
    }

    public ThreadsCommand(JvmAttachService attachService) {
        this.attachService = attachService;
    }

    @Override
    public Integer call() {
        if (pid <= 0) {
            System.err.println("[jvm-mcp] Error: A valid target PID must be specified.");
            return 1;
        }

        try {
            AttachResult attachResult = attachService.attach(String.valueOf(pid));

            if (!attachResult.isSuccessful()) {
                System.err.println("[jvm-mcp] " + attachResult.message());
                return 1;
            }

            try (JmxConnectionManager jmxManager = attachResult.virtualMachine().isPresent() 
                    ? JmxConnectionManager.connect(attachResult.virtualMachine().get(), pid) 
                    : JmxConnectionManager.connectLocal()) {

                ThreadMXBeanClient client = createThreadMXBeanClient(jmxManager);

                if (deadlocksOnly) {
                    printDeadlocks(client);
                    return 0;
                }

                if (blockedOnly) {
                    printBlockedThreads(client);
                    return 0;
                }

                if (dump) {
                    printThreadDump(client);
                    return 0;
                }

                printThreadSummary(client);
                printDeadlocks(client);
                return 0;
            }

        } catch (Exception e) {
            System.err.println("[jvm-mcp] Error querying thread metrics: " + e.getMessage());
            return 1;
        }
    }

    private void printThreadSummary(ThreadMXBeanClient client) throws Exception {
        ThreadSummary summary = client.getThreadSummary(pid);

        System.out.println("=".repeat(80));
        System.out.printf(" JVM THREAD DIAGNOSTICS FOR PID %d%n", pid);
        System.out.println("=".repeat(80));
        System.out.printf("Total Threads : %-6d (Peak: %d, Daemon: %d)%n", 
            summary.totalCount(), summary.peakCount(), summary.daemonCount());
        System.out.printf("RUNNABLE      : %-6d WAITING : %-6d%n", 
            summary.runnableCount(), summary.waitingCount());
        System.out.printf("TIMED_WAITING : %-6d BLOCKED : %-6d%n", 
            summary.timedWaitingCount(), summary.blockedCount());
    }

    private void printDeadlocks(ThreadMXBeanClient client) throws Exception {
        DeadlockReport report = client.detectDeadlocks();

        System.out.println("-".repeat(80));
        System.out.printf("DEADLOCK STATUS: %s%n", report.status());
        if ("DETECTED".equals(report.status())) {
            System.out.printf("Deadlock Count : %d threads involved%n", report.deadlockCount());
            System.out.printf("Recommendation : %s%n", report.recommendation());
            System.out.println("-".repeat(80));
            System.out.printf("%-10s %-25s %-30s %s%n", "THREAD ID", "THREAD NAME", "WAITING FOR", "STACK TOP");
            System.out.println("-".repeat(80));
            for (DeadlockedThreadDetail detail : report.chain()) {
                System.out.printf("%-10d %-25s %-30s %s%n",
                    detail.threadId(),
                    detail.threadName(),
                    detail.waitingToAcquire(),
                    detail.stackTop()
                );
            }
        } else {
            System.out.println("No deadlocked threads detected.");
        }
    }

    private void printBlockedThreads(ThreadMXBeanClient client) throws Exception {
        List<BlockedThreadDetail> blocked = client.findBlockedThreads(0);

        System.out.println("=".repeat(80));
        System.out.printf(" BLOCKED THREADS FOR PID %d (%d found)%n", pid, blocked.size());
        System.out.println("=".repeat(80));

        for (BlockedThreadDetail b : blocked) {
            System.out.printf("Thread #%d [%s]%n", b.threadId(), b.threadName());
            System.out.printf("  Waiting on lock: %s (Held by: %s)%n", b.lockName(), 
                b.lockOwnerName() != null ? b.lockOwnerName() + " [ID " + b.lockOwnerId() + "]" : "None");
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
        System.out.printf(" THREAD DUMP FOR PID %d (Timestamp: %s, Total: %d)%n", 
            pid, dump.timestamp(), dump.totalCount());
        System.out.println("=".repeat(80));

        for (ThreadDetail t : dump.threads()) {
            System.out.printf("\"%s\" #%d [%s]%n", t.threadName(), t.threadId(), t.state());
            if (t.lockName() != null) {
                System.out.printf("  waiting on %s held by %s (ID %s)%n", 
                    t.lockName(), t.lockOwnerName(), t.lockOwnerId());
            }
            for (ThreadStackFrame frame : t.stackTrace()) {
                System.out.println("    at " + frame);
            }
            System.out.println();
        }
    }

    protected ThreadMXBeanClient createThreadMXBeanClient(JmxConnectionManager jmxManager) {
        return new ThreadMXBeanClient(jmxManager.getMBeanServerConnection());
    }
}
