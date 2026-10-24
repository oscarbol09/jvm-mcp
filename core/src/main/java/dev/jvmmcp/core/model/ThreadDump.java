package dev.jvmmcp.core.model;

import java.time.Instant;
import java.util.List;

/**
 * Complete thread dump snapshot of a target JVM process.
 *
 * @param pid target process ID
 * @param timestamp snapshot instant
 * @param totalCount platform thread count
 * @param threads list of platform thread details
 * @param virtualThreads list of virtual thread details, or {@code null} if unsupported or unavailable
 */
public record ThreadDump(
    long pid,
    Instant timestamp,
    int totalCount,
    List<ThreadDetail> threads,
    List<ThreadDetail> virtualThreads
) {
    public ThreadDump(long pid, Instant timestamp, int totalCount, List<ThreadDetail> threads) {
        this(pid, timestamp, totalCount, threads, null);
    }
}
