package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ServeCommandTest {

    @Mock
    private JvmAttachService mockAttachService;

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
            ServeCommand.testInStream = new java.io.ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            CommandLine cmd = new CommandLine(new JvmMcp());
            int exitCode = cmd.execute("serve", "--transport", "stdio");

            assertThat(exitCode).isZero();
            assertThat(err.toString()).contains("Starting server via transport: stdio");
        } finally {
            System.setErr(originalErr);
            ServeCommand.testInStream = originalIn;
        }
    }

    @Test
    @DisplayName("serve command with valid --attach PID should attach and return 0")
    void shouldAttachToValidPid() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        ServeCommand command = new ServeCommand(mockAttachService);
        command.targetPid = (int) targetPid;
        command.transport = "stdio";

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        java.io.InputStream originalIn = ServeCommand.testInStream;
        try {
            System.setErr(new PrintStream(err));
            ServeCommand.testInStream = new java.io.ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.processNotFound(String.valueOf(targetPid)));

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
            ServeCommand.testInStream = new java.io.ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            String input = "{\"jsonrpc\": \"2.0\", \"id\": 1, \"method\": \"initialize\", \"params\": {\"protocolVersion\": \"2024-11-05\"}}\n";
            ServeCommand.testInStream = new java.io.ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            Integer exitCode = command.call();
            assertThat(exitCode).isZero();
            
            String output = out.toString().trim();
            java.util.Map<String, Object> response = dev.jvmmcp.core.util.SimpleJson.parseObject(output);
            assertThat(response.get("jsonrpc")).isEqualTo("2.0");
            assertThat(response.get("id")).isEqualTo(1);
            java.util.Map<String, Object> result = (java.util.Map<String, Object>) response.get("result");
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
            String input = "{\"jsonrpc\": \"2.0\", \"id\": 2, \"method\": \"tools/call\", \"params\": {\"name\": \"unknown_tool\", \"arguments\": {}}}\n";
            ServeCommand.testInStream = new java.io.ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            Integer exitCode = command.call();
            assertThat(exitCode).isZero();
            
            String output = out.toString().trim();
            java.util.Map<String, Object> response = dev.jvmmcp.core.util.SimpleJson.parseObject(output);
            
            java.util.Map<String, Object> result = (java.util.Map<String, Object>) response.get("result");
            assertThat(result.get("isError")).isEqualTo(true);
            java.util.List<java.util.Map<String, Object>> content = (java.util.List<java.util.Map<String, Object>>) result.get("content");
            assertThat(content.get(0).get("text").toString()).contains("Unknown tool: unknown_tool");
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
            String input = "{\"jsonrpc\": \"2.0\", \"id\": 3, \"method\": \"tools/call\", \"params\": {\"name\": \"get_memory_summary\", \"arguments\": {}}}\n";
            ServeCommand.testInStream = new java.io.ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            Integer exitCode = command.call();
            assertThat(exitCode).isZero();
            
            String output = out.toString().trim();
            java.util.Map<String, Object> response = dev.jvmmcp.core.util.SimpleJson.parseObject(output);
            
            java.util.Map<String, Object> result = (java.util.Map<String, Object>) response.get("result");
            assertThat(result.get("isError")).isEqualTo(true);
            java.util.List<java.util.Map<String, Object>> content = (java.util.List<java.util.Map<String, Object>>) result.get("content");
            assertThat(content.get(0).get("text").toString()).contains("Missing required argument: pid");
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }

}
