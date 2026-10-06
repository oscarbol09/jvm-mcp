package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.jmx.MemoryMXBeanClient;
import dev.jvmmcp.core.jmx.ThreadMXBeanClient;
import dev.jvmmcp.core.util.SimpleJson;
import com.sun.tools.attach.VirtualMachine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "serve", description = "Starts the JVM-MCP server", mixinStandardHelpOptions = true)
public class ServeCommand implements Callable<Integer> {

    @Option(names = "--transport", defaultValue = "stdio", description = "Transport protocol: stdio or sse")
    String transport;

    @Option(names = "--spring", description = "Use Spring AI layer (SSE, dashboard, slower startup)")
    boolean useSpring;

    @Option(names = "--attach", description = "Target PID to attach explicitly before serving")
    Integer targetPid;

    @Option(names = "--actuator", description = "Target Actuator Base URL (e.g. http://localhost:8080)")
    String actuatorUrl;

    private final JvmAttachService attachService;

    public ServeCommand() {
        this.attachService = new JvmAttachService();
    }

    public ServeCommand(JvmAttachService attachService) {
        this.attachService = attachService;
    }

    // Visible for testing
    static java.io.InputStream testInStream = null;
    static boolean exitImmediatelyForTest = false;

    @Override
    public Integer call() {
        if ("sse".equalsIgnoreCase(transport) || useSpring) {
            System.err.println("[jvm-mcp] Spring layer not yet implemented. Please use stdio transport.");
            return 1;
        }

        VirtualMachine attachedVm = null;
        if (targetPid != null) {
            AttachResult result = attachService.attach(String.valueOf(targetPid));
            if (!result.isSuccessful()) {
                System.err.println("[jvm-mcp] Error attaching to target PID " + targetPid + ": " + result.message());
                return 1;
            }
            attachedVm = result.virtualMachine().orElse(null);
            System.err.println("[jvm-mcp] Successfully attached to target PID " + targetPid);
        }

        System.err.println("[jvm-mcp] Starting server via transport: " + transport + "...");

        final VirtualMachine finalVm = attachedVm;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.err.println("[jvm-mcp] Received shutdown signal. Detaching and cleaning up...");
            if (finalVm != null) {
                try {
                    attachService.detach(finalVm);
                    System.err.println("[jvm-mcp] Detached from PID " + targetPid);
                } catch (Exception e) {
                    System.err.println("[jvm-mcp] Error detaching during shutdown: " + e.getMessage());
                }
            }
        }));

        if (exitImmediatelyForTest) {
            return 0;
        }

        java.io.InputStream in = testInStream != null ? testInStream : System.in;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                try {
                    Map<String, Object> req = SimpleJson.parseObject(line);
                    handleRequest(req);
                } catch (Exception e) {
                    System.err.println("[jvm-mcp] Error processing request: " + e.getMessage());
                }
            }
        } catch (Exception e) {
            System.err.println("[jvm-mcp] Fatal server error: " + e.getMessage());
            return 1;
        }

        return 0;
    }

    private void handleRequest(Map<String, Object> req) {
        Object id = req.get("id");
        String method = (String) req.get("method");

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        if (id != null) response.put("id", id);

        if ("tools/list".equals(method)) {
            response.put("result", Map.of("tools", getToolsList()));
            sendResponse(response);
            return;
        }

        if ("tools/call".equals(method)) {
            Map<String, Object> params = (Map<String, Object>) req.get("params");
            if (params != null) {
                String toolName = (String) params.get("name");
                Map<String, Object> args = (Map<String, Object>) params.get("arguments");
                response.put("result", executeTool(toolName, args));
                sendResponse(response);
                return;
            }
        }

        response.put("error", Map.of("code", -32601, "message", "Method not found"));
        sendResponse(response);
    }

    private List<Map<String, Object>> getToolsList() {
        return List.of(
            Map.of(
                "name", "list_jvms",
                "description", "Lists all running Java Virtual Machines on the host",
                "inputSchema", Map.of("type", "object", "properties", Map.of(), "required", List.of())
            ),
            Map.of(
                "name", "get_memory_summary",
                "description", "Inspects JVM heap memory, pool regions, GC metrics, and allocation pressure",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of("pid", Map.of("type", "number", "description", "Target JVM Process ID")),
                    "required", List.of("pid")
                )
            ),
            Map.of(
                "name", "get_thread_diagnostics",
                "description", "Inspects JVM thread states and detects deadlocks",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of("pid", Map.of("type", "number", "description", "Target JVM Process ID")),
                    "required", List.of("pid")
                )
            )
        );
    }

    private Map<String, Object> executeTool(String toolName, Map<String, Object> args) {
        List<Map<String, Object>> content = new ArrayList<>();
        boolean isError = false;

        try {
            if ("list_jvms".equals(toolName)) {
                content.add(Map.of("type", "text", "text", SimpleJson.toJson(attachService.listJvms())));
            } else if ("get_memory_summary".equals(toolName) || "get_thread_diagnostics".equals(toolName)) {
                if (args == null || !args.containsKey("pid")) {
                    throw new IllegalArgumentException("Missing required argument: pid");
                }
                long pid = ((Number) args.get("pid")).longValue();
                AttachResult attachResult = attachService.attach(String.valueOf(pid));
                
                if (!attachResult.isSuccessful()) {
                    throw new IllegalStateException(attachResult.message());
                }
                
                try (JmxConnectionManager jmxManager = attachResult.virtualMachine().isPresent() 
                        ? JmxConnectionManager.connect(attachResult.virtualMachine().get(), pid) 
                        : JmxConnectionManager.connectLocal()) {
                        
                    if ("get_memory_summary".equals(toolName)) {
                        MemoryMXBeanClient memoryClient = new MemoryMXBeanClient(jmxManager.getMBeanServerConnection());
                        content.add(Map.of("type", "text", "text", SimpleJson.toJson(memoryClient.getHeapSummary(pid))));
                    } else {
                        ThreadMXBeanClient threadClient = new ThreadMXBeanClient(jmxManager.getMBeanServerConnection());
                        content.add(Map.of("type", "text", "text", SimpleJson.toJson(threadClient.getThreadSummary(pid))));
                        content.add(Map.of("type", "text", "text", "Deadlock Report: " + SimpleJson.toJson(threadClient.detectDeadlocks())));
                    }
                }
            } else {
                isError = true;
                content.add(Map.of("type", "text", "text", "Unknown tool: " + toolName));
            }
        } catch (Exception e) {
            isError = true;
            content.add(Map.of("type", "text", "text", "Error executing tool: " + e.getMessage()));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("content", content);
        if (isError) result.put("isError", true);
        return result;
    }

    private void sendResponse(Map<String, Object> response) {
        System.out.println(SimpleJson.toJson(response));
        System.out.flush();
    }
}
