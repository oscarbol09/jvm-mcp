package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.HeapHistogramReader;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.jmx.MemoryMXBeanClient;
import dev.jvmmcp.core.model.BufferPoolInfo;
import dev.jvmmcp.core.model.ClassHistogramItem;
import dev.jvmmcp.core.model.HeapHistogram;
import dev.jvmmcp.core.model.HeapSummary;
import dev.jvmmcp.core.model.MemoryPoolInfo;
import dev.jvmmcp.core.model.MemoryUsageInfo;
import dev.jvmmcp.core.util.SimpleJson;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.Locale;
import java.util.concurrent.Callable;

@Command(
    name = "memory",
    aliases = {"heap", "mem"},
    description = "Inspects JVM heap memory, pool regions, GC metrics, and allocation pressure",
    mixinStandardHelpOptions = true
)
public class MemoryCommand implements Callable<Integer> {

    private static final String UNBOUNDED_LABEL = "unbounded";
    private static final String UNKNOWN_PERCENT_LABEL = "N/A";

    @Parameters(index = "0", description = "Target JVM Process ID (PID)")
    long pid;

    @Option(names = {"--histogram", "-H"}, description = "Include live heap class histogram")
    boolean histogram;

    @Option(names = {"--json"}, description = "Output in JSON format")
    boolean json;

    @Option(names = {"--top", "-n"}, defaultValue = "20", description = "Number of top classes in histogram (default: 20)")
    int topN;

    private final JvmAttachService attachService;

    public MemoryCommand() {
        this.attachService = new JvmAttachService();
    }

    public MemoryCommand(JvmAttachService attachService) {
        this.attachService = attachService;
    }

