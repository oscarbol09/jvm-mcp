package dev.jvmmcp.core.spring;

import com.sun.tools.attach.VirtualMachine;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.net.URI;
import java.net.InetAddress;
import java.net.Inet6Address;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes a running Spring Boot application for exposed Actuator HTTP endpoints via discovered system properties.
 */
public class ActuatorProbe {

    private static final long MAX_PAYLOAD_SIZE = 30L * 1024 * 1024; // 30MB

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

    private HttpRequest.Builder newRequest(String urlStr, Duration timeout) {
        try {
            URI originalUri = URI.create(urlStr);
            String host = originalUri.getHost();
            if (host == null) {
                throw new SecurityException("Invalid URL: No host provided");
            }

            InetAddress[] addresses = InetAddress.getAllByName(host);
            for (InetAddress addr : addresses) {
                if (!addr.isLoopbackAddress()) {
                    throw new SecurityException("SSRF blocked: Host resolves to non-loopback IP " + addr.getHostAddress());
                }
            }

            String pinnedIp = addresses[0].getHostAddress();
            if (addresses[0] instanceof Inet6Address) {
                pinnedIp = "[" + pinnedIp + "]";
            }

            URI safeUri = new URI(
                originalUri.getScheme(),
                originalUri.getUserInfo(),
                pinnedIp,
                originalUri.getPort(),
                originalUri.getPath(),
                originalUri.getQuery(),
                originalUri.getFragment()
            );

            HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(safeUri)
                .timeout(timeout)
                .header("Accept", "application/json")
                .header("Accept-Encoding", "gzip, deflate");
            auth.authorizationHeader().ifPresent(value -> builder.header("Authorization", value));
            return builder;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create safe request: " + e.getMessage(), e);
        }
    }

    public Optional<ProbeResult> probe(VirtualMachine vm) {
        if (vm == null) {
            return Optional.empty();
        }

        Set<Integer> candidatePorts = extractCandidatePorts(vm);
        for (int port : candidatePorts) {
            Optional<String> json = probePort(port);
            if (json.isPresent()) {
                return Optional.of(new ProbeResult("http://127.0.0.1:" + port + "/actuator/beans", json.get()));
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
            String url = "http://127.0.0.1:" + port + path;
            try {
                HttpRequest request = newRequest(url, Duration.ofMillis(1500)).GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpPayloadHandlers.limitingStringHandler(MAX_PAYLOAD_SIZE));
                if (response.statusCode() == 200 && response.body() != null && response.body().contains("beans")) {
                    return Optional.of(response.body());
                }
            } catch (Exception ignored) {
                // Connection refused or timeout means port/endpoint is not accessible
            }
        }

        return Optional.empty();
    }

    public Optional<String> fetchFromUrl(String baseUrl) {
        for (String targetUrl : candidateBeansUrls(baseUrl)) {
            try {
                HttpRequest request = newRequest(targetUrl, Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> response = httpClient.send(request, HttpPayloadHandlers.limitingStringHandler(MAX_PAYLOAD_SIZE));
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

    // `java -jar app.jar --server.port=8085` sets no system property: the port is only in the
    // command line, which HotSpot exposes as sun.java.command.
    private static final Pattern COMMAND_LINE_PORT =
        Pattern.compile("(?:^|\\s)(?:--|-D)(management\\.server\\.port|server\\.port)=(\\S+)");

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

            for (String port : portsFromCommandLine(sysProps.getProperty("sun.java.command"))) {
                addPortFromProperty(ports, port);
            }
        } catch (Exception ignored) {}

        ports.add(8080);
        ports.add(8081);
        ports.add(9090);

        return ports;
    }

    /**
     * The {@code --management.server.port=} and {@code --server.port=} values (or their {@code -D}
     * forms) in a command line, management ports first, as the system properties are read.
     */
    static List<String> portsFromCommandLine(String command) {
        if (command == null) {
            return List.of();
        }
        List<String> management = new ArrayList<>();
        List<String> server = new ArrayList<>();
        Matcher m = COMMAND_LINE_PORT.matcher(command);
        while (m.find()) {
            (m.group(1).startsWith("management") ? management : server).add(m.group(2));
        }
        management.addAll(server);
        return management;
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

