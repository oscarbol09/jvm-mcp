package dev.jvmmcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.util.SimpleJson;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

@ExtendWith(MockitoExtension.class)
class ServeCommandTest {

    @Mock private JvmAttachService mockAttachService;

    @Test
    @DisplayName("serve command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("serve", "--help");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("serve command with unimplemented sse transport should return exit code 1")
    void shouldFailOnUnimplementedSseTransport() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("serve", "--transport", "sse");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Spring layer not yet implemented");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("serve command should reject unknown transport before attach or startup")
    void shouldRejectUnknownTransportBeforeStartup() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        java.io.InputStream unreadableInput =
                new java.io.InputStream() {
                    @Override
                    public int read() {
                        throw new AssertionError(
                                "input should not be read for an invalid transport");
                    }
                };
        ServeCommand command = new ServeCommand(mockAttachService, unreadableInput);
        command.targetPid = 42;

        try {
            System.setErr(new PrintStream(err));
            int exitCode = new CommandLine(command).execute("--transport", "websocket");

            assertThat(exitCode).isEqualTo(2);
            assertThat(err.toString()).contains("Unsupported transport");
            assertThat(err.toString()).contains("Usage:");
            assertThat(err.toString()).doesNotContain("Starting server");
            verifyNoInteractions(mockAttachService);
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("serve command with --spring option should return exit code 1")
    void shouldFailOnSpringOption() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("serve", "--spring");

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Spring layer not yet implemented");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("serve command with stdio transport should return exit code 0")
    void shouldAcceptStdioTransport() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setErr(new PrintStream(err));
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("serve", "--transport", "StDiO");

            assertThat(exitCode).isZero();
            assertThat(err.toString()).contains("Starting server via transport: StDiO");
        } finally {
            System.setErr(originalErr);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("serve command with valid --attach PID should attach and return 0")
    void shouldAttachToValidPid() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid)))
                .thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ServeCommand command = new ServeCommand(mockAttachService);
        command.targetPid = (int) targetPid;
        command.transport = "stdio";

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setErr(new PrintStream(err));
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(err.toString()).contains("Successfully attached to target PID " + targetPid);
            assertThat(err.toString()).contains("Starting server via transport: stdio");
        } finally {
            System.setErr(originalErr);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("serve command with non-existent --attach PID should return exit code 1")
    void shouldFailOnNonExistentAttachPid() {
        long targetPid = 999999999L;
        when(mockAttachService.attach(String.valueOf(targetPid)))
                .thenReturn(AttachResult.processNotFound(String.valueOf(targetPid)));

        ServeCommand command = new ServeCommand(mockAttachService);
        command.targetPid = (int) targetPid;
        command.transport = "stdio";

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Error attaching to target PID " + targetPid);
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("Direct invocation of ServeCommand should return exit code 0")
    void shouldCallDirectly() {
        ServeCommand command = new ServeCommand();
        command.transport = "stdio";

        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            Integer exitCode = command.call();
            assertThat(exitCode).isZero();
        } finally {
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("initialize MCP handshake returns a valid protocolVersion")
    void shouldInitializeHandshake() throws Exception {
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input =
                    "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {\"protocolVersion\": \"2024-11-05\"}}\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();

            String output = out.toString().trim();
            Map<String, Object> response = SimpleJson.parseObject(output);
            assertThat(response.get("jsonrpc")).isEqualTo("2.0");
            assertThat(response.get("id")).isEqualTo(1);
            Map<String, Object> result = asMap(response.get("result"));
            assertThat(result).isNotNull();
            assertThat(result.get("protocolVersion")).isEqualTo("2024-11-05");
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("tools/call with unknown tool returns error")
    void shouldReturnErrorForUnknownTool() throws Exception {
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input =
                    "{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/call\", \"params\": {\"name\": \"unknown_tool\", \"arguments\": {}}}\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();

            String output = out.toString().trim();
            Map<String, Object> response = SimpleJson.parseObject(output);

            Map<String, Object> result = asMap(response.get("result"));
            assertThat(result).isNotNull();
            assertThat(result.get("isError")).isEqualTo(true);
            List<Map<String, Object>> content = asListOfMaps(result.get("content"));
            assertThat(content).isNotNull();
            assertThat(content.get(0).get("text").toString())
                    .contains("Unknown tool: unknown_tool");
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("tools/call with missing parameters returns error")
    void shouldReturnErrorForMissingParams() throws Exception {
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input =
                    "{\"jsonrpc\": \"2.0\", \"id\": 3, \"method\": \"tools/call\", \"params\": {\"name\": \"get_memory_summary\", \"arguments\": {}}}\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();

            String output = out.toString().trim();
            Map<String, Object> response = SimpleJson.parseObject(output);

            Map<String, Object> result = asMap(response.get("result"));
            assertThat(result).isNotNull();
            assertThat(result.get("isError")).isEqualTo(true);
            List<Map<String, Object>> content = asListOfMaps(result.get("content"));
            assertThat(content).isNotNull();
            assertThat(content.get(0).get("text").toString())
                    .contains("Missing required argument: pid");
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("ping returns empty result")
    void shouldRespondToPing() throws Exception {
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input = "{\"jsonrpc\": \"2.0\", \"id\": 10, \"method\": \"ping\"}\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();

            String output = out.toString().trim();
            Map<String, Object> response = SimpleJson.parseObject(output);
            assertThat(response.get("id")).isEqualTo(10);
            assertThat(response.get("result")).isEqualTo(Map.of());
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("notifications do not emit responses")
    void shouldHandleNotificationWithoutResponse() throws Exception {
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input = "{\"jsonrpc\": \"2.0\", \"method\": \"notifications/initialized\"}\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();
            assertThat(out.toString().trim()).isEmpty();
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("unknown method returns -32601 Method not found")
    void shouldReturnMethodNotFound() throws Exception {
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input =
                    "{\"jsonrpc\": \"2.0\", \"id\": 11, \"method\": \"nonexistent/method\"}\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();

            String output = out.toString().trim();
            Map<String, Object> response = SimpleJson.parseObject(output);
            assertThat(response.get("id")).isEqualTo(11);
            Map<String, Object> error = asMap(response.get("error"));
            assertThat(error).isNotNull();
            assertThat(error.get("code")).isEqualTo(-32601);
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("list_jvms tool call invokes attach service")
    void shouldHandleListJvmsTool() throws Exception {
        when(mockAttachService.listJvms()).thenReturn(List.of());
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input =
                    "{\"jsonrpc\": \"2.0\", \"id\": 12, \"method\": \"tools/call\", \"params\": {\"name\": \"list_jvms\", \"arguments\": {}}}\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();

            String output = out.toString().trim();
            Map<String, Object> response = SimpleJson.parseObject(output);
            assertThat(response.get("id")).isEqualTo(12);
            Map<String, Object> result = asMap(response.get("result"));
            assertThat(result).isNotNull();
            List<Map<String, Object>> content = asListOfMaps(result.get("content"));
            assertThat(content.get(0).get("text")).isEqualTo("[]");
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("inspect_pg_schema tool with invalid URL returns error")
    void shouldHandleInvalidPgUrl() throws Exception {
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input =
                    "{\"jsonrpc\": \"2.0\", \"id\": 13, \"method\": \"tools/call\", \"params\": {\"name\": \"inspect_pg_schema\", \"arguments\": {\"url\": \"http://evil.com\"}}}\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();

            String output = out.toString().trim();
            Map<String, Object> response = SimpleJson.parseObject(output);
            Map<String, Object> result = asMap(response.get("result"));
            assertThat(result.get("isError")).isEqualTo(true);
            List<Map<String, Object>> content = asListOfMaps(result.get("content"));
            assertThat(content.get(0).get("text").toString())
                    .contains("must start with jdbc:postgresql://");
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("tools/call with non-number pid returns error")
    void shouldHandleNonNumberPid() throws Exception {
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input =
                    "{\"jsonrpc\": \"2.0\", \"id\": 14, \"method\": \"tools/call\", \"params\": {\"name\": \"get_memory_summary\", \"arguments\": {\"pid\": \"not_a_number\"}}}\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();

            String output = out.toString().trim();
            Map<String, Object> response = SimpleJson.parseObject(output);
            Map<String, Object> result = asMap(response.get("result"));
            assertThat(result.get("isError")).isEqualTo(true);
            List<Map<String, Object>> content = asListOfMaps(result.get("content"));
            assertThat(content.get(0).get("text").toString()).contains("must be a number");
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("invalid JSON returns -32700 Parse error")
    void shouldReturnParseErrorOnInvalidJson() throws Exception {
        ServeCommand command = new ServeCommand(mockAttachService);
        command.transport = "stdio";

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setOut(new PrintStream(out));
            String input = "{not-valid-json\n";
            ServeCommand.testInStream =
                    new java.io.ByteArrayInputStream(
                            input.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            Integer exitCode = command.call();
            assertThat(exitCode).isZero();

            String output = out.toString().trim();
            Map<String, Object> response = SimpleJson.parseObject(output);
            Map<String, Object> error = asMap(response.get("error"));
            assertThat(error).isNotNull();
            assertThat(error.get("code")).isEqualTo(-32700);
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("shutdown hook detaches attached VirtualMachine")
    void shouldDetachOnShutdownHook() {
        com.sun.tools.attach.VirtualMachine mockVm =
                org.mockito.Mockito.mock(com.sun.tools.attach.VirtualMachine.class);
        Runnable hook = ServeCommand.createShutdownHook(mockVm, 123, mockAttachService);
        hook.run();
        org.mockito.Mockito.verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("shutdown hook handles exception when detach fails")
    void shouldHandleExceptionDuringShutdownDetach() {
        com.sun.tools.attach.VirtualMachine mockVm =
                org.mockito.Mockito.mock(com.sun.tools.attach.VirtualMachine.class);
        org.mockito.Mockito.doThrow(new RuntimeException("Detach failed"))
                .when(mockAttachService)
                .detach(mockVm);
        Runnable hook = ServeCommand.createShutdownHook(mockVm, 123, mockAttachService);
        hook.run(); // Should not throw
        org.mockito.Mockito.verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("shutdown hook does nothing when vm is null")
    void shouldIgnoreNullVmOnShutdown() {
        Runnable hook = ServeCommand.createShutdownHook(null, 123, mockAttachService);
        hook.run();
        org.mockito.Mockito.verifyNoInteractions(mockAttachService);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object obj) {
        return obj instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asListOfMaps(Object obj) {
        return obj instanceof List<?> list ? (List<Map<String, Object>>) list : null;
    }
}
