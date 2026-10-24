package dev.jvmmcp.server.transport;

import dev.jvmmcp.server.protocol.McpDispatcher;
import dev.jvmmcp.server.tool.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class McpTransportTest {

    @Test
    @DisplayName("start processes lines and writes responses to output stream")
    void testStartProcessingLines() {
        ToolRegistry registry = new ToolRegistry();
        McpDispatcher dispatcher = new McpDispatcher(registry, "test-mcp", "1.0.0");
        McpTransport transport = new McpTransport(dispatcher);

        String input = "\n   \n{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n";
        ByteArrayInputStream in = new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = transport.start(in, out);
        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8).trim()).contains("\"result\":{}");
    }

    @Test
    @DisplayName("start with default System.out")
    void testStartDefaultSystemOut() {
        ToolRegistry registry = new ToolRegistry();
        McpDispatcher dispatcher = new McpDispatcher(registry);
        McpTransport transport = new McpTransport(dispatcher);

        ByteArrayInputStream in = new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out));
            int exitCode = transport.start(in);
            assertThat(exitCode).isZero();
            assertThat(out.toString().trim()).contains("\"result\":{}");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("handles exception in worker thread when dispatcher throws unexpected error")
    void testWorkerThreadCatchesException() {
        ToolRegistry registry = new ToolRegistry();
        McpDispatcher dispatcher = new McpDispatcher(registry) {
            @Override
            public Optional<Map<String, Object>> dispatch(String line) {
                throw new RuntimeException("Unexpected dispatcher fault");
            }
        };
        McpTransport transport = new McpTransport(dispatcher);

        ByteArrayInputStream in = new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = transport.start(in, (java.io.OutputStream) out);
        assertThat(exitCode).isZero();
        assertThat(out.toString().trim()).contains("-32700");
    }

    @Test
    @DisplayName("start returns 1 when input stream throws fatal error")
    void testStartFatalError() {
        ToolRegistry registry = new ToolRegistry();
        McpDispatcher dispatcher = new McpDispatcher(registry);
        McpTransport transport = new McpTransport(dispatcher);

        InputStream failingIn = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("Stream failed fatally");
            }
        };

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int exitCode = transport.start(failingIn, out);
        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("sendResponse ignores null response map")
    void testSendResponseNull() {
        ToolRegistry registry = new ToolRegistry();
        McpDispatcher dispatcher = new McpDispatcher(registry);
        McpTransport transport = new McpTransport(dispatcher);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        java.io.PrintStream printStream = new java.io.PrintStream(out);
        transport.sendResponse(printStream, null);
        assertThat(out.toString()).isEmpty();
    }

    @Test
    @DisplayName("rejects oversized lines exceeding maximum byte threshold")
    void testRejectsOversizedLines() {
        ToolRegistry registry = new ToolRegistry();
        McpDispatcher dispatcher = new McpDispatcher(registry);
        McpTransport transport = new McpTransport(dispatcher);

        // Build a line exceeding 4MB
        String largePadding = "a".repeat(4 * 1024 * 1024 + 10);
        ByteArrayInputStream in = new ByteArrayInputStream((largePadding + "\n").getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        int exitCode = transport.start(in, out);
        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("-32700");
    }

    @Test
    @DisplayName("start wraps non-UTF8 PrintStream to force UTF-8 output")
    void testStartForcesUtf8PrintStream() {
        ToolRegistry registry = new ToolRegistry();
        McpDispatcher dispatcher = new McpDispatcher(registry);
        McpTransport transport = new McpTransport(dispatcher);

        ByteArrayInputStream in = new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}\n".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream isoStream = new PrintStream(out, true, java.nio.charset.StandardCharsets.ISO_8859_1);

        int exitCode = transport.start(in, (java.io.OutputStream) isoStream);
        assertThat(exitCode).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("\"result\":{}");
    }
}

