package dev.jvmmcp.core.port;

import dev.jvmmcp.core.model.HikariPoolsReport;

public interface HikariDiagnosticPort extends AutoCloseable {
    HikariPoolsReport getPools() throws Exception;

    @Override
    void close() throws Exception;
}
