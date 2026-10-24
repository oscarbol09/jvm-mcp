package dev.jvmmcp.core.model;

/**
 * Statistics for a JVM direct or mapped buffer pool.
 *
 * @param name buffer pool name, such as direct or mapped
 * @param count number of buffers in the pool
 * @param totalCapacityBytes total capacity of buffers in bytes
 * @param memoryUsedBytes memory used by buffers in bytes
 */
public record BufferPoolInfo(
    String name,
    long count,
    long totalCapacityBytes,
    long memoryUsedBytes
) {
}