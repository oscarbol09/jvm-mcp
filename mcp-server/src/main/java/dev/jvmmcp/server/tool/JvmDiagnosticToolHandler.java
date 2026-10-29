package dev.jvmmcp.server.tool;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.HeapHistogramReader;
import dev.jvmmcp.core.jmx.HikariMXBeanClient;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.jmx.MemoryMXBeanClient;
import dev.jvmmcp.core.jmx.ThreadMXBeanClient;
import dev.jvmmcp.core.util.SimpleJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Handles JVM diagnostic tools: list_jvms, get_memory_summary, get_heap_histogram,
 * get_thread_diagnostics, get_thread_dump, list_hikari_pools.
 */
public class JvmDiagnosticToolHandler implements ToolHandler {

    @FunctionalInterface
    public interface JmxConnector {
        JmxConnectionManager connect(VirtualMachine vm, long pid) throws Exception;
    }

    private final JvmAttachService attachService;
    private final JmxConnector jmxConnector;

    public JvmDiagnosticToolHandler() {
        this(new JvmAttachService());
    }

    public JvmDiagnosticToolHandler(JvmAttachService attachService) {
        this(
                attachService,
                (vm, pid) ->
                        vm != null
                                ? JmxConnectionManager.connect(vm, pid)
                                : JmxConnectionManager.connectLocal());
    }

    public JvmDiagnosticToolHandler(JvmAttachService attachService, JmxConnector jmxConnector) {
        this.attachService = attachService;
        this.jmxConnector = jmxConnector;
    }

    @Override
    public List<String> toolNames() {
        return List.of(
                "list_jvms",
                "get_memory_summary",
                "get_thread_diagnostics",
                "get_heap_histogram",
                "get_thread_dump",
                "list_hikari_pools");
    }

    @Override
    public List<Map<String, Object>> toolDefinitions() {
        return List.of(
                Map.of(
                        "name", "list_jvms",
                        "description", "Lists all running Java Virtual Machines on the host",
                        "inputSchema",
                                Map.of(
                                        "type",
                                        "object",
                                        "properties",
                                        Map.of(),
                                        "required",
                                        List.of())),
                Map.of(
                        "name", "get_memory_summary",
                        "description",
                                "Inspects JVM heap memory, pool regions, GC metrics, and allocation pressure",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "pid",
                                                        Map.of(
                                                                "type",
                                                                "number",
                                                                "description",
                                                                "Target JVM Process ID")),
                                        "required", List.of("pid"))),
                Map.of(
                        "name", "get_thread_diagnostics",
                        "description", "Inspects JVM thread states and detects deadlocks",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "pid",
                                                        Map.of(
                                                                "type",
                                                                "number",
                                                                "description",
                                                                "Target JVM Process ID")),
                                        "required", List.of("pid"))),
                Map.of(
                        "name", "get_heap_histogram",
                        "description", "Retrieves a histogram of class instances in the JVM heap",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "pid",
                                                                Map.of(
                                                                        "type",
                                                                        "number",
                                                                        "description",
                                                                        "Target JVM Process ID"),
                                                        "limit",
                                                                Map.of(
                                                                        "type",
                                                                        "number",
                                                                        "description",
                                                                        "Limit of classes to return (default 100)")),
                                        "required", List.of("pid"))),
                Map.of(
                        "name", "get_thread_dump",
                        "description", "Retrieves a full thread dump from the target JVM",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "pid",
                                                        Map.of(
                                                                "type",
                                                                "number",
                                                                "description",
                                                                "Target JVM Process ID")),
                                        "required", List.of("pid"))),
                Map.of(
                        "name", "list_hikari_pools",
                        "description",
                                "Lists active HikariCP connection pools, their metrics, and heuristically analyzes their health for starvation or Clock Leaps. Returns a note when no HikariCP MBeans are registered.",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "pid",
                                                        Map.of(
                                                                "type",
                                                                "number",
                                                                "description",
                                                                "Target JVM Process ID")),
                                        "required", List.of("pid"))));
    }

    @Override
    public Map<String, Object> execute(String toolName, Map<String, Object> args) throws Exception {
        List<Map<String, Object>> content = new ArrayList<>();

        if ("list_jvms".equals(toolName)) {
            content.add(
                    Map.of("type", "text", "text", SimpleJson.toJson(attachService.listJvms())));
            return Map.of("content", content);
        }

        long pid = ToolArgs.getPositiveLongArg(args, "pid", true);
        AttachResult attachResult = attachService.attach(String.valueOf(pid));
        if (!attachResult.isSuccessful()) {
            throw new IllegalStateException(attachResult.message());
        }

        VirtualMachine vm = attachResult.virtualMachine().orElse(null);
        boolean detached = false;
        try {
            try (JmxConnectionManager jmxManager = jmxConnector.connect(vm, pid)) {
                detached = true;

                if ("get_memory_summary".equals(toolName)) {
                    try (MemoryMXBeanClient memoryClient =
                            new MemoryMXBeanClient(jmxManager.getMBeanServerConnection())) {
                        content.add(
                                Map.of(
                                        "type",
                                        "text",
                                        "text",
                                        SimpleJson.toJson(memoryClient.getHeapSummary(pid))));
                    }
                } else if ("get_heap_histogram".equals(toolName)) {
                    HeapHistogramReader histoReader = new HeapHistogramReader();
                    int limit = ToolArgs.getIntArg(args, "limit", 100, 1, 1000);
                    content.add(
                            Map.of(
                                    "type",
                                    "text",
                                    "text",
                                    SimpleJson.toJson(
                                            histoReader.readHistogram(
                                                    jmxManager.getMBeanServerConnection(),
                                                    pid,
                                                    limit))));
                } else if ("get_thread_diagnostics".equals(toolName)) {
                    try (ThreadMXBeanClient threadClient =
                            new ThreadMXBeanClient(jmxManager.getMBeanServerConnection())) {
                        content.add(
                                Map.of(
                                        "type",
                                        "text",
                                        "text",
                                        SimpleJson.toJson(threadClient.getThreadSummary(pid))));
                        content.add(
                                Map.of(
                                        "type",
                                        "text",
                                        "text",
                                        SimpleJson.toJson(threadClient.detectDeadlocks())));
                    }
                } else if ("get_thread_dump".equals(toolName)) {
                    try (ThreadMXBeanClient threadClient =
                            new ThreadMXBeanClient(jmxManager.getMBeanServerConnection())) {
                        content.add(
                                Map.of(
                                        "type",
                                        "text",
                                        "text",
                                        SimpleJson.toJson(
                                                threadClient.getThreadDump(pid, false, false))));
                    }
                } else if ("list_hikari_pools".equals(toolName)) {
                    try (HikariMXBeanClient hikariClient =
                            new HikariMXBeanClient(jmxManager.getMBeanServerConnection())) {
                        content.add(
                                Map.of(
                                        "type",
                                        "text",
                                        "text",
                                        SimpleJson.toJson(hikariClient.getPools())));
                    }
                } else {
                    throw new IllegalArgumentException("Unknown JVM diagnostic tool: " + toolName);
                }
            }
        } finally {
            if (vm != null && !detached) {
                try {
                    attachService.detach(vm);
                } catch (Exception ignored) {
                }
            }
        }

        return Map.of("content", content);
    }
}
