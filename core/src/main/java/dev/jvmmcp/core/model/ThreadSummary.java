package dev.jvmmcp.core.model;

/**
 * High-level breakdown of JVM thread states and counts.
 *
 * <p>Platform thread counts are obtained from {@link java.lang.management.ThreadMXBean}.
 * Virtual thread statistics, when available on supported Java 21+ runtimes, are captured
 * via HotSpot diagnostic commands and represented in {@link #virtualThreads()}.
 *
 * @param pid target process ID
 * @param totalCount live platform thread count
 * @param daemonCount live platform daemon thread count
 * @param peakCount peak live platform thread count
 * @param totalStartedCount total started platform threads
 * @param runnableCount platform threads in RUNNABLE state
 * @param blockedCount platform threads in BLOCKED state
 * @param waitingCount platform threads in WAITING state
 * @param timedWaitingCount platform threads in TIMED_WAITING state
 * @param virtualThreads virtual thread statistics, or {@code null} if unsupported or unavailable
 */
public record ThreadSummary(
    long pid,
    int totalCount,
    int daemonCount,
    int peakCount,
    long totalStartedCount,
    int runnableCount,
    int blockedCount,
    int waitingCount,
    int timedWaitingCount,
    VirtualThreadSummary virtualThreads
) {
    public ThreadSummary(
        long pid,
        int totalCount,
        int daemonCount,
        int peakCount,
        long totalStartedCount,
        int runnableCount,
        int blockedCount,
        int waitingCount,
        int timedWaitingCount
    ) {
        this(pid, totalCount, daemonCount, peakCount, totalStartedCount,
             runnableCount, blockedCount, waitingCount, timedWaitingCount, null);
    }
}
