package dev.jvmmcp.core.port;

import dev.jvmmcp.core.model.HikariPoolStatistics;
import java.util.List;

public interface HikariDiagnosticPort extends AutoCloseable {
    List<HikariPoolStatistics> getPools() throws Exception;
    @Override void close() throws Exception;
}
