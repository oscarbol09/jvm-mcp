package dev.jvmmcp.server.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.jvmmcp.core.spring.ActuatorClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ActuatorToolHandlerTest {

    @Mock private ActuatorClient mockClient;

    @Test
    @DisplayName("toolNames and toolDefinitions return all 3 Actuator tools")
    void testToolNamesAndDefinitions() {
        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient);

        assertThat(handler.toolNames())
                .containsExactlyInAnyOrder(
                        "get_actuator_health", "get_actuator_metrics", "get_actuator_startup");
        assertThat(handler.toolDefinitions()).hasSize(3);
    }

    @Test
    @DisplayName("metrics tool description documents the actual metrics parameter")
    void testMetricsToolDescription() {
        Map<String, Object> definition =
                handlerToolDefinition(new ActuatorToolHandler((url, auth) -> mockClient));
        assertThat(definition.get("description").toString())
                .contains("metrics")
                .doesNotContain("metricName");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> handlerToolDefinition(ActuatorToolHandler handler) {
        return handler.toolDefinitions().stream()
                .filter(definition -> "get_actuator_metrics".equals(definition.get("name")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    @DisplayName("execute throws on missing url")
    void testMissingUrl() {
        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient);

        assertThatThrownBy(() -> handler.execute("get_actuator_health", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required argument: url");
    }

    @Test
    @DisplayName("execute throws when auth configuration is invalid")
    void testInvalidAuth() {
        Map<String, String> env = Map.of("ACTUATOR_USER", "admin");
        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient, env::get);

        assertThatThrownBy(
                        () ->
                                handler.execute(
                                        "get_actuator_health",
                                        Map.of("url", "http://localhost:8080")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Username and password must be provided together");
    }

    @Test
    @DisplayName("blank bearer token does not prevent Basic authentication")
    void testBlankBearerTokenFallsBackToBasicAuth() throws Exception {
        Map<String, String> env =
                Map.of(
                        "ACTUATOR_USER", "admin",
                        "ACTUATOR_PASSWORD", "secret",
                        "ACTUATOR_TOKEN", "");
        ActuatorToolHandler handler =
                new ActuatorToolHandler(
                        (url, auth) -> {
                            assertThat(auth.user()).isEqualTo("admin");
                            assertThat(auth.password()).isEqualTo("secret");
                            assertThat(auth.bearerToken()).isEmpty();
                            assertThat(auth.authorizationHeader())
                                    .hasValueSatisfying(
                                            value -> assertThat(value).startsWith("Basic "));
                            return mockClient;
                        },
                        env::get);

        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        handler.execute("get_actuator_health", Map.of("url", "http://localhost:8080"));
    }

    @Test
    @DisplayName("bearer token and Basic credentials cannot be configured together")
    void testConflictingAuthCredentialsAreRejected() {
        Map<String, String> env =
                Map.of(
                        "ACTUATOR_USER", "admin",
                        "ACTUATOR_PASSWORD", "secret",
                        "ACTUATOR_TOKEN", "token");
        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient, env::get);

        assertThatThrownBy(
                        () ->
                                handler.execute(
                                        "get_actuator_health",
                                        Map.of("url", "http://localhost:8080")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(
                        "Use either bearer token or username/password credentials, not both");
    }

    @Test
    @DisplayName("get_actuator_health calls client.getHealth()")
    void testGetActuatorHealth() throws Exception {
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient);
        Map<String, Object> result =
                handler.execute("get_actuator_health", Map.of("url", "http://localhost:8080"));

        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text")).isEqualTo("{\"status\":\"UP\"}");
        verify(mockClient).getHealth();
    }

    @Test
    @DisplayName("get_actuator_metrics without parameter calls client.getMetrics()")
    void testGetActuatorMetricsAll() throws Exception {
        when(mockClient.getMetrics()).thenReturn("{\"names\":[\"jvm.memory.used\"]}");

        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient);
        Map<String, Object> result =
                handler.execute("get_actuator_metrics", Map.of("url", "http://localhost:8080"));

        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text")).isEqualTo("{\"names\":[\"jvm.memory.used\"]}");
        verify(mockClient).getMetrics();
    }

    @Test
    @DisplayName("get_actuator_metrics with single metric calls client.getMetric()")
    void testGetActuatorMetricsSingle() throws Exception {
        when(mockClient.getMetric("jvm.memory.used"))
                .thenReturn("{\"name\":\"jvm.memory.used\",\"measurements\":[]}");

        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient);
        Map<String, Object> result =
                handler.execute(
                        "get_actuator_metrics",
                        Map.of(
                                "url", "http://localhost:8080",
                                "metrics", "jvm.memory.used"));

        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text"))
                .isEqualTo("{\"name\":\"jvm.memory.used\",\"measurements\":[]}");
        verify(mockClient).getMetric("jvm.memory.used");
    }

    @Test
    @DisplayName("get_actuator_metrics with comma-separated list calls client.getMetricsBatch()")
    void testGetActuatorMetricsBatch() throws Exception {
        when(mockClient.getMetricsBatch(List.of("jvm.memory.used", "jvm.threads.live")))
                .thenReturn("{\"batch\":true}");

        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient);
        Map<String, Object> result =
                handler.execute(
                        "get_actuator_metrics",
                        Map.of(
                                "url", "http://localhost:8080",
                                "metrics", "jvm.memory.used,jvm.threads.live"));

        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text")).isEqualTo("{\"batch\":true}");
        verify(mockClient).getMetricsBatch(List.of("jvm.memory.used", "jvm.threads.live"));
    }

    @Test
    @DisplayName("get_actuator_startup calls client.getStartup()")
    void testGetActuatorStartup() throws Exception {
        when(mockClient.getStartup()).thenReturn("{\"timeline\":{}}");

        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient);
        Map<String, Object> result =
                handler.execute("get_actuator_startup", Map.of("url", "http://localhost:8080"));

        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text")).isEqualTo("{\"timeline\":{}}");
        verify(mockClient).getStartup();
    }

    @Test
    @DisplayName("bearer token auth is supported when ACTUATOR_TOKEN is present")
    void testBearerTokenAuth() throws Exception {
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        Map<String, String> env =
                Map.of("ACTUATOR_TOKEN", "bearer_token_123", "ACTUATOR_INSECURE", "true");
        ActuatorToolHandler handler =
                new ActuatorToolHandler(
                        (url, auth) -> {
                            assertThat(auth.bearerToken()).isEqualTo("bearer_token_123");
                            assertThat(auth.insecure()).isTrue();
                            return mockClient;
                        },
                        env::get);

        Map<String, Object> result =
                handler.execute("get_actuator_health", Map.of("url", "http://localhost:8080"));
        assertThat(result.get("isError")).isNull();
    }

    @Test
    @DisplayName("execute throws on unknown actuator tool")
    void testUnknownTool() {
        ActuatorToolHandler handler = new ActuatorToolHandler((url, auth) -> mockClient);

        assertThatThrownBy(
                        () ->
                                handler.execute(
                                        "unknown_actuator_tool",
                                        Map.of("url", "http://localhost:8080")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown actuator tool");
    }

    @Test
    @DisplayName("uses configured default URL when url is omitted")
    void testDefaultUrlWhenUrlIsMissing() throws Exception {
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        ActuatorToolHandler handler =
                new ActuatorToolHandler(
                        (url, auth) -> {
                            assertThat(url).isEqualTo("http://localhost:8080");
                            return mockClient;
                        },
                        key -> null,
                        "http://localhost:8080");

        handler.execute("get_actuator_health", Map.of());

        verify(mockClient).getHealth();
    }

    @Test
    @DisplayName("explicit URL overrides configured default URL")
    void testExplicitUrlOverridesDefault() throws Exception {
        when(mockClient.getHealth()).thenReturn("{\"status\":\"UP\"}");

        ActuatorToolHandler handler =
                new ActuatorToolHandler(
                        (url, auth) -> {
                            assertThat(url).isEqualTo("http://override:8080");
                            return mockClient;
                        },
                        key -> null,
                        "http://default:8080");

        handler.execute("get_actuator_health", Map.of("url", "http://override:8080"));

        verify(mockClient).getHealth();
    }
}
