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
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ThreadMXBeanClientTest {

    private JmxConnectionManager connectionManager;
    private ThreadMXBeanClient threadClient;

    @BeforeEach
    void setUp() {
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        if (mxBean.isThreadContentionMonitoringSupported()) {
            mxBean.setThreadContentionMonitoringEnabled(true);
        }

        connectionManager = JmxConnectionManager.connectLocal();
        threadClient = new ThreadMXBeanClient(connectionManager.getMBeanServerConnection());
    }

    @AfterEach
    void tearDown() {
        ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        if (mxBean.isThreadContentionMonitoringSupported()) {
            mxBean.setThreadContentionMonitoringEnabled(false);
        }

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
    @DisplayName("getThreadSummary returns zero state counts when getAllThreadIds returns null")
    void shouldSummarizeWhenThreadIdsAreNull() throws Exception {
        MBeanServerConnection mbsc = mock(MBeanServerConnection.class);
        ObjectName threading = new ObjectName(ManagementFactory.THREAD_MXBEAN_NAME);
        when(mbsc.isInstanceOf(any(), anyString())).thenReturn(true);
        when(mbsc.getAttribute(threading, "ThreadCount")).thenReturn(3);
        when(mbsc.getAttribute(threading, "DaemonThreadCount")).thenReturn(1);
        when(mbsc.getAttribute(threading, "PeakThreadCount")).thenReturn(4);
        when(mbsc.getAttribute(threading, "TotalStartedThreadCount")).thenReturn(9L);
        when(mbsc.getAttribute(threading, "AllThreadIds")).thenReturn(null);

        ThreadSummary summary = new ThreadMXBeanClient(mbsc).getThreadSummary(42L);

        assertThat(summary).isEqualTo(new ThreadSummary(42L, 3, 1, 4, 9L, 0, 0, 0, 0));
    }

    @Test
    @DisplayName("getThreadDump returns an empty dump when the MXBean returns null")
    void shouldHandleNullThreadDump() throws Exception {
        ThreadMXBean mockBean = mock(ThreadMXBean.class);
        when(mockBean.dumpAllThreads(true, true)).thenReturn(null);

        try (org.mockito.MockedStatic<ManagementFactory> mfStatic = org.mockito.Mockito.mockStatic(ManagementFactory.class)) {
            mfStatic.when(() -> ManagementFactory.newPlatformMXBeanProxy(
                    any(), anyString(),
                    org.mockito.ArgumentMatchers.eq(ThreadMXBean.class)))
                .thenReturn(mockBean);

            ThreadDump dump = new ThreadMXBeanClient(connectionManager.getMBeanServerConnection(), null)
                .getThreadDump(42L);

            assertThat(dump.pid()).isEqualTo(42L);
            assertThat(dump.timestamp()).isNotNull();
            assertThat(dump.totalCount()).isZero();
            assertThat(dump.threads()).isEmpty();
            assertThat(dump.virtualThreads()).isNull();
        }
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
    @DisplayName("getThreadDump marks virtualThreads as empty list when virtual thread reader returns zero threads")
    void shouldReturnEmptyVirtualThreadsWhenReaderReturnsZero() throws IOException {
        VirtualThreadDumpReader mockReader = org.mockito.Mockito.mock(VirtualThreadDumpReader.class);
        org.mockito.Mockito.when(mockReader.readVirtualThreads(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.of(List.of()));

        ThreadMXBeanClient client = new ThreadMXBeanClient(connectionManager.getMBeanServerConnection(), mockReader);
        ThreadDump dump = client.getThreadDump(42L);

        assertThat(dump.virtualThreads()).isNotNull().isEmpty();
        assertThat(dump.threads()).isNotEmpty();
    }

    @Test
    @DisplayName("getThreadDump marks virtualThreads as null when virtual thread reader returns empty or is unavailable")
    void shouldReturnNullVirtualThreadsWhenReaderUnavailable() throws IOException {
        VirtualThreadDumpReader mockReader = org.mockito.Mockito.mock(VirtualThreadDumpReader.class);
        org.mockito.Mockito.when(mockReader.readVirtualThreads(org.mockito.ArgumentMatchers.any()))
            .thenReturn(Optional.empty());

        ThreadMXBeanClient client = new ThreadMXBeanClient(connectionManager.getMBeanServerConnection(), mockReader);
        ThreadDump dump = client.getThreadDump(42L);

        assertThat(dump.virtualThreads()).isNull();
        assertThat(dump.threads()).isNotEmpty();
    }

    @Test
    @DisplayName("detectDeadlocks returns NONE when deadlocked thread details are null or empty")
    void shouldHandleMissingDeadlockedThreadDetails() throws Exception {
        ThreadMXBean mockBean = mock(ThreadMXBean.class);
        long[] deadlockedIds = {11L, 12L};
        when(mockBean.findDeadlockedThreads()).thenReturn(deadlockedIds);
        when(mockBean.getThreadInfo(deadlockedIds, true, true))
            .thenReturn(null, new java.lang.management.ThreadInfo[0]);

        try (org.mockito.MockedStatic<ManagementFactory> mfStatic = org.mockito.Mockito.mockStatic(ManagementFactory.class)) {
            mfStatic.when(() -> ManagementFactory.newPlatformMXBeanProxy(
                    any(), anyString(),
                    org.mockito.ArgumentMatchers.eq(ThreadMXBean.class)))
                .thenReturn(mockBean);

            assertThat(threadClient.detectDeadlocks()).isEqualTo(DeadlockReport.none());
            assertThat(threadClient.detectDeadlocks()).isEqualTo(DeadlockReport.none());
            org.mockito.Mockito.verify(mockBean, org.mockito.Mockito.times(2))
                .getThreadInfo(deadlockedIds, true, true);
        }
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
    @DisplayName("findBlockedThreads detects threads waiting on intrinsic monitor locks")
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
            assertThat(detail.lockOwnerId()).isEqualTo(holdingThread.threadId());
            assertThat(detail.stackTrace()).isNotEmpty();
        } finally {
            keepHolding.set(false);
            holdingThread.interrupt();
            holdingThread.join(2000);
            blockedThread.join(2000);
        }
    }

    @Test
    @DisplayName("getThreadSummary covers all thread states")
    void shouldReturnThreadSummaryWithAllStates() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        Object lock = new Object();

        Thread timed = new Thread(() -> {
            try { Thread.sleep(10000); } catch (InterruptedException e) {}
        });
        timed.start();

        Thread waiting = new Thread(() -> {
            synchronized (lock) {
                try { lock.wait(); } catch (InterruptedException e) {}
            }
        });
        waiting.start();

        Thread owner = new Thread(() -> {
            synchronized (lock) {
                latch.countDown();
                try { Thread.sleep(10000); } catch (InterruptedException e) {}
            }
        });
        owner.start();

        latch.await();
        Thread blocked = new Thread(() -> {
            synchronized (lock) {
               // blocks
            }
        });
        blocked.start();

        Thread.sleep(100);

        try {
            ThreadSummary summary = threadClient.getThreadSummary(0L);
            assertThat(summary.timedWaitingCount()).isGreaterThanOrEqualTo(1);
            assertThat(summary.waitingCount()).isGreaterThanOrEqualTo(1);
            assertThat(summary.blockedCount()).isGreaterThanOrEqualTo(1);
        } finally {
            timed.interrupt();
            waiting.interrupt();
            owner.interrupt();
            blocked.interrupt();
        }
    }

    @Test
    @DisplayName("detectDeadlocks handles null return gracefully")
    void shouldHandleNullDeadlockedThreads() throws Exception {
        java.lang.management.ThreadMXBean mockBean = org.mockito.Mockito.mock(java.lang.management.ThreadMXBean.class);
        org.mockito.Mockito.when(mockBean.findDeadlockedThreads()).thenReturn(null);

        try (org.mockito.MockedStatic<ManagementFactory> mfStatic = org.mockito.Mockito.mockStatic(ManagementFactory.class)) {
            mfStatic.when(() -> ManagementFactory.newPlatformMXBeanProxy(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(java.lang.management.ThreadMXBean.class)))
                .thenReturn(mockBean);

            DeadlockReport report = threadClient.detectDeadlocks();
            assertThat(report.status()).isEqualTo("NONE");
        }
    }

    @Test
    @DisplayName("findBlockedThreads returns an empty list and restores monitoring when the dump is null")
    void shouldHandleNullBlockedThreadDump() throws Exception {
        ThreadMXBean mockBean = mock(ThreadMXBean.class);
        when(mockBean.isThreadContentionMonitoringSupported()).thenReturn(true);
        when(mockBean.isThreadContentionMonitoringEnabled()).thenReturn(false);
        when(mockBean.dumpAllThreads(true, true)).thenReturn(null);

        try (org.mockito.MockedStatic<ManagementFactory> mfStatic = org.mockito.Mockito.mockStatic(ManagementFactory.class)) {
            mfStatic.when(() -> ManagementFactory.newPlatformMXBeanProxy(
                    any(), anyString(),
                    org.mockito.ArgumentMatchers.eq(ThreadMXBean.class)))
                .thenReturn(mockBean);

            assertThat(threadClient.findBlockedThreads(100)).isEmpty();
            org.mockito.Mockito.verify(mockBean).setThreadContentionMonitoringEnabled(true);
            org.mockito.Mockito.verify(mockBean).setThreadContentionMonitoringEnabled(false);
        }
    }

    @Test
    @DisplayName("findBlockedThreads enables contention monitoring when supported")
    void shouldEnableContentionMonitoringWhenSupported() throws Exception {
        java.lang.management.ThreadMXBean mockBean = org.mockito.Mockito.mock(java.lang.management.ThreadMXBean.class);
        org.mockito.Mockito.when(mockBean.isThreadContentionMonitoringSupported()).thenReturn(true);
        org.mockito.Mockito.when(mockBean.isThreadContentionMonitoringEnabled()).thenReturn(false);
        org.mockito.Mockito.when(mockBean.dumpAllThreads(true, true)).thenReturn(new java.lang.management.ThreadInfo[0]);

        try (org.mockito.MockedStatic<ManagementFactory> mfStatic = org.mockito.Mockito.mockStatic(ManagementFactory.class)) {
            mfStatic.when(() -> ManagementFactory.newPlatformMXBeanProxy(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(java.lang.management.ThreadMXBean.class)))
                .thenReturn(mockBean);

            assertThat(threadClient.findBlockedThreads(0)).isEmpty();
            org.mockito.Mockito.verify(mockBean).setThreadContentionMonitoringEnabled(true);
        }
    }

    @Test
    @DisplayName("findBlockedThreads reports unmonitored contention when monitoring is unsupported")
    void shouldReportUnmonitoredContentionWhenUnsupported() throws Exception {
        java.lang.management.ThreadMXBean mockBean = org.mockito.Mockito.mock(java.lang.management.ThreadMXBean.class);
        org.mockito.Mockito.when(mockBean.isThreadContentionMonitoringSupported()).thenReturn(false);

        java.lang.management.ThreadInfo blockedInfo = org.mockito.Mockito.mock(java.lang.management.ThreadInfo.class);
        org.mockito.Mockito.when(blockedInfo.getThreadState()).thenReturn(java.lang.Thread.State.BLOCKED);
        org.mockito.Mockito.when(blockedInfo.getBlockedTime()).thenReturn(-1L);
        org.mockito.Mockito.when(blockedInfo.getThreadId()).thenReturn(7L);
        org.mockito.Mockito.when(blockedInfo.getThreadName()).thenReturn("blocked-worker");
        org.mockito.Mockito.when(blockedInfo.getBlockedCount()).thenReturn(3L);
        org.mockito.Mockito.when(blockedInfo.getLockName()).thenReturn("lock@0x1");
        org.mockito.Mockito.when(blockedInfo.getLockOwnerId()).thenReturn(-1L);
        org.mockito.Mockito.when(blockedInfo.getLockOwnerName()).thenReturn(null);
        org.mockito.Mockito.when(blockedInfo.getStackTrace()).thenReturn(new java.lang.StackTraceElement[0]);
        org.mockito.Mockito.when(mockBean.dumpAllThreads(true, true)).thenReturn(new java.lang.management.ThreadInfo[]{blockedInfo});

        try (org.mockito.MockedStatic<ManagementFactory> mfStatic = org.mockito.Mockito.mockStatic(ManagementFactory.class)) {
            mfStatic.when(() -> ManagementFactory.newPlatformMXBeanProxy(
                    org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.eq(java.lang.management.ThreadMXBean.class)))
                .thenReturn(mockBean);

            java.util.List<BlockedThreadDetail> blocked = threadClient.findBlockedThreads(0);

            assertThat(blocked).hasSize(1);
            assertThat(blocked.get(0).blockedTimeMs()).isNull();
            assertThat(blocked.get(0).blockedCount()).isEqualTo(3L);
            org.mockito.Mockito.verify(mockBean, org.mockito.Mockito.never()).setThreadContentionMonitoringEnabled(true);
        }
    }

    @Test
    @DisplayName("findBlockedThreads enables contention monitoring on the live JVM when supported")
    void shouldEnableContentionMonitoringOnLiveJvm() throws Exception {
        java.lang.management.ThreadMXBean mxBean = ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(mxBean.isThreadContentionMonitoringSupported());
        mxBean.setThreadContentionMonitoringEnabled(false);
        try {
            threadClient.findBlockedThreads(0);
            assertThat(mxBean.isThreadContentionMonitoringEnabled()).isFalse();
        } finally {
            mxBean.setThreadContentionMonitoringEnabled(false);
        }
    }
}
