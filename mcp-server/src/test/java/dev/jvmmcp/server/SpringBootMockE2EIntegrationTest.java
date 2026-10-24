package dev.jvmmcp.server;

import com.sun.net.httpserver.HttpServer;
import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.model.SpringContextBeans;
import dev.jvmmcp.core.spring.ActuatorClient;
import dev.jvmmcp.core.spring.SpringBeansClient;
import dev.jvmmcp.core.util.SimpleJson;
import dev.jvmmcp.server.protocol.McpDispatcher;
import dev.jvmmcp.server.tool.ActuatorToolHandler;
import dev.jvmmcp.server.tool.SpringBeansToolHandler;
import dev.jvmmcp.server.tool.ToolRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration test validating realistic Spring Boot inspection workflows
 * over the Model Context Protocol (MCP) JSON-RPC layer.
 */
class SpringBootMockE2EIntegrationTest {

    private static HttpServer server;
    private static String serverBaseUrl;

    @BeforeAll
    static void startMockSpringBootActuator() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        // 1. /actuator/health
        server.createContext("/actuator/health", exchange -> {
            String json = """
                {
                  "status": "UP",
                  "components": {
                    "db": {
                      "status": "UP",
                      "details": {
                        "database": "PostgreSQL",
                        "validationQuery": "isValid()"
                      }
                    },
                    "diskSpace": {
                      "status": "UP",
                      "details": {
                        "total": 512000000000,
                        "free": 256000000000,
                        "threshold": 10485760
                      }
                    }
                  }
                }
                """;
            byte[] resp = json.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
            exchange.sendResponseHeaders(200, resp.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(resp);
            }
        });

