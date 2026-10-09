package dev.jvmmcp.core.jmx;

import dev.jvmmcp.core.model.*;

import javax.management.MBeanServerConnection;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Diagnostic client querying JVM Threading MXBean for thread dumps, deadlock detection, and lock contention analysis.
 */
import dev.jvmmcp.core.port.ThreadDiagnosticPort;

public class ThreadMXBeanClient implements ThreadDiagnosticPort {

    private final MBeanServerConnection mbsc;

    public ThreadMXBeanClient(MBeanServerConnection mbsc) {
        if (mbsc == null) {
            throw new IllegalArgumentException("MBeanServerConnection cannot be null.");
        }
        this.mbsc = mbsc;
    }

    public ThreadSummary getThreadSummary(long pid) throws IOException {
        ThreadMXBean threadMXBean = ManagementFactory.newPlatformMXBeanProxy(
            mbsc,
            ManagementFactory.THREAD_MXBEAN_NAME,
            ThreadMXBean.class
        );

        int totalCount = threadMXBean.getThreadCount();
        int daemonCount = threadMXBean.getDaemonThreadCount();
        int peakCount = threadMXBean.getPeakThreadCount();
        long totalStarted = threadMXBean.getTotalStartedThreadCount();

        long[] threadIds = threadMXBean.getAllThreadIds();
        
        int runnable = 0;
        int blocked = 0;
        int waiting = 0;
        int timedWaiting = 0;

        // Chunking to prevent massive JMX serialization and GC pressure on target JVM
        // Using maxDepth 0 avoids heavy stack trace generation.
        int chunkSize = 500;
        for (int i = 0; i < threadIds.length; i += chunkSize) {
            int end = Math.min(threadIds.length, i + chunkSize);
            long[] chunk = Arrays.copyOfRange(threadIds, i, end);
            ThreadInfo[] chunkInfos = threadMXBean.getThreadInfo(chunk, 0);
            
            for (ThreadInfo info : chunkInfos) {
                if (info == null) continue;
                switch (info.getThreadState()) {
                    case RUNNABLE -> runnable++;
                    case BLOCKED -> blocked++;
                    case WAITING -> waiting++;
                    case TIMED_WAITING -> timedWaiting++;
                    default -> {}
                }
            }
        }

        return new ThreadSummary(
            pid,
            totalCount,
            daemonCount,
            peakCount,
            totalStarted,
            runnable,
            blocked,
            waiting,
            timedWaiting
        );
    }

    public ThreadDump getThreadDump(long pid) throws IOException {
        return getThreadDump(pid, true, true);
    }

    public ThreadDump getThreadDump(long pid, boolean lockedMonitors, boolean lockedSynchronizers) throws IOException {
        ThreadMXBean threadMXBean = ManagementFactory.newPlatformMXBeanProxy(
            mbsc,
            ManagementFactory.THREAD_MXBEAN_NAME,
            ThreadMXBean.class
        );

        ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(lockedMonitors, lockedSynchronizers);
        List<ThreadDetail> threadDetails = Arrays.stream(threadInfos)
            .filter(Objects::nonNull)
            .map(ThreadDetail::from)
            .toList();

        return new ThreadDump(pid, Instant.now(), threadDetails.size(), threadDetails);
    }

