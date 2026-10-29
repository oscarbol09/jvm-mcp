package dev.jvmmcp.core.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ActuatorClientTest {

    private HttpServer server;
    private ExecutorService serverExecutor;
    private int port;
    private ActuatorClient client;

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "//", "////", "/app", "/app/", "/app///"})
    void testBatchMetricsNormalizesTrailingSlashes(String suffix) {
        String expectedPath =
                (suffix.startsWith("/app") ? "/app" : "") + "/actuator/metrics/jvm.memory.used";
        List<String> paths = new CopyOnWriteArrayList<>();
        server.removeContext("/actuator/metrics/jvm.memory.used");
        server.createContext(
                expectedPath,
                exchange -> {
                    paths.add(exchange.getRequestURI().getRawPath());
                    byte[] response =
                            "{\"measurements\":[{\"value\":12345}]}"
                                    .getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                });

        String baseUrl = "http://127.0.0.1:" + port + suffix;
        try (HttpClient httpClient = HttpClient.newHttpClient()) {
            for (ActuatorClient batchClient :
                    List.of(
                            new ActuatorClient(baseUrl, ActuatorAuth.NONE),
                            new ActuatorClient(baseUrl, ActuatorAuth.NONE, httpClient))) {
                assertThat(batchClient.getMetricsBatch(List.of("jvm.memory.used")))
                        .isEqualTo("{\"jvm.memory.used\": {\"measurements\":[{\"value\":12345}]}}");
            }
        }
        assertThat(paths).containsExactly(expectedPath, expectedPath);
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);

        server.createContext(
                "/actuator/health",
                exchange -> {
                    String response = "{\"status\":\"UP\"}";
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });

        server.createContext(
                "/actuator/metrics",
                exchange -> {
                    String response = "{\"names\":[\"jvm.memory.used\"]}";
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });

        server.createContext(
                "/actuator/metrics/jvm.memory.used",
                exchange -> {
                    String response =
                            "{\"name\":\"jvm.memory.used\",\"measurements\":[{\"value\":12345}]}";
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });

        server.createContext(
                "/actuator/startup",
                exchange -> {
                    String response = "{\"timeline\":{\"startTime\":\"2026-10-09\"}";
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });

        server.createContext(
                "/actuator/health/503",
                exchange -> {
                    String response = "{\"status\":\"DOWN\"}";
                    exchange.sendResponseHeaders(503, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });

        server.createContext(
                "/actuator/broken",
                exchange -> {
                    String response = "Internal Server Error";
                    exchange.sendResponseHeaders(500, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });

        server.createContext(
                "/actuator/metrics/jvm.memory.max",
                exchange -> {
                    String response =
                            "{\"name\":\"jvm.memory.max\",\"measurements\":[{\"value\":54321}]}";
                    exchange.sendResponseHeaders(200, response.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response.getBytes());
                    }
                });

        server.createContext(
                "/actuator/tarpit",
                exchange -> {
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
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
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
        java.util.List<String> metrics =
                java.util.Arrays.asList("jvm.memory.used", "jvm.memory.max");
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
                .hasMessageContaining("Content-Length 31458280 exceeds limit 31457280");
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
        assertThat(res)
                .isEqualTo("{\"name\":\"jvm.memory.used\",\"measurements\":[{\"value\":12345}]}");
    }

    @Test
    void testGetStartup() {
        String res = client.getStartup();
        assertThat(res).isEqualTo("{\"timeline\":{\"startTime\":\"2026-10-09\"}");
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

    @Test
    void testSSRFProtectionRejectsNonLoopback() {
        assertThatThrownBy(() -> new ActuatorClient("http://google.com:8080", ActuatorAuth.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection");

        assertThatThrownBy(() -> new ActuatorClient("http://192.168.1.1:8080", ActuatorAuth.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection");
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.2", "127.0.1.1", "127.255.255.254", "::1", "0:0:0:0:0:0:0:1"})
    void testSSRFProtectionAcceptsLoopbackAddresses(String host) {
        String uriHost = host.contains(":") ? "[" + host + "]" : host;

        assertThat(new ActuatorClient("http://" + uriHost + ":8080", ActuatorAuth.NONE))
                .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.0.0.0", "::", "169.254.169.254", "192.0.2.1", "2001:db8::1"})
    void testSSRFProtectionRejectsNonLoopbackAddresses(String host) {
        String uriHost = host.contains(":") ? "[" + host + "]" : host;

        assertThatThrownBy(
                        () -> new ActuatorClient("http://" + uriHost + ":8080", ActuatorAuth.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a loopback address");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[   ]"})
    void testSSRFProtectionRejectsEmptyBracketedHost(String host) {
        assertThatThrownBy(() -> ActuatorClient.validateLoopbackHost(host))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Host cannot be empty");
    }

    @Test
    void testSSRFProtectionRejectsMixedLoopbackAndNonLoopbackDnsAnswers() throws Exception {
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        InetAddress nonLoopback = InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 1});

        assertThatThrownBy(
                        () ->
                                ActuatorClient.validateLoopbackHost(
                                        "mixed.example",
                                        host -> new InetAddress[] {loopback, nonLoopback}))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a loopback address");
    }

    @Test
    void testSSRFProtectionRejectsEmptyDnsAnswers() {
        assertThatThrownBy(
                        () ->
                                ActuatorClient.validateLoopbackHost(
                                        "empty.example", host -> new InetAddress[0]))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no addresses");
    }

    @Test
    void testSSRFProtectionRevalidatesHostBeforeSendingRequest() throws Exception {
        AtomicInteger resolutionCount = new AtomicInteger();
        AtomicInteger requestCount = new AtomicInteger();
        InetAddress loopback = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
        InetAddress nonLoopback = InetAddress.getByAddress(new byte[] {(byte) 192, 0, 2, 1});

        server.createContext(
                "/actuator/rebinding-test",
                exchange -> {
                    requestCount.incrementAndGet();
                    byte[] response = "{\"status\":\"UP\"}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                });

        try (HttpClient customHttpClient = HttpClient.newHttpClient()) {
            ActuatorClient revalidatingClient =
                    new ActuatorClient(
                            "http://localhost:" + port,
                            ActuatorAuth.NONE,
                            customHttpClient,
                            host ->
                                    resolutionCount.getAndIncrement() == 0
                                            ? new InetAddress[] {loopback}
                                            : new InetAddress[] {nonLoopback});

            assertThatThrownBy(() -> revalidatingClient.fetchEndpoint("/actuator/rebinding-test"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a loopback address");

            assertThatThrownBy(() -> revalidatingClient.getMetricsBatch(List.of("jvm.memory.used")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a loopback address");

            assertThat(resolutionCount.get()).isEqualTo(3);
            assertThat(requestCount.get()).isZero();
        }
    }

    @Test
    void testInsecureAuthLogsWarningAndBuildsClient() {
        ActuatorAuth insecureAuth = ActuatorAuth.basic("admin", "admin", true);
        ActuatorClient insecureClient =
                new ActuatorClient("http://127.0.0.1:" + port, insecureAuth);
        assertThat(insecureClient).isNotNull();
    }

    @Test
    void testMetricsBatchEscapesSpecialCharacters() {
        java.util.List<String> metrics = java.util.List.of("metric\"with\"quotes");
        String res = client.getMetricsBatch(metrics);
        assertThat(res).contains("\"metric\\\"with\\\"quotes\"");
    }

    @Test
    void testSSRFProtectionRejectsNonHttpSchemes() {
        assertThatThrownBy(() -> new ActuatorClient("file:///etc/passwd", ActuatorAuth.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection: URL scheme must be http or https");

        assertThatThrownBy(() -> new ActuatorClient("ftp://localhost:21", ActuatorAuth.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection: URL scheme must be http or https");
    }

    @Test
    void testPathTraversalRejectedInFetchEndpoint() {
        assertThatThrownBy(() -> client.fetchEndpoint("/actuator/../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection: Path traversal");

        assertThatThrownBy(() -> client.fetchEndpoint("/actuator/..\\secret"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection: Path traversal");
    }

    @Test
    void testSSRFProtectionRejectsUserInfoQueryAndFragment() {
        assertThatThrownBy(
                        () ->
                                new ActuatorClient(
                                        "http://user:pass@localhost:8080", ActuatorAuth.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection: UserInfo not permitted");

        assertThatThrownBy(
                        () ->
                                new ActuatorClient(
                                        "http://localhost:8080/actuator?admin=true",
                                        ActuatorAuth.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection: Query parameters not permitted");

        assertThatThrownBy(
                        () ->
                                new ActuatorClient(
                                        "http://localhost:8080/actuator#section",
                                        ActuatorAuth.NONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("SSRF Protection: URI fragments not permitted");
    }

    @Test
    void testGzipResponseDecompression() throws IOException {
        String json = "{\"status\":\"UP\",\"details\":{\"diskSpace\":{\"status\":\"UP\"}}}";
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzos = new java.util.zip.GZIPOutputStream(baos)) {
            gzos.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        byte[] gzippedBytes = baos.toByteArray();

        server.createContext(
                "/actuator/compressed-health",
                exchange -> {
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.getResponseHeaders().set("Content-Encoding", "gzip");
                    exchange.sendResponseHeaders(200, gzippedBytes.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(gzippedBytes);
                    }
                });

        String result = client.fetchEndpoint("/actuator/compressed-health");
        assertThat(result).isEqualTo(json);
    }

    @Test
    void testBatchMetricsPreservesSuccessfulMetricsWhenOneStalls() {
        // Warm up connection to ensure jvm.memory.used completes well before timeout
        client.getMetric("jvm.memory.used");

        CountDownLatch releaseLatch = new CountDownLatch(1);
        CountDownLatch requestReceivedLatch = new CountDownLatch(1);
        String stalledContext = "/actuator/metrics/stalled.one";

        server.createContext(
                stalledContext,
                exchange -> {
                    requestReceivedLatch.countDown();
                    try {
                        releaseLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] response = "{\"value\":999}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                });

        try {
            long start = System.nanoTime();
            String res =
                    client.getMetricsBatch(
                            List.of("jvm.memory.used", "stalled.one"), Duration.ofMillis(500));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(elapsedMillis).isLessThan(4000);
            assertThat(res).startsWith("{").endsWith("}");
            assertThat(res).contains("\"jvm.memory.used\"");
            assertThat(res).contains("12345");
            assertThat(res).contains("\"stalled.one\": {\"error\": \"Request timed out\"}");
        } finally {
            releaseLatch.countDown();
            server.removeContext(stalledContext);
        }
    }

    @Test
    void testBatchMetricsReturnsTimeoutErrorsWhenAllRequestsStall() {
        CountDownLatch releaseLatch = new CountDownLatch(1);
        String stalledContext1 = "/actuator/metrics/stalled.alpha";
        String stalledContext2 = "/actuator/metrics/stalled.beta";

        server.createContext(
                stalledContext1,
                exchange -> {
                    try {
                        releaseLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] response = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                });

        server.createContext(
                stalledContext2,
                exchange -> {
                    try {
                        releaseLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] response = "{\"value\":2}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                });

        try {
            long start = System.nanoTime();
            String res =
                    client.getMetricsBatch(
                            List.of("stalled.alpha", "stalled.beta"), Duration.ofMillis(200));
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(elapsedMillis).isLessThan(3000);
            assertThat(res).startsWith("{").endsWith("}");
            assertThat(res).contains("\"stalled.alpha\": {\"error\": \"Request timed out\"}");
            assertThat(res).contains("\"stalled.beta\": {\"error\": \"Request timed out\"}");
        } finally {
            releaseLatch.countDown();
            server.removeContext(stalledContext1);
            server.removeContext(stalledContext2);
        }
    }

    @Test
    void testBatchMetricsTimeoutPreservesSpecialCharacterEscaping() {
        CountDownLatch releaseLatch = new CountDownLatch(1);
        String rawMetricName = "stalled\"with\"quotes";
        String stalledContext = "/actuator/metrics/" + rawMetricName;

        server.createContext(
                stalledContext,
                exchange -> {
                    try {
                        releaseLatch.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    byte[] response = "{\"value\":1}".getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200, response.length);
                    try (OutputStream os = exchange.getResponseBody()) {
                        os.write(response);
                    }
                });

        try {
            String res = client.getMetricsBatch(List.of(rawMetricName), Duration.ofMillis(200));
            assertThat(res).startsWith("{").endsWith("}");
            assertThat(res).contains("\"stalled\\\"with\\\"quotes\"");
            assertThat(res).contains("{\"error\": \"Request timed out\"}");
        } finally {
            releaseLatch.countDown();
            server.removeContext(stalledContext);
        }
    }
}
