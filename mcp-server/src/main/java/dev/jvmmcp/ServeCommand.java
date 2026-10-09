package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.jmx.MemoryMXBeanClient;
import dev.jvmmcp.core.jmx.ThreadMXBeanClient;
import dev.jvmmcp.core.pg.PostgresSchemaReader;
import dev.jvmmcp.core.spring.ActuatorAuth;
import dev.jvmmcp.core.spring.ActuatorClient;
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
                    Map<String, Object> errResp = new LinkedHashMap<>();
                    errResp.put("jsonrpc", "2.0");
                    errResp.put("id", null);
                    errResp.put("error", Map.of("code", -32700, "message", "Parse error: " + e.getMessage()));
                    sendResponse(errResp);
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
        if (id == null) return; // JSON-RPC 2.0: do not respond to notifications

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        try {
            String method = (String) req.get("method");

            if ("initialize".equals(method)) {
                Map<String, Object> params = (Map<String, Object>) req.get("params");
                response.put("result", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of("tools", Map.of()),
                    "serverInfo", Map.of("name", "jvm-mcp", "version", JvmMcp.VERSION)
                ));
                sendResponse(response);
                return;
            }

            if ("ping".equals(method)) {
                response.put("result", Map.of());
                sendResponse(response);
                return;
            }

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
                } else {
                    response.put("error", Map.of("code", -32602, "message", "Invalid params"));
                    sendResponse(response);
                    return;
                }
            }

            response.put("error", Map.of("code", -32601, "message", "Method not found"));
            sendResponse(response);
        } catch (Exception e) {
            System.err.println("[jvm-mcp] Internal error handling request: " + e.getMessage());
            response.put("error", Map.of("code", -32603, "message", "Internal error: " + e.getMessage()));
            sendResponse(response);
        }
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
            ),
            Map.of(
                "name", "get_heap_histogram",
                "description", "Retrieves a histogram of class instances in the JVM heap",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pid", Map.of("type", "number", "description", "Target JVM Process ID"),
                        "limit", Map.of("type", "number", "description", "Limit of classes to return (default 100)")
                    ),
                    "required", List.of("pid")
                )
            ),
            Map.of(
                "name", "get_thread_dump",
                "description", "Retrieves a full thread dump from the target JVM",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of("pid", Map.of("type", "number", "description", "Target JVM Process ID")),
                    "required", List.of("pid")
                )
            ),
            Map.of(
                "name", "inspect_pg_schema",
                "description", "Inspects PostgreSQL schema, tables, indexes, and foreign keys",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "JDBC URL (e.g. jdbc:postgresql://localhost:5432/db)"),
                        "schema", Map.of("type", "string", "description", "Target schema (default: public)")
                    ),
                    "required", List.of("url")
                )
            ),
            Map.of(
                "name", "find_missing_indexes",
                "description", "Finds missing indexes by analyzing seq_scan and idx_scan metrics",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "JDBC URL (e.g. jdbc:postgresql://localhost:5432/db)"),
                        ),
                    "required", List.of("url")
                )
            ),
            Map.of(
                "name", "find_slow_queries",
                "description", "Finds slow queries using pg_stat_statements",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "JDBC URL (e.g. jdbc:postgresql://localhost:5432/db)"),
                        ),
                    "required", List.of("url")
                )
            ),
            Map.of(
                "name", "get_actuator_health",
                "description", "Fetches Spring Boot Actuator /health endpoint",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "Actuator Base URL (e.g. http://localhost:8080)"),
                        ),
                    "required", List.of("url")
                )
            ),
            Map.of(
                "name", "get_actuator_metrics",
                "description", "Fetches Spring Boot Actuator /metrics endpoint. Optionally pass metricName to get a specific metric.",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "Actuator Base URL (e.g. http://localhost:8080)"),
                        "metrics", Map.of("type", "string", "description", "Optional comma-separated list of metric names (e.g. jvm.memory.used,jvm.threads.live)"),
                        ),
                    "required", List.of("url")
                )
            ),
            Map.of(
                "name", "get_actuator_startup",
                "description", "Fetches Spring Boot Actuator /startup endpoint",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "url", Map.of("type", "string", "description", "Actuator Base URL (e.g. http://localhost:8080)"),
                        ),
                    "required", List.of("url")
                )
            ),
            Map.of(
                "name", "list_spring_beans",
                "description", "Lists instantiated Spring Beans in the ApplicationContext",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pid", Map.of("type", "number", "description", "Target JVM Process ID"),
                        "filter", Map.of("type", "string", "description", "Optional glob filter (e.g. *Service*)")
                    ),
                    "required", List.of("pid")
                )
            ),
            Map.of(
                "name", "get_bean_detail",
                "description", "Gets detailed information about a specific Spring Bean",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pid", Map.of("type", "number", "description", "Target JVM Process ID"),
                        "beanName", Map.of("type", "string", "description", "Exact bean name")
                    ),
                    "required", List.of("pid", "beanName")
                )
            ),
            Map.of(
                "name", "list_hikari_pools",
                "description", "Lists active HikariCP connection pools, their metrics, and heuristically analyzes their health for starvation or Clock Leaps.",
                "inputSchema", Map.of(
                    "type", "object",
                    "properties", Map.of(
                        "pid", Map.of("type", "number", "description", "Target JVM Process ID")
                    ),
                    "required", List.of("pid")
                )
            )
        );
    }

    private String getStringArg(Map<String, Object> args, String key, boolean required) {
        if (args == null || !args.containsKey(key)) {
            if (required) throw new IllegalArgumentException("Missing required argument: " + key);
            return null;
        }
        Object val = args.get(key);
        if (val != null && !(val instanceof String)) {
            throw new IllegalArgumentException("Argument '" + key + "' must be a string");
        }
        return (String) val;
    }

    private long getLongArg(Map<String, Object> args, String key, boolean required) {
        if (args == null || !args.containsKey(key)) {
            if (required) throw new IllegalArgumentException("Missing required argument: " + key);
            return 0L;
        }
        Object val = args.get(key);
        if (!(val instanceof Number)) {
            throw new IllegalArgumentException("Argument '" + key + "' must be a number");
        }
        return ((Number) val).longValue();
    }

    private Map<String, Object> executeTool(String toolName, Map<String, Object> args) {
        List<Map<String, Object>> content = new ArrayList<>();
        boolean isError = false;

        try {
            if ("list_jvms".equals(toolName)) {
                content.add(Map.of("type", "text", "text", SimpleJson.toJson(attachService.listJvms())));
            } else if ("get_memory_summary".equals(toolName) || 
                       "get_heap_histogram".equals(toolName) || 
                       "get_thread_diagnostics".equals(toolName) || 
                       "get_thread_dump".equals(toolName) || 
                       "list_spring_beans".equals(toolName) || 
                       "get_bean_detail".equals(toolName) || 
                       "list_hikari_pools".equals(toolName)) {
                long pid = getLongArg(args, "pid", true);
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
                    } else if ("get_heap_histogram".equals(toolName)) {
                        dev.jvmmcp.core.jmx.HeapHistogramReader histoReader = new dev.jvmmcp.core.jmx.HeapHistogramReader();
                        long limit = args != null && args.containsKey("limit") ? getLongArg(args, "limit", false) : 100L;
                        if (limit <= 0) limit = 100L;
                        if (limit > 1000) limit = 1000L;
                        content.add(Map.of("type", "text", "text", SimpleJson.toJson(histoReader.readHistogram(jmxManager.getMBeanServerConnection(), pid, (int) limit))));
                    } else if ("get_thread_diagnostics".equals(toolName)) {
                        ThreadMXBeanClient threadClient = new ThreadMXBeanClient(jmxManager.getMBeanServerConnection());
                        content.add(Map.of("type", "text", "text", SimpleJson.toJson(threadClient.getThreadSummary(pid))));
                        content.add(Map.of("type", "text", "text", "Deadlock Report: " + SimpleJson.toJson(threadClient.detectDeadlocks())));
                    } else if ("get_thread_dump".equals(toolName)) {
                        ThreadMXBeanClient threadClient = new ThreadMXBeanClient(jmxManager.getMBeanServerConnection());
                        content.add(Map.of("type", "text", "text", SimpleJson.toJson(threadClient.getThreadDump(pid, false, false))));
                    } else if ("list_spring_beans".equals(toolName) || "get_bean_detail".equals(toolName)) {
                        dev.jvmmcp.core.spring.SpringBeansClient beansClient = new dev.jvmmcp.core.spring.SpringBeansClient();
                        String filterGlob = getStringArg(args, "filter", false);
                        dev.jvmmcp.core.model.SpringBeansReport report = beansClient.inspectBeans(pid, attachResult.virtualMachine().orElse(null), jmxManager.getMBeanServerConnection(), filterGlob);
                        
                        if ("list_spring_beans".equals(toolName)) {
                            content.add(Map.of("type", "text", "text", SimpleJson.toJson(report)));
                        } else {
                            String beanName = getStringArg(args, "beanName", true);
                            content.add(Map.of("type", "text", "text", SimpleJson.toJson(beansClient.getBeanDetail(report, beanName).orElse(null))));
                        }
                    } else if ("list_hikari_pools".equals(toolName)) {
                        dev.jvmmcp.core.jmx.HikariMXBeanClient hikariClient = new dev.jvmmcp.core.jmx.HikariMXBeanClient(jmxManager.getMBeanServerConnection());
                        content.add(Map.of("type", "text", "text", SimpleJson.toJson(hikariClient.getPools())));
                    }
                }
            } else if ("inspect_pg_schema".equals(toolName) || "find_missing_indexes".equals(toolName) || "find_slow_queries".equals(toolName)) {
                String url = getStringArg(args, "url", true);
                if (!url.startsWith("jdbc:postgresql://localhost:") && !url.startsWith("jdbc:postgresql://localhost/") && !url.startsWith("jdbc:postgresql://127.0.0.1:") && !url.startsWith("jdbc:postgresql://127.0.0.1/")) {
                    throw new IllegalArgumentException("SSRF Protection: JDBC URL must target localhost (got: " + url + ")");
                }
                String user = System.getenv("PG_USER");
                String password = System.getenv("PG_PASSWORD");
                PostgresSchemaReader reader = new PostgresSchemaReader(url, user, password);

                if ("inspect_pg_schema".equals(toolName)) {
                    String schema = getStringArg(args, "schema", false);
                    if (schema == null) schema = "public";
                    content.add(Map.of("type", "text", "text", SimpleJson.toJson(reader.inspectSchema(schema))));
                } else if ("find_missing_indexes".equals(toolName)) {
                    String schema = getStringArg(args, "schema", false);
                    if (schema == null) schema = "public";
                    content.add(Map.of("type", "text", "text", SimpleJson.toJson(reader.findMissingIndexes(schema))));
                } else {
                    content.add(Map.of("type", "text", "text", SimpleJson.toJson(reader.findSlowQueries())));
                }
            } else if (toolName.startsWith("get_actuator_")) {
                String url = getStringArg(args, "url", true);
                String user = System.getenv("ACTUATOR_USER");
                String password = System.getenv("ACTUATOR_PASSWORD");
                String token = System.getenv("ACTUATOR_TOKEN");
                boolean insecure = "true".equalsIgnoreCase(System.getenv("ACTUATOR_INSECURE"));

                ActuatorAuth auth = token != null ? ActuatorAuth.bearer(token, insecure) : ActuatorAuth.basic(user, password, insecure);
                auth.validate().ifPresent(error -> { throw new IllegalArgumentException(error); });

                ActuatorClient client = new ActuatorClient(url, auth);

                if ("get_actuator_health".equals(toolName)) {
                    content.add(Map.of("type", "text", "text", client.getHealth()));
                } else if ("get_actuator_metrics".equals(toolName)) {
                    String metricsParam = getStringArg(args, "metrics", false);
                    if (metricsParam != null && !metricsParam.isBlank()) {
                        if (metricsParam.contains(",")) {
                            content.add(Map.of("type", "text", "text", client.getMetricsBatch(java.util.Arrays.asList(metricsParam.split(",")))));
                        } else {
                            content.add(Map.of("type", "text", "text", client.getMetric(metricsParam)));
                        }
                    } else {
                        content.add(Map.of("type", "text", "text", client.getMetrics()));
                    }
                } else if ("get_actuator_startup".equals(toolName)) {
                    content.add(Map.of("type", "text", "text", client.getStartup()));
                } else {
                    throw new IllegalArgumentException("Unknown actuator tool: " + toolName);
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


