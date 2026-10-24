package dev.jvmmcp.core.model;

/**
 * Summary of virtual thread diagnostics in a Java 21+ JVM process.
 *
 * <p>Metrics are captured from HotSpot thread dump containers via the {@code DiagnosticCommand}
 * MBean ({@code Thread.dump_to_file -format=json}).
 *
 * <p>Note: HotSpot's JSON diagnostic dump schema provides thread identity (ID, name) and stack traces,
 * but does not expose an authoritative thread state enum, mounted carrier flag, or pinning indicator
 * for virtual threads. Consequently, mounted/unmounted and pinned counts are not inferred from stack
 * frames to prevent brittle or inaccurate heuristics.
 *
 * @param totalCount the total number of live virtual threads observed in the diagnostic dump
 */
public record VirtualThreadSummary(
    int totalCount
) {}
