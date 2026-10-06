package dev.jvmmcp.core.jmx;

import dev.jvmmcp.core.model.BlockedThreadDetail;
import dev.jvmmcp.core.model.DeadlockReport;
import dev.jvmmcp.core.model.ThreadDump;
import dev.jvmmcp.core.model.ThreadSummary;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ThreadMXBeanClientTest {

    private JmxConnectionManager connectionManager;
    private ThreadMXBeanClient threadClient;

    @BeforeEach
    void setUp() {
        connectionManager = JmxConnectionManager.connectLocal();
        threadClient = new ThreadMXBeanClient(connectionManager.getMBeanServerConnection());
    }

    @AfterEach
    void tearDown() {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Test
    @DisplayName("Constructor throws IllegalArgumentException when MBeanServerConnection is null")
    void shouldThrowWhenMbscIsNull() {
        assertThatThrownBy(() -> new ThreadMXBeanClient(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MBeanServerConnection cannot be null");
    }

    @Test
    @DisplayName("getThreadSummary should return accurate state counts for current JVM")
    void shouldExtractValidThreadSummary() throws IOException {
        long currentPid = ProcessHandle.current().pid();
        ThreadSummary summary = threadClient.getThreadSummary(currentPid);

        assertThat(summary).isNotNull();
        assertThat(summary.pid()).isEqualTo(currentPid);
        assertThat(summary.totalCount()).isPositive();
        assertThat(summary.runnableCount()).isPositive();
        assertThat(summary.peakCount()).isGreaterThanOrEqualTo(summary.totalCount());
    }

    @Test
    @DisplayName("getThreadDump should capture formatted thread stack frames")
    void shouldCaptureStructuredThreadDump() throws IOException {
        long currentPid = ProcessHandle.current().pid();
        ThreadDump dump = threadClient.getThreadDump(currentPid);

        assertThat(dump).isNotNull();
        assertThat(dump.pid()).isEqualTo(currentPid);
        assertThat(dump.threads()).isNotEmpty();

        boolean hasStackTrace = dump.threads().stream().anyMatch(t -> !t.stackTrace().isEmpty());
        assertThat(hasStackTrace).isTrue();
    }

    @Test
    @DisplayName("detectDeadlocks should return NONE report when no threads are deadlocked")
    void shouldReportNoDeadlocksOnCleanJvm() throws IOException {
        DeadlockReport report = threadClient.detectDeadlocks();

        assertThat(report).isNotNull();
        assertThat(report.status()).isEqualTo("NONE");
        assertThat(report.deadlockCount()).isZero();
        assertThat(report.chain()).isEmpty();
    }

    @Test
    @DisplayName("findBlockedThreads detects threads waiting on intrinsic monitor locks and filters by threshold")
    void shouldDetectBlockedThreads() throws Exception {
        Object lock = new Object();
        CountDownLatch lockAcquired = new CountDownLatch(1);
        AtomicBoolean keepHolding = new AtomicBoolean(true);

        Thread holdingThread = new Thread(() -> {
            synchronized (lock) {
                lockAcquired.countDown();
                while (keepHolding.get()) {
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException ignored) {
                        break;
                    }
                }
            }
        }, "holding-thread");

        Thread blockedThread = new Thread(() -> {
            try {
                lockAcquired.await();
            } catch (InterruptedException ignored) {}
            synchronized (lock) {
                // Entered after release
            }
        }, "blocked-worker-thread");

        holdingThread.start();
        blockedThread.start();

        try {
            Awaitility.await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(20))
                .until(() -> blockedThread.getState() == Thread.State.BLOCKED);

            List<BlockedThreadDetail> blockedList = threadClient.findBlockedThreads(0);

            assertThat(blockedList).isNotEmpty();
            BlockedThreadDetail detail = blockedList.stream()
                .filter(b -> "blocked-worker-thread".equals(b.threadName()))
                .findFirst()
                .orElse(null);

            assertThat(detail).isNotNull();
            assertThat(detail.lockOwnerName()).isEqualTo("holding-thread");
            assertThat(detail.lockOwnerId()).isEqualTo(holdingThread.getId());
            assertThat(detail.stackTrace()).isNotEmpty();

            // Threshold larger than elapsed block time filters it out
            List<BlockedThreadDetail> filtered = threadClient.findBlockedThreads(10_000_000L);
            assertThat(filtered).doesNotContain(detail);
        } finally {
            keepHolding.set(false);
            holdingThread.interrupt();
            holdingThread.join(2000);
            blockedThread.join(2000);
        }
    }
}
