package dev.jvmmcp.server.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import dev.jvmmcp.core.util.SimpleJson;
import dev.jvmmcp.server.protocol.McpDispatcher;
import dev.jvmmcp.server.tool.ToolHandler;
import dev.jvmmcp.server.tool.ToolRegistry;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@Timeout(30)
class McpTransportRecoveryTest {
    @Test
    void reportsBrokenOutputWithoutRetrying() {
        try (ToolRegistry registry = new ToolRegistry()) {
            ByteArrayOutputStream errors = new ByteArrayOutputStream();
            PrintStream original = System.err;
            try {
                System.setErr(new PrintStream(errors));
                PrintStream broken =
                        new PrintStream(
                                new OutputStream() {
                                    @Override
                                    public void write(int value) throws IOException {
                                        throw new IOException("disconnected");
                                    }
                                });
                new McpTransport(new McpDispatcher(registry)).sendResponse(broken, Map.of("id", 1));
                assertThat(errors.toString()).contains("Failed to write response");
                PrintStream throwing = mock(PrintStream.class);
                doThrow(new IllegalStateException()).when(throwing).println(anyString());
                new McpTransport(new McpDispatcher(registry))
                        .sendResponse(throwing, Map.of("id", 2));
                verify(throwing, times(1)).println(anyString());
            } finally {
                System.setErr(original);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "true", "42"})
    void serializationFallbackNormalizesResponseIds(String id) {
        try (ToolRegistry registry = new ToolRegistry()) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", SimpleJson.parse(id));
            response.put("result", response);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new McpTransport(new McpDispatcher(registry))
                    .sendResponse(new PrintStream(out), response);
            assertThat(frames(out)).hasSize(1);
            assertThat(frames(out).getFirst()).containsEntry("id", id.equals("42") ? 42 : null);
            assertThat(frames(out).getFirst().get("error"))
                    .isEqualTo(Map.of("code", -32603, "message", "Internal error"));
        }
    }

