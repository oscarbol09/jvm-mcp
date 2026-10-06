package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.HeapHistogramReader;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.jmx.MemoryMXBeanClient;
import dev.jvmmcp.core.model.ClassHistogramItem;
import dev.jvmmcp.core.model.HeapHistogram;
import dev.jvmmcp.core.model.HeapSummary;
import dev.jvmmcp.core.model.MemoryPoolInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(
    name = "memory",
    aliases = {"heap", "mem"},
    description = "Inspects JVM heap memory, pool regions, GC metrics, and allocation pressure",
    mixinStandardHelpOptions = true
)
public class MemoryCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Target JVM Process ID (PID)")
    long pid;

    @Option(names = {"--histogram", "-H"}, description = "Include live heap class histogram")
    boolean histogram;

    @Option(names = {"--top", "-n"}, defaultValue = "20", description = "Number of top classes in histogram (default: 20)")
    int topN;

    JvmAttachService attachService = new JvmAttachService();

    public MemoryCommand() {}

    public MemoryCommand(JvmAttachService attachService) {
        this.attachService = attachService;
    }

    @Override
    public Integer call() {
        if (pid <= 0) {
            System.err.println("[jvm-mcp] Error: A valid target PID must be specified.");
            return 1;
        }

        AttachResult attachResult = attachService.attach(String.valueOf(pid));

        if (!attachResult.isSuccessful()) {
            System.err.println("[jvm-mcp] " + attachResult.message());
            return 1;
        }

        try (JmxConnectionManager jmxManager = attachResult.virtualMachine().isPresent() 
                ? JmxConnectionManager.connect(attachResult.virtualMachine().get(), pid) 
                : JmxConnectionManager.connectLocal()) {

            MemoryMXBeanClient client = new MemoryMXBeanClient(jmxManager.getMBeanServerConnection());
            HeapSummary summary = client.getHeapSummary(pid);

            System.out.println("=".repeat(80));
            System.out.printf(" JVM MEMORY DIAGNOSTICS FOR PID %d%n", pid);
            System.out.println("=".repeat(80));

            System.out.printf("Heap Usage     : %.2f MB / %.2f MB (%.1f%% used)%n", 
                summary.heap().usedMb(), summary.heap().maxMb(), summary.heap().usedPercent());
            System.out.printf("Non-Heap Usage : %.2f MB / %.2f MB%n", 
                summary.nonHeap().usedMb(), summary.nonHeap().maxMb());
            System.out.printf("Pressure Status: %s (Ratio: %.1f%%)%n", 
                summary.pressure().level(), summary.pressure().heapUsageRatio() * 100.0);
            System.out.printf("Recommendation : %s%n", summary.pressure().recommendation());

            System.out.println("-".repeat(80));
            System.out.println("MEMORY POOLS");
            System.out.printf("%-30s %-12s %-16s %s%n", "POOL NAME", "TYPE", "USED (MB)", "MAX (MB)");
            System.out.println("-".repeat(80));

            for (MemoryPoolInfo pool : summary.pools()) {
                System.out.printf("%-30s %-12s %-16.2f %.2f%n",
                    pool.name(),
                    pool.type(),
                    pool.usage().usedMb(),
                    pool.usage().maxMb()
                );
            }

            if (histogram && attachResult.virtualMachine().isPresent()) {
                System.out.println("-".repeat(80));
                System.out.printf("HEAP HISTOGRAM (TOP %d CLASSES)%n", topN);
                System.out.printf("%-6s %-14s %-14s %s%n", "RANK", "INSTANCES", "BYTES (MB)", "CLASS NAME");
                System.out.println("-".repeat(80));

                try {
                    HeapHistogramReader reader = new HeapHistogramReader();
                    HeapHistogram hist = reader.readHistogram(attachResult.virtualMachine().get(), pid, topN);
                    for (ClassHistogramItem item : hist.topClasses()) {
                        System.out.printf("%-6d %-14d %-14.2f %s%n",
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

        } catch (Exception e) {
            System.err.println("[jvm-mcp] Error querying memory metrics: " + e.getMessage());
            return 1;
        }
    }
}
