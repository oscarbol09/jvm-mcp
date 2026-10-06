package dev.jvmmcp.core.port;

import dev.jvmmcp.core.model.ThreadSummary;
import dev.jvmmcp.core.model.DeadlockReport;
import dev.jvmmcp.core.model.BlockedThreadDetail;
import dev.jvmmcp.core.model.ThreadDump;
import java.util.List;

public interface ThreadDiagnosticPort extends AutoCloseable {
    ThreadSummary getThreadSummary(long pid) throws Exception;
    DeadlockReport detectDeadlocks() throws Exception;
    List<BlockedThreadDetail> findBlockedThreads(long timeoutMillis) throws Exception;
    ThreadDump getThreadDump(long pid) throws Exception;
    @Override void close() throws Exception;
}
