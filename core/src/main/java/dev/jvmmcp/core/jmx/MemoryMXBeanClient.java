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

        RuntimeMXBean runtimeMXBean = ManagementFactory.newPlatformMXBeanProxy(
            mbsc,
            ManagementFactory.RUNTIME_MXBEAN_NAME,
            RuntimeMXBean.class
        );

        long uptimeMs = runtimeMXBean.getUptime();

        MemoryUsageInfo heapUsage = MemoryUsageInfo.from(memoryMXBean.getHeapMemoryUsage());
        MemoryUsageInfo nonHeapUsage = MemoryUsageInfo.from(memoryMXBean.getNonHeapMemoryUsage());

        List<MemoryPoolInfo> pools = getMemoryPools();
        List<GarbageCollectorInfo> gcs = getGarbageCollectors();

        MemoryPressure pressure = evaluatePressure(heapUsage, pools, gcs, uptimeMs);

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

    public MemoryPressure evaluatePressure(MemoryUsageInfo heapUsage, List<MemoryPoolInfo> pools, List<GarbageCollectorInfo> gcs, long uptimeMs) {
        double heapRatio = heapUsage.maxBytes() > 0 
            ? (double) heapUsage.usedBytes() / (double) heapUsage.maxBytes()
            : (heapUsage.committedBytes() > 0 ? (double) heapUsage.usedBytes() / (double) heapUsage.committedBytes() : 0.0);

        double maxMetaspaceRatio = 0.0;
        for (MemoryPoolInfo pool : pools) {
            String name = pool.name().toLowerCase();
            if (name.contains("metaspace") || name.contains("compressed class space")) {
                MemoryUsageInfo usage = pool.usage();
                if (usage.maxBytes() > 0) {
                    double ratio = (double) usage.usedBytes() / (double) usage.maxBytes();
                    if (ratio > maxMetaspaceRatio) {
                        maxMetaspaceRatio = ratio;
                    }
                }
            }
        }

        double maxMemoryRatio = Math.max(heapRatio, maxMetaspaceRatio);

        long totalGcTime = gcs.stream().mapToLong(GarbageCollectorInfo::collectionTimeMs).sum();
        long totalGcCount = gcs.stream().mapToLong(GarbageCollectorInfo::collectionCount).sum();

        double gcOverhead = uptimeMs > 0 ? ((double) totalGcTime / (double) uptimeMs) : 0.0;

        MemoryPressureLevel level;
        String recommendation;

        if (gcOverhead >= 0.20) {
            level = MemoryPressureLevel.CRITICAL;
            recommendation = "CRITICAL: GC Lifetime Overhead is " + Math.round(gcOverhead * 100) + "%. The JVM is spending excessive time in Garbage Collection (Thrashing), severely degrading throughput. Inspect for memory leaks.";
        } else if (maxMemoryRatio >= 0.95) {
            level = MemoryPressureLevel.CRITICAL;
            if (maxMetaspaceRatio >= 0.95) {
                recommendation = "CRITICAL: Metaspace/Class Space utilization exceeds 95%. Immediate risk of java.lang.OutOfMemoryError: Metaspace. Recommend checking for ClassLoader leaks or increasing -XX:MaxMetaspaceSize.";
            } else {
                recommendation = "CRITICAL: Heap utilization exceeds 95%. Immediate risk of java.lang.OutOfMemoryError: Java heap space. Recommend inspecting heap histogram for memory leaks or increasing -Xmx.";
            }
        } else if (maxMemoryRatio >= 0.85 || gcOverhead >= 0.10) {
            level = MemoryPressureLevel.ELEVATED;
            if (gcOverhead >= 0.10) {
                recommendation = "ELEVATED: GC Overhead is " + Math.round(gcOverhead * 100) + "%. Frequent GC cycles are introducing latency spikes. Monitor object allocation rates.";
            } else {
                recommendation = "ELEVATED: Memory utilization exceeds 85%. Monitor memory pools.";
            }
        } else {
            level = MemoryPressureLevel.NORMAL;
            recommendation = "NORMAL: Memory consumption is healthy (< 85% utilization). Garbage collection pauses are within normal thresholds.";
        }

        return new MemoryPressure(level, Math.round(maxMemoryRatio * 100.0) / 100.0, totalGcTime, totalGcCount, recommendation);
    }

    @Override
    public void close() throws Exception {
        // Client itself holds no native resources; JmxConnectionManager manages the transport.
    }
}
