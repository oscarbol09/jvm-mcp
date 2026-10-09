import os
import re

def append_to_class(path, text):
    with open(path, 'r', encoding='utf-8') as f:
        content = f.read()
    
    # find the last '}'
    last_brace_index = content.rfind('}')
    if last_brace_index == -1:
        return
    
    new_content = content[:last_brace_index] + text + "\n}\n"
    with open(path, 'w', encoding='utf-8') as f:
        f.write(new_content)

serve_test_path = r'mcp-server\src\test\java\dev\jvmmcp\ServeCommandTest.java'
serve_new_tests = '''
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
            String input = "{\\"jsonrpc\\": \\"2.0\\", \\"id\\": 1, \\"method\\": \\"initialize\\", \\"params\\": {\\"protocolVersion\\": \\"2024-11-05\\"}}\\n";
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
            String input = "{\\"jsonrpc\\": \\"2.0\\", \\"id\\": 2, \\"method\\": \\"tools/call\\", \\"params\\": {\\"name\\": \\"unknown_tool\\", \\"arguments\\": {}}}\\n";
            ServeCommand.testInStream = new java.io.ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            Integer exitCode = command.call();
            assertThat(exitCode).isZero();
            
            String output = out.toString().trim();
            java.util.Map<String, Object> response = dev.jvmmcp.core.util.SimpleJson.parseObject(output);
            
            java.util.Map<String, Object> result = (java.util.Map<String, Object>) response.get("result");
            assertThat(result.get("isError")).isEqualTo(True);
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
            String input = "{\\"jsonrpc\\": \\"2.0\\", \\"id\\": 3, \\"method\\": \\"tools/call\\", \\"params\\": {\\"name\\": \\"get_memory_summary\\", \\"arguments\\": {}}}\\n";
            ServeCommand.testInStream = new java.io.ByteArrayInputStream(input.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            
            Integer exitCode = command.call();
            assertThat(exitCode).isZero();
            
            String output = out.toString().trim();
            java.util.Map<String, Object> response = dev.jvmmcp.core.util.SimpleJson.parseObject(output);
            
            java.util.Map<String, Object> result = (java.util.Map<String, Object>) response.get("result");
            assertThat(result.get("isError")).isEqualTo(True);
            java.util.List<java.util.Map<String, Object>> content = (java.util.List<java.util.Map<String, Object>>) result.get("content");
            assertThat(content.get(0).get("text").toString()).contains("Missing required argument: pid");
        } finally {
            System.setOut(originalOut);
            ServeCommand.testInStream = originalIn;
        }
    }
'''.replace('True', 'true')

json_test_path = r'core\src\test\java\dev\jvmmcp\core\util\SimpleJsonTest.java'
json_new_tests = '''
    @Test
    @DisplayName("parse and toJson should round-trip successfully for strings with escape sequences and unicode")
    void shouldRoundTripStrings() {
        String original = "Here is a string with \\n newline, \\t tab, \\u001b escape, \\" quotes \\", and unicode ? ??.";
        String json = SimpleJson.toJson(original);
        Object parsed = SimpleJson.parse(json);
        assertThat(parsed).isEqualTo(original);
    }
'''

append_to_class(serve_test_path, serve_new_tests)
append_to_class(json_test_path, json_new_tests)
print("Updated.")
