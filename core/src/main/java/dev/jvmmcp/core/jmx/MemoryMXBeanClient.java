package dev.jvmmcp.core.jmx;

import dev.jvmmcp.core.model.*;

import javax.management.MBeanServerConnection;
import java.io.IOException;
import java.lang.management.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostic client querying JVM Memory and Garbage Collector MXBeans via JMX.
 */
import dev.jvmmcp.core.port.MemoryDiagnosticPort;

public class MemoryMXBeanClient implements MemoryDiagnosticPort {

    private final MBeanServerConnection mbsc;

    public MemoryMXBeanClient(MBeanServerConnection mbsc) {
        if (mbsc == null) {
            throw new IllegalArgumentException("MBeanServerConnection cannot be null.");
        }
        this.mbsc = mbsc;
    }

    public HeapSummary getHeapSummary(long pid) throws IOException {
        MemoryMXBean memoryMXBean = ManagementFactory.newPlatformMXBeanProxy(
            mbsc,
            ManagementFactory.MEMORY_MXBEAN_NAME,
            MemoryMXBean.class
        );

        MemoryUsageInfo heapUsage = MemoryUsageInfo.from(memoryMXBean.getHeapMemoryUsage());
        MemoryUsageInfo nonHeapUsage = MemoryUsageInfo.from(memoryMXBean.getNonHeapMemoryUsage());

        List<MemoryPoolInfo> pools = getMemoryPools();
        List<GarbageCollectorInfo> gcs = getGarbageCollectors();

        MemoryPressure pressure = evaluatePressure(heapUsage, gcs);

        return new HeapSummary(pid, heapUsage, nonHeapUsage, pools, gcs, pressure);
    }

    public List<MemoryPoolInfo> getMemoryPools() throws IOException {
        List<MemoryPoolInfo> poolInfos = new ArrayList<>();
        List<MemoryPoolMXBean> poolBeans = ManagementFactory.getPlatformMXBeans(mbsc, MemoryPoolMXBean.class);

        for (MemoryPoolMXBean bean : poolBeans) {
            MemoryUsageInfo usage = MemoryUsageInfo.from(bean.getUsage());
            MemoryUsageInfo peak = MemoryUsageInfo.from(bean.getPeakUsage());
            poolInfos.add(new MemoryPoolInfo(
                bean.getName(),
                bean.getType().name(),
                usage,
                peak
            ));
        }

        return poolInfos;
    }

    public List<GarbageCollectorInfo> getGarbageCollectors() throws IOException {
        List<GarbageCollectorInfo> gcInfos = new ArrayList<>();
        List<GarbageCollectorMXBean> gcBeans = ManagementFactory.getPlatformMXBeans(mbsc, GarbageCollectorMXBean.class);

        for (GarbageCollectorMXBean bean : gcBeans) {
            gcInfos.add(new GarbageCollectorInfo(
                bean.getName(),
                bean.getCollectionCount(),
                bean.getCollectionTime(),
                bean.getMemoryPoolNames()
            ));
        }

        return gcInfos;
    }

    public MemoryPressure evaluatePressure(MemoryUsageInfo heapUsage, List<GarbageCollectorInfo> gcs) {
        double ratio = heapUsage.maxBytes() > 0 
            ? (double) heapUsage.usedBytes() / (double) heapUsage.maxBytes()
            : (heapUsage.committedBytes() > 0 ? (double) heapUsage.usedBytes() / (double) heapUsage.committedBytes() : 0.0);

        long totalGcTime = gcs.stream().mapToLong(GarbageCollectorInfo::collectionTimeMs).sum();
        long totalGcCount = gcs.stream().mapToLong(GarbageCollectorInfo::collectionCount).sum();

        MemoryPressureLevel level;
        String recommendation;

        if (ratio >= 0.95) {
            level = MemoryPressureLevel.CRITICAL;
            recommendation = "CRITICAL: Heap utilization exceeds 95%. Immediate risk of OutOfMemoryError. Recommend inspecting heap histogram for memory leaks or increasing -Xmx.";
        } else if (ratio >= 0.85) {
            level = MemoryPressureLevel.ELEVATED;
            recommendation = "ELEVATED: Heap utilization exceeds 85%. Frequent GC cycles may introduce latency spikes. Monitor object allocation rates.";
        } else {
            level = MemoryPressureLevel.NORMAL;
            recommendation = "NORMAL: Memory consumption is healthy (< 85% utilization). Garbage collection pauses are within normal thresholds.";
        }

        return new MemoryPressure(level, Math.round(ratio * 100.0) / 100.0, totalGcTime, totalGcCount, recommendation);
    }

    @Override
    public void close() throws Exception {
        // Client itself holds no native resources; JmxConnectionManager manages the transport.
    }
}
