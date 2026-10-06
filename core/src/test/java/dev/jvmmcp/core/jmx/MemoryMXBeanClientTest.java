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
    }

    @Test
    @DisplayName("getMemoryPools extracts pool types, usage, and peak usage")
    void shouldExtractMemoryPools() throws IOException {
        List<MemoryPoolInfo> pools = memoryClient.getMemoryPools();

        assertThat(pools).isNotEmpty();
        assertThat(pools.get(0).poolName()).isNotBlank();
        assertThat(pools.get(0).type()).isIn("HEAP", "NON_HEAP");
    }

    @Test
    @DisplayName("getGarbageCollectors extracts GC names and collection stats")
    void shouldExtractGarbageCollectors() throws IOException {
        List<GarbageCollectorInfo> gcs = memoryClient.getGarbageCollectors();

        assertThat(gcs).isNotEmpty();
        assertThat(gcs.get(0).gcName()).isNotBlank();
        assertThat(gcs.get(0).memoryPoolNames()).isNotNull();
    }

    @Test
    @DisplayName("evaluatePressure flags CRITICAL when heap ratio exceeds 95%")
    void shouldFlagCriticalPressure() {
        MemoryUsageInfo criticalUsage = new MemoryUsageInfo(100, 960, 1000, 1000, 96.0, 96.0, 100.0);
        MemoryPressure pressure = memoryClient.evaluatePressure(criticalUsage, List.of(
            new GarbageCollectorInfo("G1 Young", 10, 150, new String[]{"G1 Eden"})
        ));

        assertThat(pressure.level()).isEqualTo(MemoryPressureLevel.CRITICAL);
        assertThat(pressure.recommendation()).contains("CRITICAL");
        assertThat(pressure.totalGcCount()).isEqualTo(10);
        assertThat(pressure.totalGcTimeMs()).isEqualTo(150);
    }

    @Test
    @DisplayName("evaluatePressure flags ELEVATED when heap ratio is between 85% and 95%")
    void shouldFlagElevatedPressure() {
        MemoryUsageInfo elevatedUsage = new MemoryUsageInfo(100, 880, 1000, 1000, 88.0, 88.0, 100.0);
        MemoryPressure pressure = memoryClient.evaluatePressure(elevatedUsage, List.of());

        assertThat(pressure.level()).isEqualTo(MemoryPressureLevel.ELEVATED);
        assertThat(pressure.recommendation()).contains("ELEVATED");
    }

    @Test
    @DisplayName("evaluatePressure flags NORMAL when heap ratio is low")
    void shouldFlagNormalPressure() {
        MemoryUsageInfo normalUsage = new MemoryUsageInfo(100, 200, 1000, 1000, 20.0, 20.0, 100.0);
        MemoryPressure pressure = memoryClient.evaluatePressure(normalUsage, List.of());

        assertThat(pressure.level()).isEqualTo(MemoryPressureLevel.NORMAL);
        assertThat(pressure.recommendation()).contains("NORMAL");
    }

    @Test
    @DisplayName("evaluatePressure falls back to committed bytes when max bytes is unassigned")
    void shouldHandleUndefinedMaxBytesInPressure() {
        MemoryUsageInfo undefinedMax = new MemoryUsageInfo(100, 450, 500, -1, 90.0, 450.0, 500.0);
        MemoryPressure pressure = memoryClient.evaluatePressure(undefinedMax, List.of());

        assertThat(pressure.level()).isEqualTo(MemoryPressureLevel.ELEVATED);

        MemoryUsageInfo zeroCommitted = new MemoryUsageInfo(0, 0, 0, -1, 0.0, 0.0, 0.0);
        MemoryPressure zeroPressure = memoryClient.evaluatePressure(zeroCommitted, List.of());
        assertThat(zeroPressure.level()).isEqualTo(MemoryPressureLevel.NORMAL);
    }
}
