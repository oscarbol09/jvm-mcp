package dev.jvmmcp.core.jmx;

import dev.jvmmcp.core.model.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryMXBeanClientTest {

    private JmxConnectionManager connectionManager;
    private MemoryMXBeanClient memoryClient;

    @BeforeEach
    void setUp() {
        connectionManager = JmxConnectionManager.connectLocal();
        memoryClient = new MemoryMXBeanClient(connectionManager.getMBeanServerConnection());
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
        assertThatThrownBy(() -> new MemoryMXBeanClient(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MBeanServerConnection cannot be null");
    }

    @Test
    @DisplayName("getHeapSummary should extract valid live memory and GC metrics from current JVM")
    void shouldExtractValidHeapSummary() throws IOException {
        long currentPid = ProcessHandle.current().pid();
        HeapSummary summary = memoryClient.getHeapSummary(currentPid);

        assertThat(summary).isNotNull();
        assertThat(summary.pid()).isEqualTo(currentPid);
        assertThat(summary.heap().usedBytes()).isPositive();
        assertThat(summary.heap().committedBytes()).isPositive();
        assertThat(summary.pools()).isNotEmpty();
        assertThat(summary.garbageCollectors()).isNotEmpty();
        assertThat(summary.pressure()).isNotNull();
        assertThat(summary.pressure().level()).isNotNull();
        assertThat(summary.classLoading()).isNotNull();
        assertThat(summary.classLoading().loadedClassCount()).isPositive();
        assertThat(summary.classLoading().totalLoadedClasses()).isPositive();
        assertThat(summary.classLoading().unloadedClassCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("getClassLoadingInfo extracts valid class loading metrics from current JVM")
    void shouldExtractClassLoadingInfo() {
        ClassLoadingInfo info = memoryClient.getClassLoadingInfo();

        assertThat(info).isNotNull();
        assertThat(info.loadedClassCount()).isPositive();
        assertThat(info.totalLoadedClasses()).isPositive();
        assertThat(info.unloadedClassCount()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("getClassLoadingInfo returns null gracefully when MBean query fails")
    void shouldReturnNullWhenClassLoadingMBeanFails() throws Exception {
        javax.management.MBeanServerConnection failingMbsc = org.mockito.Mockito.mock(javax.management.MBeanServerConnection.class);
        org.mockito.Mockito.when(failingMbsc.getAttribute(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenThrow(new javax.management.InstanceNotFoundException("MBean not found"));
        org.mockito.Mockito.when(failingMbsc.getMBeanInfo(org.mockito.ArgumentMatchers.any()))
            .thenThrow(new javax.management.InstanceNotFoundException("MBean not found"));

        MemoryMXBeanClient failingClient = new MemoryMXBeanClient(failingMbsc);
        assertThat(failingClient.getClassLoadingInfo()).isNull();
    }

    @Test
    @DisplayName("getMemoryPools extracts pool types, usage, and peak usage")
    void shouldExtractMemoryPools() throws IOException {
        List<MemoryPoolInfo> pools = memoryClient.getMemoryPools();

        assertThat(pools).isNotEmpty();
        assertThat(pools.get(0).name()).isNotBlank();
        assertThat(pools.get(0).type()).isIn("HEAP", "NON_HEAP");
    }

    @Test
    @DisplayName("getBufferPools extracts direct and mapped buffer statistics")
    void shouldExtractBufferPools() throws IOException {
        List<BufferPoolInfo> bufferPools = memoryClient.getBufferPools();

        assertThat(bufferPools).isNotEmpty();
        assertThat(bufferPools)
            .extracting(BufferPoolInfo::name)
            .contains("direct", "mapped");

        assertThat(bufferPools).allSatisfy(pool -> {
            assertThat(pool.name()).isNotBlank();
            assertThat(pool.count()).isGreaterThanOrEqualTo(0);
            assertThat(pool.totalCapacityBytes()).isGreaterThanOrEqualTo(0);
            assertThat(pool.memoryUsedBytes()).isGreaterThanOrEqualTo(0);
        });
    }

    @Test
    @DisplayName("getGarbageCollectors extracts GC names and collection stats")
    void shouldExtractGarbageCollectors() throws IOException {
        List<GarbageCollectorInfo> gcs = memoryClient.getGarbageCollectors();

        assertThat(gcs).isNotEmpty();
        assertThat(gcs.get(0).name()).isNotBlank();
        assertThat(gcs.get(0).memoryPoolNames()).isNotNull();
    }

    @Test
    @DisplayName("evaluatePressure flags CRITICAL when heap ratio exceeds 95%")
    void shouldFlagCriticalPressure() {
        MemoryUsageInfo criticalUsage = new MemoryUsageInfo(100, 960, 1000, 1000, 96.0, 96.0, 100.0);
        MemoryPressure pressure = memoryClient.evaluatePressure(criticalUsage, List.of(), List.of(
            new GarbageCollectorInfo("G1 Young", 10, 150, new String[]{"G1 Eden"})
        ), 10000L);

        assertThat(pressure.level()).isEqualTo(MemoryPressureLevel.CRITICAL);
        assertThat(pressure.recommendation()).contains("CRITICAL");
        assertThat(pressure.totalGcCount()).isEqualTo(10);
        assertThat(pressure.totalGcTimeMs()).isEqualTo(150);
    }

    @Test
    @DisplayName("evaluatePressure flags ELEVATED when heap ratio is between 85% and 95%")
    void shouldFlagElevatedPressure() {
        MemoryUsageInfo elevatedUsage = new MemoryUsageInfo(100, 880, 1000, 1000, 88.0, 88.0, 100.0);
        MemoryPressure pressure = memoryClient.evaluatePressure(elevatedUsage, List.of(), List.of(), 10000L);

        assertThat(pressure.level()).isEqualTo(MemoryPressureLevel.ELEVATED);
        assertThat(pressure.recommendation()).contains("ELEVATED");
    }

    @Test
    @DisplayName("evaluatePressure flags NORMAL when heap ratio is low")
    void shouldFlagNormalPressure() {
        MemoryUsageInfo normalUsage = new MemoryUsageInfo(100, 200, 1000, 1000, 20.0, 20.0, 100.0);
        MemoryPressure pressure = memoryClient.evaluatePressure(normalUsage, List.of(), List.of(), 10000L);

        assertThat(pressure.level()).isEqualTo(MemoryPressureLevel.NORMAL);
        assertThat(pressure.recommendation()).contains("NORMAL");
    }

    @Test
    @DisplayName("evaluatePressure falls back to committed bytes when max bytes is unassigned")
    void shouldHandleUndefinedMaxBytesInPressure() {
        MemoryUsageInfo undefinedMax = new MemoryUsageInfo(100, 450, 500, -1, 90.0, 450.0, 500.0);
        MemoryPressure pressure = memoryClient.evaluatePressure(undefinedMax, List.of(), List.of(), 10000L);

        assertThat(pressure.level()).isEqualTo(MemoryPressureLevel.ELEVATED);

        MemoryUsageInfo zeroCommitted = new MemoryUsageInfo(0, 0, 0, -1, 0.0, 0.0, 0.0);
        MemoryPressure zeroPressure = memoryClient.evaluatePressure(zeroCommitted, List.of(), List.of(), 10000L);
        assertThat(zeroPressure.level()).isEqualTo(MemoryPressureLevel.NORMAL);
    }

    @Test
    @DisplayName("evaluatePressure ignores high committed utilization for unbounded Metaspace")
    void shouldIgnoreCommittedUtilizationForUnboundedMetaspace() {
        MemoryUsageInfo normalHeap = new MemoryUsageInfo(100, 200, 500, 1000, 20.0, 200.0, 1000.0);
        MemoryPoolInfo unboundedMetaspace = pool("Metaspace", usage(99, 100, -1));

        MemoryPressure pressure = memoryClient.evaluatePressure(
            normalHeap,
            List.of(unboundedMetaspace),
            List.of(),
            10000L
        );

        assertThat(pressure.level()).isEqualTo(MemoryPressureLevel.NORMAL);
        assertThat(pressure.heapUsageRatio()).isEqualTo(0.2);
        assertThat(pressure.recommendation()).doesNotContain("Metaspace");
    }

    @Test
    @DisplayName("evaluatePressure flags bounded Metaspace and class space pressure but reports the heap ratio")
    void shouldFlagBoundedMetaspaceAndClassSpacePressure() {
        MemoryUsageInfo normalHeap = new MemoryUsageInfo(100, 200, 500, 1000, 20.0, 200.0, 1000.0);
        MemoryPoolInfo boundedMetaspace = pool("Metaspace", usage(960, 1000, 1000));
        MemoryPressure metaspacePressure = memoryClient.evaluatePressure(
            normalHeap,
            List.of(boundedMetaspace),
            List.of(),
            10000L
        );

        assertThat(metaspacePressure.level()).isEqualTo(MemoryPressureLevel.CRITICAL);
        assertThat(metaspacePressure.heapUsageRatio()).isEqualTo(0.2);
        assertThat(metaspacePressure.recommendation()).contains("Metaspace");

        MemoryPoolInfo unboundedMetaspace = pool("Metaspace", usage(99, 100, -1));
        MemoryPoolInfo boundedClassSpace = pool("Compressed Class Space", usage(96, 100, 100));
        MemoryPressure classSpacePressure = memoryClient.evaluatePressure(
            normalHeap,
            List.of(unboundedMetaspace, boundedClassSpace),
            List.of(),
            10000L
        );

        assertThat(classSpacePressure.level()).isEqualTo(MemoryPressureLevel.CRITICAL);
        assertThat(classSpacePressure.heapUsageRatio()).isEqualTo(0.2);
        assertThat(classSpacePressure.recommendation()).contains("Metaspace/Class Space");
    }

    @Test
    @DisplayName("evaluatePressure ignores zero and undefined Metaspace limits while preserving heap and GC pressure")
    void shouldIgnoreZeroMetaspaceLimitsAndPreserveHeapAndGcPressure() {
        MemoryUsageInfo normalHeap = new MemoryUsageInfo(100, 200, 500, 1000, 20.0, 200.0, 1000.0);
        MemoryPoolInfo zeroMetaspace = pool("Metaspace", usage(0, 0, -1));
        MemoryPoolInfo zeroLimitClassSpace = pool("Compressed Class Space", usage(99, 100, 0));
        MemoryPressure zeroPressure = memoryClient.evaluatePressure(
            normalHeap,
            List.of(zeroMetaspace, zeroLimitClassSpace),
            List.of(),
            10000L
        );
        assertThat(zeroPressure.level()).isEqualTo(MemoryPressureLevel.NORMAL);

        MemoryPoolInfo unboundedMetaspace = pool("Metaspace", usage(99, 100, -1));
        MemoryPressure heapPressure = memoryClient.evaluatePressure(
            new MemoryUsageInfo(100, 960, 1000, 1000, 96.0, 96.0, 100.0),
            List.of(unboundedMetaspace),
            List.of(),
            10000L
        );
        assertThat(heapPressure.level()).isEqualTo(MemoryPressureLevel.CRITICAL);
        assertThat(heapPressure.recommendation()).contains("Heap utilization");

        MemoryPressure gcPressure = memoryClient.evaluatePressure(
            normalHeap,
            List.of(unboundedMetaspace),
            List.of(new GarbageCollectorInfo("G1 Old", 10, 2000, new String[]{"G1 Old Gen"})),
            10000L
        );
        assertThat(gcPressure.level()).isEqualTo(MemoryPressureLevel.CRITICAL);
        assertThat(gcPressure.recommendation()).contains("GC Lifetime Overhead");
        assertThat(gcPressure.totalGcTimeMs()).isEqualTo(2000);
        assertThat(gcPressure.totalGcCount()).isEqualTo(10);
    }

    private MemoryPoolInfo pool(String name, MemoryUsageInfo usage) {
        return new MemoryPoolInfo(name, "NON_HEAP", usage, usage);
    }

    private MemoryUsageInfo usage(long used, long committed, long max) {
        return new MemoryUsageInfo(0, used, committed, max, 0.0, used, max);
    }
}