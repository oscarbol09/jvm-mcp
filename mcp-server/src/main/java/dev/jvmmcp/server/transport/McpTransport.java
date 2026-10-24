package dev.jvmmcp.server.transport;

import dev.jvmmcp.core.util.SimpleJson;
import dev.jvmmcp.server.protocol.McpDispatcher;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Manages stdio transport, line reading loop, virtual-thread concurrency,
 * synchronized response writing, and graceful termination.
 */
public class McpTransport {

    private final McpDispatcher dispatcher;
    private final ExecutorService customExecutor;
    private final Object responseLock = new Object();

    public McpTransport(McpDispatcher dispatcher) {
        this(dispatcher, null);
    }

    public McpTransport(McpDispatcher dispatcher, ExecutorService customExecutor) {
        this.dispatcher = dispatcher;
        this.customExecutor = customExecutor;
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
        ExecutorService executor = customExecutor != null ? customExecutor : Executors.newVirtualThreadPerTaskExecutor();
        try {
            try (BoundedLineReader reader = new BoundedLineReader(in, MAX_REQUEST_BYTES)) {
                while (true) {
                    String line;
                    try {
                        line = reader.readLine();
                    } catch (LineTooLargeException e) {
                        System.err.println("[jvm-mcp] Request rejected: " + e.getMessage());
                        sendResponse(out, dispatcher.formatParseError("Request exceeds maximum size limit of " + MAX_REQUEST_BYTES + " bytes"));
                        continue;
                    }

                    if (line == null) {
                        break;
                    }
                    if (line.trim().isEmpty()) {
                        continue;
                    }
                    final String requestLine = line;
                    executor.submit(() -> {
                        try {
                            Optional<Map<String, Object>> response = dispatcher.dispatch(requestLine);
                            response.ifPresent(resp -> sendResponse(out, resp));
                        } catch (Exception e) {
                            System.err.println("[jvm-mcp] Error processing request: " + e.getMessage());
                            sendResponse(out, dispatcher.formatParseError(e.getMessage()));
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
                    if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
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
        synchronized (responseLock) {
            out.println(SimpleJson.toJson(response));
            out.flush();
        }
    }
}
