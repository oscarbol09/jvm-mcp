package dev.jvmmcp.core.model;

public record HikariPoolHealth(
    String poolName,
    String status,
    String recommendation
) {}
