package dev.jvmmcp.core.spring;

import com.sun.tools.attach.VirtualMachine;

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
import java.util.*;

/**
 * Probes a running Spring Boot application for exposed Actuator HTTP endpoints via discovered system properties.
 */
public class ActuatorProbe {

    private final HttpClient httpClient;
    private final ActuatorAuth auth;

    public ActuatorProbe() {
        this(ActuatorAuth.NONE);
    }

    public ActuatorProbe(ActuatorAuth auth) {
        this.auth = auth == null ? ActuatorAuth.NONE : auth;
        this.httpClient = buildHttpClient(this.auth);
    }

    public ActuatorProbe(HttpClient httpClient) {
        this(httpClient, ActuatorAuth.NONE);
    }

    public ActuatorProbe(HttpClient httpClient, ActuatorAuth auth) {
        this.httpClient = httpClient;
        this.auth = auth == null ? ActuatorAuth.NONE : auth;
    }

    private static HttpClient buildHttpClient(ActuatorAuth auth) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1500));
        if (auth.insecure()) {
            builder.sslContext(trustAllSslContext());
        }
        return builder.build();
    }

    /**
     * Creates an SSL context that accepts any certificate and hostname. Only used when the user
     * explicitly passes --insecure, e.g. to test against self-signed certificates.
     */
    private static SSLContext trustAllSslContext() {
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[] {new TrustAllManager()}, null);
            return context;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Unable to initialise insecure TLS context", e);
        }
    }

    /**
     * Extends X509ExtendedTrustManager so the JDK also skips hostname verification.
     */
    private static final class TrustAllManager extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {}
        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    }

    private HttpRequest.Builder newRequest(String url, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(timeout)
            .header("Accept", "application/json");
        auth.authorizationHeader().ifPresent(value -> builder.header("Authorization", value));
        return builder;
    }

    public Optional<ProbeResult> probe(VirtualMachine vm) {
        if (vm == null) {
            return Optional.empty();
        }

        Set<Integer> candidatePorts = extractCandidatePorts(vm);
        for (int port : candidatePorts) {
            Optional<String> json = probePort(port);
            if (json.isPresent()) {
                return Optional.of(new ProbeResult("http://localhost:" + port + "/actuator/beans", json.get()));
            }
        }

        return Optional.empty();
    }

    public Optional<String> probePort(int port) {
        String[] candidatePaths = {
            "/actuator/beans",
            "/beans"
        };

        for (String path : candidatePaths) {
            String url = "http://localhost:" + port + path;
            try {
                HttpRequest request = newRequest(url, Duration.ofMillis(1500)).GET().build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body() != null && response.body().contains("beans")) {
                    return Optional.of(response.body());
                }
            } catch (Exception ignored) {
                // Connection refused or timeout means port/endpoint is not accessible
            }
        }

        return Optional.empty();
    }

    /**
     * Fetches the beans endpoint from a user-supplied URL. Accepts http or https, and either a full
     * beans URL (e.g. https://host/management/beans) or a base URL, in which case both
     * {@code /actuator/beans} and {@code /beans} (custom management base path) are tried.
     */
    public Optional<String> fetchFromUrl(String baseUrl) {
        for (String targetUrl : candidateBeansUrls(baseUrl)) {
            try {
                HttpRequest request = newRequest(targetUrl, Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200 && response.body() != null) {
                    return Optional.of(response.body());
                }
            } catch (Exception ignored) {
                // Try the next candidate path
            }
        }
        return Optional.empty();
    }

    static List<String> candidateBeansUrls(String baseUrl) {
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/beans")) {
            return List.of(trimmed);
        }
        String base = trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
        return List.of(base + "/actuator/beans", base + "/beans");
    }

    private Set<Integer> extractCandidatePorts(VirtualMachine vm) {
        Set<Integer> ports = new LinkedHashSet<>();
        try {
            Properties sysProps = vm.getSystemProperties();
            Properties agentProps = vm.getAgentProperties();

            addPortFromProperty(ports, sysProps.getProperty("management.server.port"));
            addPortFromProperty(ports, sysProps.getProperty("server.port"));
            addPortFromProperty(ports, sysProps.getProperty("local.management.port"));
            addPortFromProperty(ports, sysProps.getProperty("local.server.port"));

            addPortFromProperty(ports, agentProps.getProperty("management.server.port"));
            addPortFromProperty(ports, agentProps.getProperty("server.port"));
        } catch (Exception ignored) {}

        // Common default ports to test as fallback
        ports.add(8080);
        ports.add(8081);
        ports.add(9090);

        return ports;
    }

    private void addPortFromProperty(Set<Integer> ports, String portStr) {
        if (portStr != null && !portStr.isBlank()) {
            try {
                int port = Integer.parseInt(portStr.trim());
                if (port > 0 && port <= 65535) {
                    ports.add(port);
                }
            } catch (NumberFormatException ignored) {}
        }
    }

    public record ProbeResult(String url, String rawJson) {}
}
