package dev.jvmmcp.core.jmx;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.model.ClassHistogramItem;
import dev.jvmmcp.core.model.HeapHistogram;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

/**
 * Executes and parses live JVM class histograms ({@code GC.class_histogram}) over the Attach API,
 * with a JMX DiagnosticCommand fallback for runtimes where {@code executeJCmd} is unavailable.
 */
public class HeapHistogramReader {

    private static final String DIAGNOSTIC_COMMAND_MBEAN = "com.sun.management:type=DiagnosticCommand";

    public HeapHistogram readHistogram(VirtualMachine vm, long pid, int topN) throws Exception {
        if (vm == null) {
            throw new IllegalArgumentException("VirtualMachine cannot be null to read live heap histogram.");
        }

        Method executeJCmdMethod = vm.getClass().getMethod("executeJCmd", String.class);
        try (InputStream in = (InputStream) executeJCmdMethod.invoke(vm, "GC.class_histogram")) {
            return parseHistogramStream(in, pid, topN);
        }
    }

    /**
     * Reads the live heap histogram through the platform
     * {@code com.sun.management:type=DiagnosticCommand} MBean, as a fallback for non-HotSpot
     * JVMs, modular runtimes without {@code --add-opens jdk.attach/sun.tools.attach}, and
     * self-inspection without a {@link VirtualMachine} handle.
     */
    public HeapHistogram readHistogram(MBeanServerConnection mbsc, long pid, int topN) throws Exception {
        if (mbsc == null) {
            throw new IllegalArgumentException("MBeanServerConnection cannot be null to read live heap histogram.");
        }

        Object result = mbsc.invoke(
            new ObjectName(DIAGNOSTIC_COMMAND_MBEAN),
            "gcClassHistogram",
            new Object[]{new String[0]},
            new String[]{String[].class.getName()});

        if (result == null) {
            return new HeapHistogram(pid, 0, 0, 0.0, List.of());
        }

        return parseHistogramStream(
            new ByteArrayInputStream(result.toString().getBytes(StandardCharsets.UTF_8)), pid, topN);
    }

    public HeapHistogram parseHistogramStream(InputStream in, long pid, int topN) throws Exception {
        if (in == null) {
            return new HeapHistogram(pid, 0, 0, 0.0, List.of());
        }

        List<ClassHistogramItem> items = new ArrayList<>();
        long totalInstances = 0;
        long totalBytes = 0;
        long parsedTotalInstances = 0;
        long parsedTotalBytes = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            boolean headerFound = false;

            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("---") || (line.contains("instances") && line.contains("bytes"))) {
                    headerFound = true;
                    continue;
                }
                if (!headerFound || line.isBlank()) {
                    continue;
                }

                if (line.startsWith("Total")) {
                    String[] totalParts = line.split("\\s+");
                    if (totalParts.length >= 3) {
                        try {
                            totalInstances = Long.parseLong(totalParts[1]);
                            totalBytes = Long.parseLong(totalParts[2]);
                        } catch (NumberFormatException ignored) {}
                    }
                    continue;
                }

                String[] parts = line.split("\\s+", 4);
                if (parts.length >= 4) {
                    try {
                        String rankStr = parts[0].replace(":", "");
                        int rank = Integer.parseInt(rankStr);
                        long instances = Long.parseLong(parts[1]);
                        long bytes = Long.parseLong(parts[2]);
                        String className = parts[3];

                        parsedTotalInstances += instances;
                        parsedTotalBytes += bytes;

                        if (topN <= 0 || items.size() < topN) {
                            items.add(ClassHistogramItem.of(rank, instances, bytes, className));
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
        }

        if (totalBytes == 0) {
            totalBytes = parsedTotalBytes;
            totalInstances = parsedTotalInstances;
        }

        double totalMb = totalBytes / (1024.0 * 1024.0);
        return new HeapHistogram(pid, totalInstances, totalBytes, Math.round(totalMb * 100.0) / 100.0, items);
    }
}
