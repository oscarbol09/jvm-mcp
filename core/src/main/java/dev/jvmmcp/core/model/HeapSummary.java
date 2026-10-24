package dev.jvmmcp.core.model;

import java.util.List;

/**
 * Comprehensive memory diagnostic summary of a target JVM process.
 *
 * @param pid target process ID
 * @param heap heap memory usage details
 * @param nonHeap non-heap memory usage details
 * @param pools list of memory pool statistics
 * @param garbageCollectors list of garbage collector statistics
 * @param pressure evaluated JVM memory pressure
 * @param classLoading class loading statistics, or {@code null} if unavailable
 */
public record HeapSummary(
    long pid,
    MemoryUsageInfo heap,
    MemoryUsageInfo nonHeap,
    List<MemoryPoolInfo> pools,
    List<GarbageCollectorInfo> garbageCollectors,
    MemoryPressure pressure,
    ClassLoadingInfo classLoading
) {
    public HeapSummary(
        long pid,
        MemoryUsageInfo heap,
        MemoryUsageInfo nonHeap,
        List<MemoryPoolInfo> pools,
        List<GarbageCollectorInfo> garbageCollectors,
        MemoryPressure pressure
    ) {
        this(pid, heap, nonHeap, pools, garbageCollectors, pressure, null);
    }
}
