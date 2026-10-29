package dev.jvmmcp.core.model;

import java.util.List;

public record HikariPoolsReport(List<HikariPoolStatistics> pools, String note) {}
