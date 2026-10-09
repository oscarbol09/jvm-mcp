package dev.jvmmcp.core.spring;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ActuatorClientTest {

    private HttpServer server;
    private int port;
    private ActuatorClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        
        server.createContext("/actuator/health", exchange -> {
            String response = "{\"status\":\"UP\"}";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });
        
        server.createContext("/actuator/metrics", exchange -> {
            String response = "{\"names\":[\"jvm.memory.used\"]}";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.createContext("/actuator/metrics/jvm.memory.used", exchange -> {
            String response = "{\"name\":\"jvm.memory.used\",\"measurements\":[{\"value\":12345}]}";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.createContext("/actuator/startup", exchange -> {
            String response = "{\"timeline\":{\"startTime\":\"2026-10-09\"}}";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.createContext("/actuator/health/503", exchange -> {
            String response = "{\"status\":\"DOWN\"}";
            exchange.sendResponseHeaders(503, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.createContext("/actuator/broken", exchange -> {
            String response = "Internal Server Error";
            exchange.sendResponseHeaders(500, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.createContext("/actuator/metrics/jvm.memory.max", exchange -> {
            String response = "{\"name\":\"jvm.memory.max\",\"measurements\":[{\"value\":54321}]}";
            exchange.sendResponseHeaders(200, response.length());
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(response.getBytes());
            }
        });

        server.createContext("/actuator/tarpit", exchange -> {
            // Write 31MB of spaces to trigger the 30MB limit
            exchange.sendResponseHeaders(200, 31457280 + 1000);
            try (OutputStream os = exchange.getResponseBody()) {
                byte[] chunk = new byte[8192];
                java.util.Arrays.fill(chunk, (byte) ' ');
                for (int i = 0; i < 3900; i++) { // ~31.9 MB
                    os.write(chunk);
                }
            }
        });

        server.start();
        port = server.getAddress().getPort();
        client = new ActuatorClient("http://localhost:" + port, ActuatorAuth.NONE);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void testGetHealth() {
        String res = client.getHealth();
        assertThat(res).isEqualTo("{\"status\":\"UP\"}");
    }

    @Test
    void testGetMetricsBatch() {
        // Test CompletableFuture.allOf() handling multiple metrics concurrently
        java.util.List<String> metrics = java.util.Arrays.asList("jvm.memory.used", "jvm.memory.max");
        String res = client.getMetricsBatch(metrics);
        // It returns a JSON object combining them
        assertThat(res).contains("\"jvm.memory.used\"");
        assertThat(res).contains("12345");
        assertThat(res).contains("\"jvm.memory.max\"");
        assertThat(res).contains("54321");
    }

    @Test
    void testLimitingStringHandlerCancelsTarpit() {
        // Assert that the 30MB limit causes an IOException rather than OOM
        assertThatThrownBy(() -> client.fetchEndpoint("/actuator/tarpit"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("Response exceeded maximum length of 31457280 bytes");
    }

    @Test
    void testSemanticErrorMappers() {
        assertThatThrownBy(() -> client.fetchEndpoint("/actuator/notfound"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 404")
            .hasMessageContaining("management.endpoints.web.exposure.include");
    }

    @Test
    void testGetMetrics() {
        String res = client.getMetrics();
        assertThat(res).isEqualTo("{\"names\":[\"jvm.memory.used\"]}");
    }

    @Test
    void testGetSpecificMetric() {
        String res = client.getMetric("jvm.memory.used");
        assertThat(res).isEqualTo("{\"name\":\"jvm.memory.used\",\"measurements\":[{\"value\":12345}]}");
    }

    @Test
    void testGetStartup() {
        String res = client.getStartup();
        assertThat(res).isEqualTo("{\"timeline\":{\"startTime\":\"2026-10-09\"}}");
    }

    @Test
    void testHealthDownReturns503() {
        String res = client.fetchEndpoint("/actuator/health/503");
        assertThat(res).isEqualTo("{\"status\":\"DOWN\"}");
    }

    @Test
    void testErrorThrowsException() {
        assertThatThrownBy(() -> client.fetchEndpoint("/actuator/broken"))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("HTTP 500");
    }
}