    @Override
    public Integer call() {
        if (pid <= 0) {
            System.err.println("[jvm-mcp] Error: A valid target PID must be specified.");
            return 1;
        }

        try {
            AttachResult attachResult = attachService.attach(String.valueOf(pid));

            if (!attachResult.isSuccessful()) {
                System.err.println("[jvm-mcp] " + attachResult.message());
                return 1;
            }

            com.sun.tools.attach.VirtualMachine vm = attachResult.virtualMachine().orElse(null);
            boolean[] jmxManagerOwnsVm = {false};
            try {
                try (JmxConnectionManager jmxManager = vm != null
                        ? JmxConnectionManager.connect(vm, pid)
                        : JmxConnectionManager.connectLocal();
                     MemoryMXBeanClient client = new MemoryMXBeanClient(jmxManager.getMBeanServerConnection())) {
                    jmxManagerOwnsVm[0] = true;

                    HeapSummary summary = client.getHeapSummary(pid);

                    if (json) {
                        System.out.println(SimpleJson.toJson(summary));
                        return 0;
                    }

                System.out.println("=".repeat(80));
                System.out.printf(Locale.ROOT, " JVM MEMORY DIAGNOSTICS FOR PID %d%n", pid);
                System.out.println("=".repeat(80));

                System.out.printf(Locale.ROOT, "Heap Usage     : %.2f MB / %s (%s used)%n",
                    summary.heap().usedMb(), quotaLabel(summary.heap()), usedPercentLabel(summary.heap()));
                System.out.printf(Locale.ROOT, "Non-Heap Usage : %.2f MB / %s%n",
                    summary.nonHeap().usedMb(), quotaLabel(summary.nonHeap()));
                if (summary.classLoading() != null) {
                    System.out.printf(Locale.ROOT, "Classes Loaded : %d (Total: %d, Unloaded: %d)%n",
                        summary.classLoading().loadedClassCount(),
                        summary.classLoading().totalLoadedClasses(),
                        summary.classLoading().unloadedClassCount());
                }
                System.out.printf(Locale.ROOT, "Pressure Status: %s (Ratio: %.1f%%)%n",
                    summary.pressure().level(), summary.pressure().heapUsageRatio() * 100.0);
                System.out.printf(Locale.ROOT, "Recommendation : %s%n", summary.pressure().recommendation());

                System.out.println("-".repeat(80));
                System.out.println("MEMORY POOLS");
                System.out.printf(Locale.ROOT, "%-30s %-12s %-16s %s%n", "POOL NAME", "TYPE", "USED (MB)", "MAX (MB)");
                System.out.println("-".repeat(80));

                for (MemoryPoolInfo pool : summary.pools()) {
                    System.out.printf(Locale.ROOT, "%-30s %-12s %-16.2f %s%n",
                        pool.name(),
                        pool.type(),
                        pool.usage().usedMb(),
                        maxMbLabel(pool.usage())
                    );
                }

                System.out.println("-".repeat(80));
                System.out.println("DIRECT AND MAPPED BUFFER POOLS");
                System.out.printf(Locale.ROOT, "%-20s %-12s %-22s %s%n",
                    "POOL NAME", "COUNT", "CAPACITY (MB)", "MEMORY USED (MB)");
                System.out.println("-".repeat(80));

                for (BufferPoolInfo pool : summary.bufferPools()) {
                    System.out.printf(Locale.ROOT, "%-20s %-12s %-22s %s%n",
                        pool.name(),
                        pool.count() < 0 ? UNKNOWN_PERCENT_LABEL : Long.toString(pool.count()),
                        bufferMbLabel(pool.totalCapacityBytes()),
                        bufferMbLabel(pool.memoryUsedBytes())
                    );
                }

                if (histogram) {
                    System.out.println("-".repeat(80));
                    System.out.printf(Locale.ROOT, "HEAP HISTOGRAM (TOP %d CLASSES)%n", topN);
                    System.out.printf(Locale.ROOT, "%-6s %-14s %-14s %s%n", "RANK", "INSTANCES", "BYTES (MB)", "CLASS NAME");
                    System.out.println("-".repeat(80));

                    try {
                        HeapHistogramReader reader = new HeapHistogramReader();
                        HeapHistogram hist;
                        if (attachResult.virtualMachine().isPresent()) {
                            try {
                                hist = reader.readHistogram(attachResult.virtualMachine().get(), pid, topN);
                            } catch (NoSuchMethodException | IllegalAccessException
                                    | java.lang.reflect.InaccessibleObjectException e) {
                                // executeJCmd is HotSpot-specific and needs --add-opens on modular
                                // runtimes; fall back to the DiagnosticCommand MBean over JMX
                                hist = reader.readHistogram(jmxManager.getMBeanServerConnection(), pid, topN);
                            }
                        } else {
                            // Self-inspection has no VM handle; read the histogram over JMX
                            hist = reader.readHistogram(jmxManager.getMBeanServerConnection(), pid, topN);
                        }
                        for (ClassHistogramItem item : hist.topClasses()) {
                            System.out.printf(Locale.ROOT, "%-6d %-14d %-14.2f %s%n",
                                item.rank(),
                                item.instances(),
                                item.megabytes(),
                                item.className()
                            );
                        }
                    } catch (Exception e) {
                        System.err.println("[jvm-mcp] Could not extract live heap histogram: " + e.getMessage());
                    }
                }
                return 0;
            }
        } finally {
            if (vm != null && !jmxManagerOwnsVm[0]) {
                try {
                    attachService.detach(vm);
                } catch (Exception ignored) {
                }
            }
        }

        } catch (Exception e) {
            System.err.println("[jvm-mcp] Error querying memory metrics: " + e.getMessage());
            return 1;
        }
    }

    /**
     * JMX reports "no upper bound" as a negative max. Render that as text instead of printing
     * the {@code -1.0} sentinel (or passing committed bytes off as the quota); a max of zero is
     * a real bound and stays numeric.
     */
    private static String quotaLabel(MemoryUsageInfo usage) {
        return usage.maxBytes() < 0 ? UNBOUNDED_LABEL : String.format(Locale.ROOT, "%.2f MB", usage.maxMb());
    }

    private static String maxMbLabel(MemoryUsageInfo usage) {
        return usage.maxBytes() < 0 ? UNBOUNDED_LABEL : String.format(Locale.ROOT, "%.2f", usage.maxMb());
    }

    private static String usedPercentLabel(MemoryUsageInfo usage) {
        return usage.maxBytes() < 0 ? UNKNOWN_PERCENT_LABEL : String.format(Locale.ROOT, "%.1f%%", usage.usedPercent());
    }

    private static String bufferMbLabel(long bytes) {
        return bytes < 0 ? UNKNOWN_PERCENT_LABEL : String.format(Locale.ROOT, "%.2f", bytes / (1024.0 * 1024.0));
    }
}