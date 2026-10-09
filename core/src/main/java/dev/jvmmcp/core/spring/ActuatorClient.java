package dev.jvmmcp.core.spring;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Optional;

/**
 * Lightweight HTTP client for reading Spring Boot Actuator endpoints (health, metrics, startup, etc.).
 * Supports basic and bearer authentication.
 */
public class ActuatorClient {

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
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return response.body() != null ? response.body() : "{}";
            } else {
                // If it's a 503 from /actuator/health, Spring boot returns 503 with the payload indicating DOWN
                if (response.statusCode() == 503 && targetPath.contains("/health")) {
                    return response.body() != null ? response.body() : "{}";
                }
                throw new RuntimeException("Failed to fetch " + targetUrl + " - HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while fetching " + targetUrl, e);
        } catch (Exception e) {
            if (e instanceof RuntimeException re) throw re;
            throw new RuntimeException("Error connecting to " + targetUrl + ": " + e.getMessage(), e);
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

    public String getStartup() {
        return fetchEndpoint("/actuator/startup");
    }
}
