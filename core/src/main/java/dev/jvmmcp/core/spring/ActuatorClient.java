package dev.jvmmcp.core.spring;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Lightweight HTTP client for reading Spring Boot Actuator endpoints (health, metrics, startup, etc.).
 * Supports basic and bearer authentication.
 */
public class ActuatorClient {

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
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static HttpClient buildHttpClient(ActuatorAuth auth) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(3000));
        if (auth.insecure()) {
            builder.sslContext(trustAllSslContext());
        }
        return builder.build();
    }

    private static SSLContext trustAllSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
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
            .header("Accept", "application/json");
        auth.authorizationHeader().ifPresent(value -> builder.header("Authorization", value));
        return builder;
    }

    private static HttpResponse.BodyHandler<String> limitingStringHandler(long maxBytes) {
        return responseInfo -> {
            responseInfo.headers().firstValueAsLong("Content-Length").ifPresent(len -> {
                if (len > maxBytes) {
                    throw new IllegalArgumentException("Content-Length " + len + " exceeds limit " + maxBytes);
                }
            });

            HttpResponse.BodySubscriber<String> downstream = HttpResponse.BodySubscribers.ofString(StandardCharsets.UTF_8);
            
            return new HttpResponse.BodySubscriber<String>() {
                private long totalReceived = 0;
                private Flow.Subscription subscription;

                @Override
                public void onSubscribe(Flow.Subscription subscription) {
                    this.subscription = subscription;
                    downstream.onSubscribe(subscription);
                }

                @Override
                public void onNext(List<ByteBuffer> item) {
                    long batchSize = 0;
                    for (ByteBuffer b : item) {
                        batchSize += b.remaining();
                    }
                    totalReceived += batchSize;

                    if (totalReceived > maxBytes) {
                        subscription.cancel();
                        downstream.onError(new IOException("Response size exceeded maximum limit of " + maxBytes + " bytes"));
                        return;
                    }
                    downstream.onNext(item);
                }

                @Override
                public void onError(Throwable throwable) {
                    downstream.onError(throwable);
                }

                @Override
                public void onComplete() {
                    downstream.onComplete();
                }

                @Override
                public CompletionStage<String> getBody() {
                    return downstream.getBody();
                }
            };
        };
    }

    /**
     * Fetches the specified actuator endpoint.
     * @param endpoint The path relative to the base URL, or starting with /actuator (e.g., "/actuator/health", "/actuator/metrics")
     * @return The raw JSON response from the endpoint.
     * @throws RuntimeException if the endpoint is unreachable or returns a non-200/503 status code.
     */
    public String fetchEndpoint(String endpoint) {
        String targetPath = endpoint.startsWith("/") ? endpoint : "/" + endpoint;
        String targetUrl = baseUrl + targetPath;
        
        try {
            HttpRequest request = newRequest(targetUrl).GET().build();
            HttpResponse<String> response = httpClient.send(request, limitingStringHandler(MAX_PAYLOAD_SIZE));
            
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
        return fetchEndpoint("/actuator/metrics/" + metricName);
    }

    public String getMetricsBatch(List<String> metricNames) {
        if (metricNames == null || metricNames.isEmpty()) {
            return getMetrics();
        }

        List<CompletableFuture<String>> futures = metricNames.stream()
            .map(name -> {
                String targetUrl = baseUrl + "/actuator/metrics/" + name;
                HttpRequest request = newRequest(targetUrl).GET().build();

                return httpClient.sendAsync(request, limitingStringHandler(MAX_PAYLOAD_SIZE))
                    .thenApply(response -> {
                        if (response.statusCode() == 200) {
                            return "\"" + name + "\": " + response.body();
                        } else if (response.statusCode() == 404) {
                            return "\"" + name + "\": null";
                        } else {
                            throw new RuntimeException("HTTP " + response.statusCode());
                        }
                    })
                    .exceptionally(ex -> {
                        System.err.println("Failed to fetch metric " + name + ": " + ex.getMessage());
                        return "\"" + name + "\": {\"error\": \"Failed to fetch\"}"; 
                    });
            })
            .toList();

        CompletableFuture<Void> allFutures = CompletableFuture.allOf(
            futures.toArray(new CompletableFuture[0])
        ).orTimeout(10, TimeUnit.SECONDS);

        try {
            allFutures.join();
        } catch (Exception ex) {
            System.err.println("Batch metrics operation timed out: " + ex.getMessage());
        }

        return futures.stream()
            .map(CompletableFuture::join)
            .filter(Objects::nonNull)
            .collect(Collectors.joining(", ", "{", "}"));
    }

    public String getStartup() {
        return fetchEndpoint("/actuator/startup");
    }
}