    public DeadlockReport detectDeadlocks() throws IOException {
        ThreadMXBean threadMXBean = ManagementFactory.newPlatformMXBeanProxy(
            mbsc,
            ManagementFactory.THREAD_MXBEAN_NAME,
            ThreadMXBean.class
        );

        long[] deadlockedIds = threadMXBean.findDeadlockedThreads();
        if (deadlockedIds == null || deadlockedIds.length == 0) {
            deadlockedIds = threadMXBean.findMonitorDeadlockedThreads();
        }

        if (deadlockedIds == null || deadlockedIds.length == 0) {
            return DeadlockReport.none();
        }

        ThreadInfo[] deadlockedInfos = threadMXBean.getThreadInfo(deadlockedIds, true, true);
        List<DeadlockedThreadDetail> chain = new ArrayList<>();

        for (ThreadInfo info : deadlockedInfos) {
            if (info == null) continue;

            String stackTop = "Unknown";
            if (info.getStackTrace().length > 0) {
                stackTop = ThreadStackFrame.from(info.getStackTrace()[0]).toString();
            }

            Long ownerId = info.getLockOwnerId() >= 0 ? info.getLockOwnerId() : null;
            String lockName = info.getLockName() != null ? info.getLockName() : "Unknown Monitor";
            String waitingToAcquire = lockName + (ownerId != null ? " (owned by thread " + ownerId + ")" : "");

            chain.add(new DeadlockedThreadDetail(
                info.getThreadId(),
                info.getThreadName(),
                info.getThreadState().name(),
                waitingToAcquire,
                ownerId,
                info.getLockOwnerName(),
                stackTop
            ));
        }

        String threadNames = chain.stream()
            .map(d -> "'" + d.threadName() + "' (ID " + d.threadId() + ")")
            .collect(Collectors.joining(" and "));

        String recommendation = "Deadlock detected among threads " + threadNames + 
            ". Inspect competing synchronization locks and enforce a consistent lock acquisition order.";

        return DeadlockReport.detected(chain, recommendation);
    }

    public List<BlockedThreadDetail> findBlockedThreads(long thresholdMs) throws IOException {
        ThreadMXBean threadMXBean = ManagementFactory.newPlatformMXBeanProxy(
            mbsc,
            ManagementFactory.THREAD_MXBEAN_NAME,
            ThreadMXBean.class
        );

        boolean contentionMonitored = false;
        boolean wasEnabled = false;
        if (threadMXBean.isThreadContentionMonitoringSupported()) {
            wasEnabled = threadMXBean.isThreadContentionMonitoringEnabled();
            if (!wasEnabled) threadMXBean.setThreadContentionMonitoringEnabled(true);
            contentionMonitored = true;
        }

        try {
            ThreadInfo[] threadInfos = threadMXBean.dumpAllThreads(true, true);
            List<BlockedThreadDetail> blockedList = new ArrayList<>();

            for (ThreadInfo info : threadInfos) {
                if (info == null || info.getThreadState() != Thread.State.BLOCKED) {
                    continue;
                }

                // Without contention monitoring, getBlockedTime() always reports -1
                long blockedTime = info.getBlockedTime();
                Long blockedTimeMs = contentionMonitored && blockedTime >= 0 ? blockedTime : null;
                if (thresholdMs > 0 && blockedTimeMs != null && blockedTimeMs < thresholdMs) {
                    continue;
                }

                List<ThreadStackFrame> stackFrames = Arrays.stream(info.getStackTrace())
                    .map(ThreadStackFrame::from)
                    .toList();

                Long ownerId = info.getLockOwnerId() >= 0 ? info.getLockOwnerId() : null;

                blockedList.add(new BlockedThreadDetail(
                    info.getThreadId(),
                    info.getThreadName(),
                    blockedTimeMs,
                    info.getBlockedCount(),
                    info.getLockName(),
                    ownerId,
                    info.getLockOwnerName(),
                    stackFrames
                ));
            }

            return blockedList;
        } finally {
            if (contentionMonitored && !wasEnabled) {
                threadMXBean.setThreadContentionMonitoringEnabled(false);
            }
        }
    }

    /**
     * Enables thread contention monitoring on the target JVM when supported, so blocked
     * threads report real contention durations instead of -1.
     *
     * @return true if contention timings are monitored after the call
     */
    private boolean ensureContentionMonitoring(ThreadMXBean threadMXBean) throws IOException {
        if (!threadMXBean.isThreadContentionMonitoringSupported()) {
            return false;
        }
        if (!threadMXBean.isThreadContentionMonitoringEnabled()) {
            threadMXBean.setThreadContentionMonitoringEnabled(true);
        }
        return threadMXBean.isThreadContentionMonitoringEnabled();
    }

    @Override
    public void close() throws Exception {
        // Client itself holds no native resources; JmxConnectionManager manages the transport.
    }
}
