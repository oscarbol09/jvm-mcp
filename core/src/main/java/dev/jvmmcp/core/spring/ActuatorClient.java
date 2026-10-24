package dev.jvmmcp.core.spring;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.lang.System.Logger;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Lightweight HTTP client for reading Spring Boot Actuator endpoints (health, metrics, startup, etc.).
 * Supports basic and bearer authentication.
 */
public class ActuatorClient {

    private static final Logger LOGGER = System.getLogger(ActuatorClient.class.getName());

    private static final long MAX_PAYLOAD_SIZE = 30L * 1024 * 1024; // 30MB
    private final String baseUrl;
    private final ActuatorAuth auth;
    private final HttpClient httpClient;

    public ActuatorClient(String baseUrl, ActuatorAuth auth) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.auth = auth == null ? ActuatorAuth.NONE : auth;
        this.httpClient = buildHttpClient(this.auth);
    }

    public ActuatorClient(String baseUrl, ActuatorAuth auth, HttpClient httpClient) {
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.auth = auth == null ? ActuatorAuth.NONE : auth;
        this.httpClient = httpClient;
    }

    private String normalizeBaseUrl(String url) {
        String trimmed = url.trim();
        URI uri = URI.create(trimmed);
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("SSRF Protection: URL scheme must be http or https (got: " + scheme + ")");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("SSRF Protection: UserInfo not permitted in Actuator base URL");
        }
        if (uri.getQuery() != null) {
            throw new IllegalArgumentException("SSRF Protection: Query parameters not permitted in Actuator base URL");
        }
        if (uri.getFragment() != null) {
            throw new IllegalArgumentException("SSRF Protection: URI fragments not permitted in Actuator base URL");
        }
        String host = uri.getHost();
        if (host == null) {
            throw new IllegalArgumentException("SSRF Protection: Malformed or missing host in URL");
        }
        String cleanHost = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        validateLoopbackHost(cleanHost);
        return trimmed.replaceAll("/+$", "");
    }

    public static void validateLoopbackHost(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("SSRF Protection: Host cannot be empty");
        }
        String cleanHost = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
        boolean isLiteralLoopback = cleanHost.equalsIgnoreCase("localhost")
            || cleanHost.equals("127.0.0.1")
            || cleanHost.equals("::1")
            || cleanHost.equals("0:0:0:0:0:0:0:1");

        if (!isLiteralLoopback) {
            throw new IllegalArgumentException("SSRF Protection: Actuator URLs must target localhost or loopback (got: " + host + ")");
        }

        try {
            java.net.InetAddress[] addresses = java.net.InetAddress.getAllByName(cleanHost);
            for (java.net.InetAddress addr : addresses) {
                if (!addr.isLoopbackAddress()) {
                    throw new IllegalArgumentException("SSRF Protection: Resolved address " + addr.getHostAddress() + " for host '" + host + "' is not a loopback address");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("SSRF Protection: Unable to resolve host '" + host + "': " + e.getMessage(), e);
        }
    }

    private static HttpClient buildHttpClient(ActuatorAuth auth) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(3000))
            .followRedirects(HttpClient.Redirect.NEVER);
        if (auth.insecure()) {
            LOGGER.log(Logger.Level.WARNING, "Insecure TLS mode enabled for Actuator. Server certificate verification is disabled.");
            builder.sslContext(trustAllSslContext());
        }
        return builder.build();
    }

    private static SSLContext trustAllSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, new TrustManager[] {new TrustAllManager()}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialise insecure TLS context", e);
        }
    }

    private static final class TrustAllManager extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private HttpRequest.Builder newRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(5))
            .header("Accept", "application/json")
            .header("Accept-Encoding", "gzip, deflate");
        auth.authorizationHeader().ifPresent(value -> builder.header("Authorization", value));
        return builder;
    }

    /**
     * Fetches the specified actuator endpoint.
     * @param endpoint The path relative to the base URL, or starting with /actuator (e.g., "/actuator/health", "/actuator/metrics")
     * @return The raw JSON response from the endpoint.
     * @throws RuntimeException if the endpoint is unreachable or returns a non-200/503 status code.
     */
    public String fetchEndpoint(String endpoint) {
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Endpoint cannot be null or empty");
        }
        String targetPath = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        if (targetPath.contains("..") || targetPath.contains("\\")) {
            throw new IllegalArgumentException("SSRF Protection: Path traversal sequences are prohibited in endpoint: " + endpoint);
        }
        URI baseUri = URI.create(baseUrl);
        URI resolvedUri = baseUri.resolve(URI.create(targetPath).normalize());
        if (!Objects.equals(resolvedUri.getScheme(), baseUri.getScheme())
            || !Objects.equals(resolvedUri.getHost(), baseUri.getHost())
            || resolvedUri.getPort() != baseUri.getPort()) {
            throw new IllegalArgumentException("SSRF Protection: Endpoint resolution deviated from base URL");
        }
        String targetUrl = resolvedUri.toString();
        
        try {
            HttpRequest request = newRequest(targetUrl).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpPayloadHandlers.limitingStringHandler(MAX_PAYLOAD_SIZE));
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body() != null ? response.body() : "{}";
            } else {
                // If it's a 503 from /actuator/health, Spring boot returns 503 with the payload indicating DOWN
                if (response.statusCode() == 503 && targetPath.contains("/health")) {
                    return response.body() != null ? response.body() : "{}";
                }
                handleSemanticError(response.statusCode(), targetUrl);
                throw new RuntimeException("Unreachable code");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching " + targetUrl, e);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("Error connecting to " + targetUrl + ": " + e.getMessage(), e);
        }
    }

    private void handleSemanticError(int statusCode, String targetUrl) {
        switch (statusCode) {
            case 401:
                throw new RuntimeException("HTTP 401 Unauthorized: Authentication is required to access '" + targetUrl + "'. Please provide valid credentials (e.g., Basic/Bearer) or adjust your SecurityFilterChain to permit access.");
            case 403:
                throw new RuntimeException("HTTP 403 Forbidden: You do not have permission to view '" + targetUrl + "'. Ensure your user has the required roles mapped in Spring Security.");
            case 404:
                throw new RuntimeException("HTTP 404 Not Found: Actuator endpoint '" + targetUrl + "' not found. Ensure it is enabled via 'management.endpoint.<id>.enabled=true' and exposed over HTTP via 'management.endpoints.web.exposure.include'. Verify 'management.endpoints.web.base-path' (default is /actuator).");
            case 503:
                throw new RuntimeException("HTTP 503 Service Unavailable: Gateway or service at '" + targetUrl + "' is temporarily offline.");
            default:
                throw new RuntimeException("Failed to fetch " + targetUrl + " - HTTP " + statusCode);
        }
    }

    public String getHealth() {
        return fetchEndpoint("/actuator/health");
    }

    public String getMetrics() {
        return fetchEndpoint("/actuator/metrics");
    }

    public String getMetric(String metricName) {
        return fetchEndpoint("/actuator/metrics/" + java.net.URLEncoder.encode(metricName, StandardCharsets.UTF_8));
    }

    private record MetricBatchTask(
        String name,
        CompletableFuture<HttpResponse<String>> httpFuture,
        CompletableFuture<String> resultFuture
    ) {}

    public String getMetricsBatch(List<String> metricNames) {
        return getMetricsBatch(metricNames, Duration.ofSeconds(10));
    }

    String getMetricsBatch(List<String> metricNames, Duration timeout) {
        if (metricNames == null || metricNames.isEmpty()) {
            return getMetrics();
        }

        Duration effectiveTimeout = (timeout == null || timeout.isNegative() || timeout.isZero())
            ? Duration.ofSeconds(10)
            : timeout;
        long timeoutMillis = Math.max(1L, effectiveTimeout.toMillis());

        List<MetricBatchTask> tasks = metricNames.stream()
            .map(name -> {
                String encodedName = java.net.URLEncoder.encode(name.trim(), StandardCharsets.UTF_8);
                String targetUrl = baseUrl + "/actuator/metrics/" + encodedName;
                HttpRequest request = newRequest(targetUrl).GET().build();

                CompletableFuture<HttpResponse<String>> httpFuture =
                    httpClient.sendAsync(request, HttpPayloadHandlers.limitingStringHandler(MAX_PAYLOAD_SIZE));

                CompletableFuture<String> resultFuture = httpFuture
                    .thenApply(response -> {
                        String jsonKey = dev.jvmmcp.core.util.SimpleJson.toJson(name);
                        if (response.statusCode() == 200) {
                            return jsonKey + ": " + response.body();
                        } else if (response.statusCode() == 404) {
                            return jsonKey + ": null";
                        } else {
                            throw new RuntimeException("HTTP " + response.statusCode());
                        }
                    })
                    .exceptionally(ex -> {
                        Throwable cause = ex instanceof java.util.concurrent.CompletionException ? ex.getCause() : ex;
                        if (cause instanceof java.util.concurrent.CancellationException
                                || cause instanceof java.util.concurrent.TimeoutException
                                || cause instanceof java.net.http.HttpTimeoutException) {
                            return dev.jvmmcp.core.util.SimpleJson.toJson(name) + ": {\"error\": \"Request timed out\"}";
                        }
                        LOGGER.log(Logger.Level.WARNING, "Failed to fetch metric {0}: {1}", name, ex.getMessage());
                        return dev.jvmmcp.core.util.SimpleJson.toJson(name) + ": {\"error\": \"Failed to fetch\"}"; 
                    });

                return new MetricBatchTask(name, httpFuture, resultFuture);
            })
            .toList();

        CompletableFuture<?>[] resultFutures = tasks.stream()
            .map(MetricBatchTask::resultFuture)
            .toArray(CompletableFuture[]::new);

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(resultFutures)
            .orTimeout(timeoutMillis, TimeUnit.MILLISECONDS);

        try {
            allFutures.join();
        } catch (Exception ex) {
            if (ex.getCause() instanceof InterruptedException || Thread.currentThread().isInterrupted()) {
                Thread.currentThread().interrupt();
            }
            LOGGER.log(Logger.Level.WARNING, "Batch metrics operation timed out: {0}", ex.getMessage());
        }

        for (MetricBatchTask task : tasks) {
            if (!task.resultFuture.isDone()) {
                task.resultFuture.complete(
                    dev.jvmmcp.core.util.SimpleJson.toJson(task.name) + ": {\"error\": \"Request timed out\"}"
                );
                try {
                    task.httpFuture.cancel(true);
                } catch (Exception ignored) {
                }
            }
        }

        return tasks.stream()
            .map(task -> {
                try {
                    return task.resultFuture.getNow(
                        dev.jvmmcp.core.util.SimpleJson.toJson(task.name) + ": {\"error\": \"Request timed out\"}"
                    );
                } catch (Exception e) {
                    return dev.jvmmcp.core.util.SimpleJson.toJson(task.name) + ": {\"error\": \"Failed to fetch\"}";
                }
            })
            .filter(Objects::nonNull)
            .collect(Collectors.joining(", ", "{", "}"));
    }

    public String getStartup() {
        return fetchEndpoint("/actuator/startup");
    }
}

