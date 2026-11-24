package dev.jvmmcp.server.transport;

import dev.jvmmcp.core.util.SimpleJson;
import dev.jvmmcp.server.protocol.McpDispatcher;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages stdio transport, line reading loop, virtual-thread concurrency, synchronized response
 * writing, and graceful termination.
 */
public class McpTransport {

    private final McpDispatcher dispatcher;
    private final ExecutorService customExecutor;
    private final Duration shutdownTimeout;
    private final Object responseLock = new Object();

    public McpTransport(McpDispatcher dispatcher) {
        this(dispatcher, null);
    }

    public McpTransport(McpDispatcher dispatcher, ExecutorService customExecutor) {
        this.dispatcher = dispatcher;
        this.customExecutor = customExecutor;
        this.shutdownTimeout = dispatcher.getRequestTimeout().plusSeconds(5);
    }

    public int start(InputStream in) {
        return start(in, System.out);
    }

    public int start(InputStream in, OutputStream out) {
        PrintStream printStream;
        if (out instanceof PrintStream ps && StandardCharsets.UTF_8.equals(ps.charset())) {
            printStream = ps;
        } else {
            printStream = new PrintStream(out, true, StandardCharsets.UTF_8);
        }
        return start(in, printStream);
    }

    public static final int MAX_REQUEST_BYTES = 4 * 1024 * 1024; // 4MB

    public int start(InputStream in, PrintStream out) {
        ExecutorService executor =
                customExecutor != null
                        ? customExecutor
                        : Executors.newVirtualThreadPerTaskExecutor();
        try {
            try (BoundedLineReader reader = new BoundedLineReader(in, MAX_REQUEST_BYTES)) {
                while (true) {
                    String line;
                    try {
                        line = reader.readLine();
                    } catch (LineTooLargeException e) {
                        System.err.println("[jvm-mcp] Request rejected: " + e.getMessage());
                        sendResponse(
                                out,
                                dispatcher.formatParseError(
                                        "Request exceeds maximum size limit of "
                                                + MAX_REQUEST_BYTES
                                                + " bytes"));
                        continue;
                    }

                    if (line == null) {
                        break;
                    }
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    final String requestLine = line;
                    executor.submit(
                            () -> {
                                try {
                                    Optional<Map<String, Object>> response =
                                            dispatcher.dispatch(requestLine);
                                    response.ifPresent(resp -> sendResponse(out, resp));
                                } catch (Exception e) {
                                    System.err.println(
                                            "[jvm-mcp] Error processing request: "
                                                    + e.getMessage());
                                    sendResponse(out, recoverFailure(requestLine));
                                }
                            });
                }
            }
        } catch (Exception e) {
            System.err.println("[jvm-mcp] Fatal server error: " + e.getMessage());
            return 1;
        } finally {
            if (customExecutor == null) {
                executor.shutdown();
                try {
                    if (!executor.awaitTermination(
                            shutdownTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                        executor.shutdownNow();
                    }
                } catch (InterruptedException ie) {
                    executor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }
        }

        return 0;
    }

    public void sendResponse(PrintStream out, Map<String, Object> response) {
        if (response == null) {
            return;
        }
        String frame;
        try {
            frame = SimpleJson.toJson(response);
        } catch (Exception e) {
            System.err.println("[jvm-mcp] Failed to serialize response");
            frame = SimpleJson.toJson(internalError(validId(response.get("id"))));
        }
        try {
            synchronized (responseLock) {
                out.println(frame);
                out.flush();
                if (out.checkError()) {
                    System.err.println("[jvm-mcp] Failed to write response");
                }
            }
        } catch (Exception e) {
            System.err.println("[jvm-mcp] Failed to write response");
        }
    }

    private Map<String, Object> recoverFailure(String line) {
        Object parsed;
        try {
            parsed = SimpleJson.parse(line);
        } catch (IllegalArgumentException e) {
            return dispatcher.formatParseError("Malformed JSON");
        }
        if (!(parsed instanceof Map<?, ?> request)) {
            return dispatcher.formatInvalidRequest(null, "Invalid Request");
        }
        Object id = request.get("id");
        if (id != null && !(id instanceof String) && !(id instanceof Number)) {
            return dispatcher.formatInvalidRequest(null, "Invalid Request");
        }
        if (!"2.0".equals(request.get("jsonrpc"))
                || !(request.get("method") instanceof String method)
                || method.isBlank()) {
            return dispatcher.formatInvalidRequest(id, "Invalid Request");
        }
        return request.containsKey("id") ? internalError(id) : null;
    }

    private static Object validId(Object id) {
        return id instanceof String || id instanceof Number ? id : null;
    }

    private static Map<String, Object> internalError(Object id) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", -32603, "message", "Internal error"));
        return response;
    }
}