        // 2. /actuator/metrics
        server.createContext("/actuator/metrics", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/actuator/metrics")) {
                String json = """
                    {
                      "names": [
                        "jvm.memory.used",
                        "jvm.threads.live",
                        "http.server.requests"
                      ]
                    }
                    """;
                byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } else if (path.equals("/actuator/metrics/jvm.memory.used")) {
                String json = """
                    {
                      "name": "jvm.memory.used",
                      "description": "The amount of used memory",
                      "baseUnit": "bytes",
                      "measurements": [
                        {
                          "statistic": "VALUE",
                          "value": 134217728.0
                        }
                      ]
                    }
                    """;
                byte[] resp = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
                exchange.sendResponseHeaders(200, resp.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(resp);
                }
            } else if (path.equals("/actuator/metrics/jvm.threads.live")) {
                // Verify GZIP decompression support when client sends Accept-Encoding: gzip
                String json = """
                    {
                      "name": "jvm.threads.live",
                      "description": "The current number of live threads",
                      "measurements": [
                        {
                          "statistic": "VALUE",
                          "value": 42.0
                        }
                      ]
                    }
                    """;
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try (GZIPOutputStream gzos = new GZIPOutputStream(baos)) {
                    gzos.write(json.getBytes(StandardCharsets.UTF_8));
                }
                byte[] gzipped = baos.toByteArray();
                exchange.getResponseHeaders().set("Content-Type", "application/vnd.spring-boot.actuator.v3+json");
                exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                exchange.sendResponseHeaders(200, gzipped.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(gzipped);
                }
            } else {
                exchange.sendResponseHeaders(404, -1);
            }
        });

        server.start();
        int port = server.getAddress().getPort();
        serverBaseUrl = "http://127.0.0.1:" + port;
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    @DisplayName("End-to-End: MCP Protocol handshake and tool discovery")
    void testMcpProtocolHandshakeAndToolsDiscovery() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ActuatorToolHandler());
        McpDispatcher dispatcher = new McpDispatcher(registry, "jvm-mcp", "1.1.0-beta.1");

        // 1. Initialize
        String initRequest = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "method": "initialize",
              "params": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "test-ai-client", "version": "1.0.0"}
              }
            }
            """;
        Optional<Map<String, Object>> initOpt = dispatcher.dispatch(initRequest);
        assertThat(initOpt).isPresent();
        Map<String, Object> initResp = initOpt.get();
        assertThat(initResp.get("id")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> initResult = (Map<String, Object>) initResp.get("result");
        assertThat(initResult.get("protocolVersion")).isEqualTo("2024-11-05");

        // 2. Tools list
        String listRequest = """
            {"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}}
            """;
        Optional<Map<String, Object>> listOpt = dispatcher.dispatch(listRequest);
        assertThat(listOpt).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> listResult = (Map<String, Object>) listOpt.get().get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> tools = (List<Map<String, Object>>) listResult.get("tools");
        assertThat(tools)
            .extracting(t -> (String) t.get("name"))
            .contains("get_actuator_health", "get_actuator_metrics");
    }

    @Test
    @DisplayName("End-to-End: Execute get_actuator_health over MCP against mock Spring Boot")
    void testGetActuatorHealthEndToEnd() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ActuatorToolHandler());
        McpDispatcher dispatcher = new McpDispatcher(registry);

        String callRequest = String.format("""
            {
              "jsonrpc": "2.0",
              "id": 10,
              "method": "tools/call",
              "params": {
                "name": "get_actuator_health",
                "arguments": {
                  "url": "%s"
                }
              }
            }
            """, serverBaseUrl);

        Optional<Map<String, Object>> callOpt = dispatcher.dispatch(callRequest);
        assertThat(callOpt).isPresent();
        Map<String, Object> resp = callOpt.get();
        assertThat(resp.get("id")).isEqualTo(10);

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) resp.get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).isNotEmpty();
        String jsonText = (String) content.get(0).get("text");
        assertThat(jsonText).contains("\"status\": \"UP\"");
        assertThat(jsonText).contains("PostgreSQL");
    }

    @Test
    @DisplayName("End-to-End: Execute get_actuator_metrics with GZIP response decompression")
    void testGetActuatorMetricsWithGzipDecompression() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ActuatorToolHandler());
        McpDispatcher dispatcher = new McpDispatcher(registry);

        // Specific metric that returns gzipped content
        String callRequest = String.format("""
            {
              "jsonrpc": "2.0",
              "id": 11,
              "method": "tools/call",
              "params": {
                "name": "get_actuator_metrics",
                "arguments": {
                  "url": "%s",
                  "metrics": "jvm.threads.live"
                }
              }
            }
            """, serverBaseUrl);

        Optional<Map<String, Object>> callOpt = dispatcher.dispatch(callRequest);
        assertThat(callOpt).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) callOpt.get().get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        String text = (String) content.get(0).get("text");
        assertThat(text).contains("jvm.threads.live");
        assertThat(text).contains("42");
    }

    @Test
    @DisplayName("End-to-End: Execute list_spring_beans and get_bean_detail with numeric string PID")
    void testSpringBeansInspectionEndToEnd() throws Exception {
        SpringBeansClient mockClient = mock(SpringBeansClient.class);
        dev.jvmmcp.core.attach.JvmAttachService mockAttachService = mock(dev.jvmmcp.core.attach.JvmAttachService.class);
        when(mockAttachService.attach("12345")).thenReturn(dev.jvmmcp.core.attach.AttachResult.success("12345", null));

        SpringBeanDetail userRepoBean = new SpringBeanDetail(
            "userRepository",
            List.of(),
            "singleton",
            "com.example.repository.UserRepository",
            null,
            List.of("dataSource")
        );
        SpringBeansReport report = new SpringBeansReport(
            12345L,
            "JMX",
            1,
            List.of(new SpringContextBeans("root", null, 1, List.of(userRepoBean)))
        );
        when(mockClient.inspectBeansWithJmxProvider(org.mockito.ArgumentMatchers.eq(12345L), any(), any(), any()))
            .thenReturn(report);

        ToolRegistry registry = new ToolRegistry();
        // Register SpringBeansToolHandler with mock attachService and mock client
        registry.register(new SpringBeansToolHandler(
            mockAttachService,
            mockClient,
            (vm, pid) -> mock(dev.jvmmcp.core.jmx.JmxConnectionManager.class)
        ));
        McpDispatcher dispatcher = new McpDispatcher(registry);

        // 1. list_spring_beans using stringified PID (verifying LLM robustness from Issue #162)
        String listBeansCall = """
            {
              "jsonrpc": "2.0",
              "id": 20,
              "method": "tools/call",
              "params": {
                "name": "list_spring_beans",
                "arguments": {
                  "pid": "12345",
                  "filter": "*Repo*"
                }
              }
            }
            """;
        Optional<Map<String, Object>> listOpt = dispatcher.dispatch(listBeansCall);
        assertThat(listOpt).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> listResult = (Map<String, Object>) listOpt.get().get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> listContent = (List<Map<String, Object>>) listResult.get("content");
        assertThat(listContent.get(0).get("text").toString()).contains("userRepository");

        // 2. get_bean_detail for existing bean
        String getBeanCall = """
            {
              "jsonrpc": "2.0",
              "id": 21,
              "method": "tools/call",
              "params": {
                "name": "get_bean_detail",
                "arguments": {
                  "pid": 12345,
                  "beanName": "userRepository"
                }
              }
            }
            """;
        Optional<Map<String, Object>> getOpt = dispatcher.dispatch(getBeanCall);
        assertThat(getOpt).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> getResult = (Map<String, Object>) getOpt.get().get("result");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> getContent = (List<Map<String, Object>>) getResult.get("content");
        assertThat(getContent.get(0).get("text").toString()).contains("userRepository");

        // 3. get_bean_detail for non-existing bean (verifying Issue #112 isError: true formatting)
        String getMissingBeanCall = """
            {
              "jsonrpc": "2.0",
              "id": 22,
              "method": "tools/call",
              "params": {
                "name": "get_bean_detail",
                "arguments": {
                  "pid": 12345,
                  "beanName": "nonExistentBean"
                }
              }
            }
            """;
        Optional<Map<String, Object>> missingOpt = dispatcher.dispatch(getMissingBeanCall);
        assertThat(missingOpt).isPresent();
        @SuppressWarnings("unchecked")
        Map<String, Object> missingResult = (Map<String, Object>) missingOpt.get().get("result");
        assertThat(missingResult.get("isError")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> missingContent = (List<Map<String, Object>>) missingResult.get("content");
        assertThat(missingContent.get(0).get("text").toString()).contains("Bean not found");
    }
}
