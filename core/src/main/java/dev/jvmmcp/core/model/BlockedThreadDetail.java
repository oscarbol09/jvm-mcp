package dev.jvmmcp.core.model;

import java.util.List;

/**
 * Diagnostic record representing a thread currently in BLOCKED state waiting on a monitor lock.
 * <p>
 * {@code blockedTimeMs} is {@code null} when thread contention timing is unavailable on the
 * target JVM, signaling that the reported blocked duration is not monitored.
 */
public record BlockedThreadDetail(
    long threadId,
    String threadName,
    Long blockedTimeMs,
    long blockedCount,
    String lockName,
    Long lockOwnerId,
    String lockOwnerName,
    List<ThreadStackFrame> stackTrace
) {}