    private static ByteArrayInputStream input(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    private static List<Map<String, Object>> frames(ByteArrayOutputStream out) {
        return out.toString(StandardCharsets.UTF_8).lines().map(SimpleJson::parseObject).toList();
    }

    private static ToolHandler tool(long millis) throws Exception {
        ToolHandler handler = mock(ToolHandler.class);
        when(handler.toolNames()).thenReturn(List.of("slow"));
        when(handler.execute(anyString(), anyMap()))
                .thenAnswer(
                        call -> {
                            Thread.sleep(millis);
                            return Map.of("answer", "finished");
                        });
        return handler;
    }

    @Test
    void drainsSlowToolAfterImmediateEof() throws Exception {
        try (ToolRegistry registry = new ToolRegistry()) {
            registry.register(tool(6000));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            assertThat(
                            new McpTransport(new McpDispatcher(registry))
                                    .start(
                                            input(
                                                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"slow\"}}\n"),
                                            out))
                    .isZero();
            assertThat(frames(out)).hasSize(1);
            assertThat(frames(out).getFirst())
                    .containsEntry("id", 7)
                    .containsEntry("result", Map.of("answer", "finished"));
        }
    }

    @Test
    void emitsToolTimeoutBeforeExit() throws Exception {
        try (ToolRegistry registry = new ToolRegistry(Duration.ofMillis(30))) {
            registry.register(tool(10000));
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new McpTransport(new McpDispatcher(registry))
                    .start(
                            input(
                                    "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"slow\"}}"),
                            out);
            assertThat(frames(out)).hasSize(1);
            assertThat(frames(out).getFirst()).containsEntry("id", 7);
            assertThat(out.toString(StandardCharsets.UTF_8))
                    .contains("timed out", "\"isError\":true");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"42", "null", "\"escaped\\\"\\nID\""})
    void correlatesUnexpectedDispatchFailures(String id) {
        try (ToolRegistry registry = new ToolRegistry()) {
            McpDispatcher dispatcher =
                    new McpDispatcher(registry) {
                        @Override
                        public Optional<Map<String, Object>> dispatch(String line) {
                            throw new IllegalStateException("secret-value");
                        }
                    };
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new McpTransport(dispatcher)
                    .start(
                            input("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"ping\"}"),
                            out);
            assertThat(frames(out)).hasSize(1);
            assertThat(frames(out).getFirst()).containsEntry("id", SimpleJson.parse(id));
            assertThat(frames(out).getFirst().get("error"))
                    .isEqualTo(Map.of("code", -32603, "message", "Internal error"));
            assertThat(out.toString()).doesNotContain("secret-value");
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "{",
                "[]",
                "{\"jsonrpc\":\"2.0\",\"id\":true,\"method\":\"ping\"}",
                "{\"id\":4}",
                "{\"jsonrpc\":\"2.0\",\"method\":\"ping\"}"
            })
    void preservesProtocolClassificationWhenDispatchFails(String request) {
        try (ToolRegistry registry = new ToolRegistry()) {
            McpDispatcher normal = new McpDispatcher(registry);
            McpDispatcher failing =
                    new McpDispatcher(registry) {
                        @Override
                        public Optional<Map<String, Object>> dispatch(String line) {
                            throw new IllegalStateException();
                        }
                    };
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new McpTransport(failing).start(input(request), out);
            Optional<Map<String, Object>> expected = normal.dispatch(request);
            if (expected.isEmpty()) {
                assertThat(frames(out)).isEmpty();
            } else {
                assertThat(frames(out)).hasSize(1);
                assertThat(frames(out).getFirst().get("id")).isEqualTo(expected.get().get("id"));
                assertThat(((Map<?, ?>) frames(out).getFirst().get("error")).get("code"))
                        .isEqualTo(((Map<?, ?>) expected.get().get("error")).get("code"));
            }
        }
    }

    @Test
    void serializationFallbackDoesNotBreakSubsequentFrames() {
        try (ToolRegistry registry = new ToolRegistry()) {
            Object nested = "value";
            for (int i = 0; i < 110; i++) nested = List.of(nested);
            Object payload = nested;
            McpDispatcher dispatcher =
                    new McpDispatcher(registry) {
                        @Override
                        public Optional<Map<String, Object>> dispatch(String line) {
                            if (line.contains("deep"))
                                return Optional.of(
                                        Map.of("jsonrpc", "2.0", "id", "deep", "result", payload));
                            return super.dispatch(line);
                        }
                    };
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new McpTransport(dispatcher)
                    .start(
                            input(
                                    "{\"jsonrpc\":\"2.0\",\"id\":\"deep\",\"method\":\"ping\"}\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"),
                            out);
            assertThat(frames(out)).hasSize(2);
            assertThat(frames(out))
                    .anySatisfy(
                            frame -> {
                                assertThat(frame).containsEntry("id", "deep");
                                assertThat(frame.get("error"))
                                        .isEqualTo(
                                                Map.of(
                                                        "code",
                                                        -32603,
                                                        "message",
                                                        "Internal error"));
                            })
                    .anySatisfy(
                            frame ->
                                    assertThat(frame)
                                            .containsEntry("id", 2)
                                            .containsEntry("result", Map.of()));
        }
    }

    @Test
    void shutdownUsesConfiguredTimeoutAndInterruptsAfterExpiry() throws Exception {
        try (ToolRegistry registry = new ToolRegistry(Duration.ofSeconds(27))) {
            ExecutorService executor = mock(ExecutorService.class);
            try (var factories = mockStatic(Executors.class)) {
                factories.when(Executors::newVirtualThreadPerTaskExecutor).thenReturn(executor);
                new McpTransport(new McpDispatcher(registry))
                        .start(input(""), new ByteArrayOutputStream());
                verify(executor).shutdown();
                verify(executor)
                        .awaitTermination(Duration.ofSeconds(32).toNanos(), TimeUnit.NANOSECONDS);
                verify(executor).shutdownNow();
            }
        }
    }

    @Test
    void restoresInterruptAndDoesNotOwnInjectedExecutor() throws Exception {
        try (ToolRegistry registry = new ToolRegistry()) {
            ExecutorService executor = mock(ExecutorService.class);
            when(executor.awaitTermination(anyLong(), any())).thenThrow(new InterruptedException());
            try (var factories = mockStatic(Executors.class)) {
                factories.when(Executors::newVirtualThreadPerTaskExecutor).thenReturn(executor);
                try {
                    new McpTransport(new McpDispatcher(registry))
                            .start(input(""), new ByteArrayOutputStream());
                    assertThat(Thread.currentThread().isInterrupted()).isTrue();
                    verify(executor).shutdownNow();
                } finally {
                    Thread.interrupted();
                }
            }
            ExecutorService external = mock(ExecutorService.class);
            new McpTransport(new McpDispatcher(registry), external)
                    .start(input(""), new ByteArrayOutputStream());
            verifyNoInteractions(external);
        }
    }

    @Test
    void concurrentResponsesAreSeparateUtf8Frames() {
        try (ToolRegistry registry = new ToolRegistry()) {
            StringBuilder requests = new StringBuilder();
            for (int i = 0; i < 100; i++)
                requests.append("{\"jsonrpc\":\"2.0\",\"id\":\"文字-")
                        .append(i)
                        .append("\",\"method\":\"ping\"}\n");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            new McpTransport(new McpDispatcher(registry)).start(input(requests.toString()), out);
            assertThat(frames(out)).hasSize(100);
            assertThat(frames(out).stream().map(frame -> frame.get("id")).distinct()).hasSize(100);
        }
    }
}
