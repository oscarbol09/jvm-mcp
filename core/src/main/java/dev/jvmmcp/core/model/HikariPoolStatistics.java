package dev.jvmmcp.core.model;

public record HikariPoolStatistics(
    String poolName,
    int activeConnections,
    int idleConnections,
    int totalConnections,
    int threadsAwaitingConnection,
    int maximumPoolSize,
    double saturationRatio
) {
}