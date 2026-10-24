package dev.jvmmcp.core.spring;

import com.sun.net.httpserver.HttpServer;
import com.sun.tools.attach.VirtualMachine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ActuatorProbeCompressionTest {

    private static final String BEANS_JSON = "{\"contexts\":{\"app\":{\"beans\":{\"service\":{}}}}}";

    private HttpServer server;
    private int port;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        port = server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private static byte[] compressed(String content, String encoding) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (OutputStream compressor = encoding.equals("gzip")
                ? new GZIPOutputStream(output)
                : new DeflaterOutputStream(output)) {
            compressor.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return output.toByteArray();
    }

    private void serve(String path, String encoding, byte[] body, AtomicReference<String> acceptEncoding) {
        server.createContext(path, exchange -> {
            if (acceptEncoding != null) {
                acceptEncoding.set(exchange.getRequestHeaders().getFirst("Accept-Encoding"));
            }
            if (encoding != null) {
                exchange.getResponseHeaders().set("Content-Encoding", encoding);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream stream = exchange.getResponseBody()) {
                stream.write(body);
            }
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "deflate"})
    void probePortReadsCompressedBeans(String encoding) throws IOException {
        AtomicReference<String> acceptEncoding = new AtomicReference<>();
        serve("/actuator/beans", encoding, compressed(BEANS_JSON, encoding), acceptEncoding);

        assertThat(new ActuatorProbe().probePort(port)).contains(BEANS_JSON);
        assertThat(acceptEncoding.get()).isEqualTo("gzip, deflate");
    }

    @ParameterizedTest
    @ValueSource(strings = {"gzip", "deflate"})
    void fetchFromUrlReadsCompressedBeansOnFallbackPath(String encoding) throws IOException {
        server.createContext("/actuator/beans", exchange -> {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        serve("/beans", encoding, compressed(BEANS_JSON, encoding), null);

        assertThat(new ActuatorProbe().fetchFromUrl("http://127.0.0.1:" + port))
            .contains(BEANS_JSON);
    }

    @Test
    void autoDiscoverReadsCompressedBeans() throws Exception {
        serve("/actuator/beans", "gzip", compressed(BEANS_JSON, "gzip"), null);
        VirtualMachine vm = mock(VirtualMachine.class);
        Properties properties = new Properties();
        properties.setProperty("management.server.port", Integer.toString(port));
        when(vm.getSystemProperties()).thenReturn(properties);
        when(vm.getAgentProperties()).thenReturn(new Properties());

        assertThat(new ActuatorProbe().probe(vm))
            .hasValueSatisfying(result -> {
                assertThat(result.url()).contains(":" + port + "/actuator/beans");
                assertThat(result.rawJson()).isEqualTo(BEANS_JSON);
            });
    }

    @Test
    void malformedCompressedResponseStillTriesNextPath() throws IOException {
        serve("/actuator/beans", "gzip", "not a gzip stream".getBytes(StandardCharsets.UTF_8), null);
        serve("/beans", "deflate", compressed(BEANS_JSON, "deflate"), null);

        assertThat(new ActuatorProbe().probePort(port)).contains(BEANS_JSON);
    }

    @Test
    void uncompressedResponsesStillWork() {
        serve("/actuator/beans", null, BEANS_JSON.getBytes(StandardCharsets.UTF_8), null);

        assertThat(new ActuatorProbe().probePort(port)).contains(BEANS_JSON);
    }

    @Test
    void sharedHandlerRejectsOversizedDecompressedPayload() throws Exception {
        serve("/big", "gzip", compressed("x".repeat(4096), "gzip"), null);

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/big"))
                .GET().build();

            assertThatThrownBy(() -> client.send(request, HttpPayloadHandlers.limitingStringHandler(256)))
                .hasStackTraceContaining("Decompressed response size exceeded maximum limit of 256 bytes");
        }
    }

    @Test
    void sharedHandlerStillRejectsOversizedContentLength() {
        serve("/big", null, "x".repeat(1024).getBytes(StandardCharsets.UTF_8), null);

        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/big"))
                .GET().build();

            assertThatThrownBy(() -> client.send(request, HttpPayloadHandlers.limitingStringHandler(256)))
                .hasStackTraceContaining("Content-Length 1024 exceeds limit 256");
        }
    }
}
