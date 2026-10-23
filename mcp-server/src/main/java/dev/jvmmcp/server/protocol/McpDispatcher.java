package dev.jvmmcp.server.protocol;

import dev.jvmmcp.JvmMcp;
import dev.jvmmcp.core.util.SimpleJson;
import dev.jvmmcp.server.tool.ToolArgs;
import dev.jvmmcp.server.tool.ToolRegistry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Handles JSON-RPC 2.0 protocol dispatching for MCP server.
 */
public class McpDispatcher {

    public static final String PROTOCOL_VERSION = "2024-11-05";

    private final ToolRegistry toolRegistry;
    private final String serverName;
    private final String serverVersion;

    public McpDispatcher(ToolRegistry toolRegistry) {
        this(toolRegistry, "jvm-mcp", JvmMcp.getVersion());
    }

    public McpDispatcher(ToolRegistry toolRegistry, String serverName, String serverVersion) {
        this.toolRegistry = toolRegistry;
        this.serverName = serverName;
        this.serverVersion = serverVersion;
    }

    /**
     * Parses a raw line of input and dispatches it as a JSON-RPC request.
     *
     * @param requestLine raw JSON string
     * @return response map if a response is expected, or empty for notifications
     */
    public Optional<Map<String, Object>> dispatch(String requestLine) {
        try {
            Map<String, Object> req = SimpleJson.parseObject(requestLine);
            return handleRequest(req);
        } catch (Exception e) {
            System.err.println("[jvm-mcp] Error processing request: " + e.getMessage());
            return Optional.of(formatParseError(e.getMessage()));
        }
    }

    /**
     * Dispatches an already-parsed JSON-RPC request map.
     *
     * @param req request map
     * @return response map if a response is expected, or empty for notifications
     */
    public Optional<Map<String, Object>> handleRequest(Map<String, Object> req) {
        if (req == null) {
            return Optional.empty();
        }

        // 1. Validate JSON-RPC version
        Object jsonrpc = req.get("jsonrpc");
        if (!"2.0".equals(jsonrpc)) {
            return Optional.of(formatInvalidRequest(req.get("id"), "Invalid Request: 'jsonrpc' must be exactly '2.0'"));
        }

        // 2. Validate method
        Object methodObj = req.get("method");
        if (!(methodObj instanceof String method) || method.isBlank()) {
            return Optional.of(formatInvalidRequest(req.get("id"), "Invalid Request: 'method' must be a non-empty string"));
        }

        // 3. Validate id type
        if (req.containsKey("id")) {
            Object id = req.get("id");
            if (id != null && !(id instanceof String) && !(id instanceof Number)) {
                return Optional.of(formatInvalidRequest(null, "Invalid Request: 'id' must be a String, Number, or null"));
            }
        }

        // 4. Distinguish notifications from requests
        boolean isNotification = !req.containsKey("id");
        if (isNotification) {
            try {
                processMethod(method, req);
            } catch (Exception e) {
                System.err.println("[jvm-mcp] Error processing notification: " + e.getMessage());
            }
            return Optional.empty();
        }

        Object id = req.get("id");
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);

        try {
            Map<String, Object> result = processMethod(method, req);
            if (result != null && result.containsKey("error")) {
                response.put("error", result.get("error"));
            } else {
                response.put("result", result != null ? result : Map.of());
            }
            return Optional.of(response);
        } catch (Exception e) {
            String errorRef = java.util.UUID.randomUUID().toString().substring(0, 8);
            System.err.println("[jvm-mcp] [err-" + errorRef + "] Internal error handling request: " + e.getMessage());
            response.put("error", Map.of(
                "code", -32603,
                "message", "Internal error (ref: " + errorRef + ")"
            ));
            return Optional.of(response);
        }
    }

    private Map<String, Object> processMethod(String method, Map<String, Object> req) {
        if ("initialize".equals(method)) {
            return Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of("tools", Map.of()),
                "serverInfo", Map.of("name", serverName, "version", serverVersion)
            );
        }

        if ("ping".equals(method)) {
            return Map.of();
        }

        if ("tools/list".equals(method)) {
            return Map.of("tools", toolRegistry.getToolsList());
        }

        if ("tools/call".equals(method)) {
            Map<String, Object> params = ToolArgs.asMap(req.get("params"));
            if (params != null && params.get("name") instanceof String toolName) {
                Map<String, Object> args = ToolArgs.asMap(params.get("arguments"));
                return toolRegistry.execute(toolName, args != null ? args : Map.of());
            } else {
                return Map.of("error", Map.of("code", -32602, "message", "Invalid params"));
            }
        }

        return Map.of("error", Map.of("code", -32601, "message", "Method not found"));
    }

    public Map<String, Object> formatParseError(String message) {
        Map<String, Object> errResp = new LinkedHashMap<>();
        errResp.put("jsonrpc", "2.0");
        errResp.put("id", null);
        errResp.put("error", Map.of("code", -32700, "message", "Parse error: " + message));
        return errResp;
    }

    public Map<String, Object> formatInvalidRequest(Object id, String message) {
        Map<String, Object> errResp = new LinkedHashMap<>();
        errResp.put("jsonrpc", "2.0");
        errResp.put("id", id);
        errResp.put("error", Map.of("code", -32600, "message", message != null ? message : "Invalid Request"));
        return errResp;
    }

    public Map<String, Object> formatMethodNotFound(Object id) {
        Map<String, Object> errResp = new LinkedHashMap<>();
        errResp.put("jsonrpc", "2.0");
        errResp.put("id", id);
        errResp.put("error", Map.of("code", -32601, "message", "Method not found"));
        return errResp;
    }

    public Map<String, Object> formatInvalidParams(Object id, String message) {
        Map<String, Object> errResp = new LinkedHashMap<>();
        errResp.put("jsonrpc", "2.0");
        errResp.put("id", id);
        errResp.put("error", Map.of("code", -32602, "message", message != null ? message : "Invalid params"));
        return errResp;
    }

    public Map<String, Object> formatInternalError(Object id, String message) {
        Map<String, Object> errResp = new LinkedHashMap<>();
        errResp.put("jsonrpc", "2.0");
        errResp.put("id", id);
        errResp.put("error", Map.of("code", -32603, "message", message != null ? message : "Internal error"));
        return errResp;
    }
}
