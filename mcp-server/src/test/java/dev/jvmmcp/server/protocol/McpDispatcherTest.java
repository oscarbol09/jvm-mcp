package dev.jvmmcp.server.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import dev.jvmmcp.server.tool.ToolHandler;
import dev.jvmmcp.server.tool.ToolRegistry;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class McpDispatcherTest {

    private ToolRegistry registry;
    private McpDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        registry = new ToolRegistry();
        registry.register(
                new ToolHandler() {
                    @Override
                    public List<String> toolNames() {
                        return List.of("mock_tool");
                    }

                    @Override
                    public List<Map<String, Object>> toolDefinitions() {
                        return List.of(
                                Map.of(
                                        "name",
                                        "mock_tool",
                                        "description",
                                        "Mock tool description"));
                    }

                    @Override
                    public Map<String, Object> execute(
                            String toolName, Map<String, Object> arguments) {
                        return Map.of(
                                "content", List.of(Map.of("type", "text", "text", "mock_result")));
                    }
                });
        dispatcher = new McpDispatcher(registry, "test-mcp", "2.0.0");
    }

    @Test
    @DisplayName("dispatch handles initialize method properly")
    void testInitialize() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("jsonrpc")).isEqualTo("2.0");
        assertThat(resp.get("id")).isEqualTo(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        assertThat(result.get("protocolVersion")).isEqualTo("2024-11-05");

        @SuppressWarnings("unchecked")
        Map<String, Object> serverInfo = (Map<String, Object>) result.get("serverInfo");
        assertThat(serverInfo.get("name")).isEqualTo("test-mcp");
        assertThat(serverInfo.get("version")).isEqualTo("2.0.0");
    }

    @Test
    @DisplayName("dispatch handles ping method properly")
    void testPing() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":\"abc\",\"method\":\"ping\"}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isEqualTo("abc");
        assertThat(resp.get("result")).isEqualTo(Map.of());
    }

    @Test
    @DisplayName("dispatch handles tools/list properly")
    void testToolsList() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":100,\"method\":\"tools/list\"}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isEqualTo(100);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) result.get("tools");
        assertThat(tools).hasSize(1);
        assertThat(tools.get(0).get("name")).isEqualTo("mock_tool");
    }

    @Test
    @DisplayName("dispatch handles tools/call properly")
    void testToolsCallSuccess() {
        String line =
                "{\"jsonrpc\":\"2.0\",\"id\":200,\"method\":\"tools/call\",\"params\":{\"name\":\"mock_tool\",\"arguments\":{}}}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isEqualTo(200);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text")).isEqualTo("mock_result");
    }

    @Test
    @DisplayName("dispatch returns -32602 when tools/call has null or missing params")
    void testToolsCallInvalidParams() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":201,\"method\":\"tools/call\"}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isEqualTo(201);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertThat(error.get("code")).isEqualTo(-32602);
        assertThat(error.get("message")).isEqualTo("Invalid params");
    }

    @Test
    @DisplayName("dispatch returns -32601 Method not found for unknown methods")
    void testMethodNotFound() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":300,\"method\":\"custom/action\"}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isEqualTo(300);

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertThat(error.get("code")).isEqualTo(-32601);
        assertThat(error.get("message")).isEqualTo("Method not found");
    }

    @Test
    @DisplayName("dispatch ignores notifications without id")
    void testNotifications() {
        String line = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/test\"}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isEmpty();
    }

    @ParameterizedTest
    @MethodSource("malformedInputs")
    @DisplayName("dispatch returns -32700 for empty input and malformed JSON")
    void testParseError(String line) {
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertThat(error.get("code")).isEqualTo(-32700);
        assertThat(error.get("message").toString()).contains("Parse error:");
    }

    private static Stream<Arguments> malformedInputs() {
        return Stream.of(
                Arguments.of((String) null),
                Arguments.of(""),
                Arguments.of(" \t\n"),
                Arguments.of("{not a json}"),
                Arguments.of("{\"jsonrpc\":"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[1]", "42", "\"x\"", "true", "null"})
    @DisplayName("dispatch returns -32600 for valid JSON values that are not request objects")
    void testNonObjectJsonRequest(String line) {
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("jsonrpc")).isEqualTo("2.0");
        assertThat(resp.get("id")).isNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertThat(error.get("code")).isEqualTo(-32600);
        assertThat(error.get("message").toString()).contains("Invalid Request");
    }

    @Test
    @DisplayName("handleRequest returns empty on null request")
    void testNullRequest() {
        assertThat(dispatcher.handleRequest(null)).isEmpty();
    }

    @Test
    @DisplayName("error formatting methods produce compliant JSON-RPC 2.0 payloads")
    void testErrorFormatters() {
        Map<String, Object> parseErr = dispatcher.formatParseError("syntax issue");
        assertThat(parseErr.get("id")).isNull();
        assertThat(((Map<?, ?>) parseErr.get("error")).get("code")).isEqualTo(-32700);

        Map<String, Object> notFound = dispatcher.formatMethodNotFound(42);
        assertThat(notFound.get("id")).isEqualTo(42);
        assertThat(((Map<?, ?>) notFound.get("error")).get("code")).isEqualTo(-32601);

        Map<String, Object> invalidParams = dispatcher.formatInvalidParams(43, "bad parameter");
        assertThat(invalidParams.get("id")).isEqualTo(43);
        assertThat(((Map<?, ?>) invalidParams.get("error")).get("code")).isEqualTo(-32602);
        assertThat(((Map<?, ?>) invalidParams.get("error")).get("message"))
                .isEqualTo("bad parameter");

        Map<String, Object> defaultInvalidParams = dispatcher.formatInvalidParams(44, null);
        assertThat(((Map<?, ?>) defaultInvalidParams.get("error")).get("message"))
                .isEqualTo("Invalid params");

        Map<String, Object> internalErr = dispatcher.formatInternalError(45, "database down");
        assertThat(internalErr.get("id")).isEqualTo(45);
        assertThat(((Map<?, ?>) internalErr.get("error")).get("code")).isEqualTo(-32603);
    }

    @Test
    @DisplayName("dispatch returns -32600 when jsonrpc version is missing or invalid")
    void testInvalidJsonRpcVersion() {
        String line = "{\"id\":1,\"method\":\"ping\"}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertThat(error.get("code")).isEqualTo(-32600);
        assertThat(error.get("message").toString()).contains("Invalid Request");
    }

    @Test
    @DisplayName("dispatch returns -32600 when method is missing or not a string")
    void testMissingMethod() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":1}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertThat(error.get("code")).isEqualTo(-32600);
    }

    @Test
    @DisplayName("dispatch returns -32600 when id type is not a string, number or null")
    void testInvalidIdType() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":[1, 2],\"method\":\"ping\"}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertThat(error.get("code")).isEqualTo(-32600);
        assertThat(error.get("message").toString())
                .contains("Invalid Request: 'id' must be a String, Number, or null");
    }

    @Test
    @DisplayName("dispatch supports requests with explicit id: null")
    void testExplicitNullId() {
        String line = "{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"ping\"}";
        Optional<Map<String, Object>> response = dispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        assertThat(resp.get("id")).isNull();
        assertThat(resp.get("result")).isEqualTo(Map.of());
    }

    @Test
    @DisplayName(
            "dispatch sanitizes internal errors and does not expose raw exception text to client")
    void testInternalErrorSanitization() {
        ToolRegistry throwingRegistry =
                new ToolRegistry() {
                    @Override
                    public Map<String, Object> execute(
                            String toolName, Map<String, Object> arguments) {
                        throw new RuntimeException(
                                "Sensitive PostgreSQL db password: secret123 at /etc/passwd");
                    }
                };
        McpDispatcher throwingDispatcher = new McpDispatcher(throwingRegistry);

        String line =
                "{\"jsonrpc\":\"2.0\",\"id\":500,\"method\":\"tools/call\",\"params\":{\"name\":\"faulty_tool\",\"arguments\":{}}}";
        Optional<Map<String, Object>> response = throwingDispatcher.dispatch(line);

        assertThat(response).isPresent();
        Map<String, Object> resp = response.get();
        @SuppressWarnings("unchecked")
        Map<String, Object> error = (Map<String, Object>) resp.get("error");
        assertThat(error.get("code")).isEqualTo(-32603);
        String msg = (String) error.get("message");
        assertThat(msg).doesNotContain("secret123");
        assertThat(msg).doesNotContain("/etc/passwd");
        assertThat(msg).contains("Internal error");
    }
}
