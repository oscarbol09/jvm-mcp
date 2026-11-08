package dev.jvmmcp.core.spring;

import com.sun.tools.attach.VirtualMachine;
import java.net.ConnectException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;

public class ActuatorProbe {

    private static final long MAX_PAYLOAD_SIZE = 30L * 1024 * 1024;

    private static final Pattern COMMAND_LINE_PORT =
            Pattern.compile("(?:^|\\s)(?:--|-D)(management\\.server\\.port|server\\.port)=(\\S+)");

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
        HttpClient.Builder builder =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(1500))
                        .followRedirects(HttpClient.Redirect.NEVER);

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

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {}

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {}

        @Override
        public void checkClientTrusted(
                X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public void checkServerTrusted(
                X509Certificate[] chain, String authType, SSLEngine engine) {}

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    private HttpRequest.Builder newRequest(String urlStr, Duration timeout) {
        try {
            URI originalUri = URI.create(urlStr);
            String host = originalUri.getHost();

            if (host == null) {
                throw new SecurityException("Invalid URL: No host provided");
            }

            ActuatorClient.validateLoopbackHost(host);

            HttpRequest.Builder builder =
                    HttpRequest.newBuilder()
                            .uri(originalUri)
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

        Map<Integer, Boolean> candidatePorts = extractCandidatePorts(vm);

        for (Map.Entry<Integer, Boolean> candidate : candidatePorts.entrySet()) {
            int port = candidate.getKey();
            Optional<ProbeHit> hit = probePortInternal(port, candidate.getValue());

            if (hit.isPresent()) {
                return Optional.of(
                        new ProbeResult(
                                hit.get().scheme() + "://127.0.0.1:" + port + "/actuator/beans",
                                hit.get().body()));
            }
        }

        return Optional.empty();
    }

    public Optional<String> probePort(int port) {
        return probePortInternal(port, false).map(ProbeHit::body);
    }

    private Optional<ProbeHit> probePortInternal(int port, boolean preferHttps) {
        String[] candidatePaths = {"/actuator/beans", "/beans"};
        List<String> schemes = preferHttps ? List.of("https", "http") : List.of("http", "https");

        for (String scheme : schemes) {
            for (String path : candidatePaths) {
                String url = scheme + "://127.0.0.1:" + port + path;

                try {
                    HttpRequest request = newRequest(url, Duration.ofMillis(1500)).GET().build();
                    HttpResponse<String> response =
                            httpClient.send(
                                    request,
                                    HttpPayloadHandlers.limitingStringHandler(MAX_PAYLOAD_SIZE));

                    if (response.statusCode() == 200
                            && response.body() != null
                            && response.body().contains("beans")) {
                        return Optional.of(new ProbeHit(scheme, response.body()));
                    }
                } catch (ConnectException e) {
                    return Optional.empty();
                } catch (Exception ignored) {
                }
            }
        }

        return Optional.empty();
    }

    public Optional<String> fetchFromUrl(String baseUrl) {
        for (String targetUrl : candidateBeansUrls(baseUrl)) {
            try {
                HttpRequest request = newRequest(targetUrl, Duration.ofSeconds(3)).GET().build();
                HttpResponse<String> response =
                        httpClient.send(
                                request,
                                HttpPayloadHandlers.limitingStringHandler(MAX_PAYLOAD_SIZE));

                if (response.statusCode() == 200 && response.body() != null) {
                    return Optional.of(response.body());
                }
            } catch (Exception ignored) {
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

    static String commandLineProperty(String command, String property) {
        if (command == null || command.isBlank()) {
            return null;
        }

        Pattern pattern =
                Pattern.compile("(?:^|\\s)(?:--|-D)" + Pattern.quote(property) + "=(\\S+)");
        Matcher matcher = pattern.matcher(command);
        String value = null;

        while (matcher.find()) {
            value = matcher.group(1);
        }

        return value;
    }

    private Map<Integer, Boolean> extractCandidatePorts(VirtualMachine vm) {
        Map<Integer, Boolean> ports = new LinkedHashMap<>();

        try {
            Properties sysProps = vm.getSystemProperties();
            Properties agentProps = vm.getAgentProperties();

            if (sysProps == null) {
                sysProps = new Properties();
            }

            if (agentProps == null) {
                agentProps = new Properties();
            }

            String command = sysProps.getProperty("sun.java.command");

            String serverSslValue = commandLineProperty(command, "server.ssl.enabled");
            if (serverSslValue == null) {
                serverSslValue = sysProps.getProperty("server.ssl.enabled");
            }
            boolean serverSsl = isTrue(serverSslValue);

            String mgmtSslValue = commandLineProperty(command, "management.server.ssl.enabled");
            if (mgmtSslValue == null) {
                mgmtSslValue = sysProps.getProperty("management.server.ssl.enabled");
            }
            boolean mgmtSsl = mgmtSslValue != null ? isTrue(mgmtSslValue) : serverSsl;

            addPortFromProperty(ports, sysProps.getProperty("management.server.port"), mgmtSsl);
            addPortFromProperty(ports, sysProps.getProperty("server.port"), serverSsl);
            addPortFromProperty(ports, sysProps.getProperty("local.management.port"), mgmtSsl);
            addPortFromProperty(ports, sysProps.getProperty("local.server.port"), serverSsl);
            addPortFromProperty(ports, agentProps.getProperty("management.server.port"), mgmtSsl);
            addPortFromProperty(ports, agentProps.getProperty("server.port"), serverSsl);

            addCommandLinePorts(ports, command, mgmtSsl, serverSsl);
        } catch (Exception ignored) {
        }

        ports.putIfAbsent(8080, false);
        ports.putIfAbsent(8081, false);
        ports.putIfAbsent(9090, false);

        return ports;
    }

    private void addCommandLinePorts(
            Map<Integer, Boolean> ports, String command, boolean mgmtSsl, boolean serverSsl) {
        if (command == null || command.isBlank()) {
            return;
        }

        List<String> management = new ArrayList<>();
        List<String> server = new ArrayList<>();
        Matcher matcher = COMMAND_LINE_PORT.matcher(command);

        while (matcher.find()) {
            if (matcher.group(1).startsWith("management")) {
                management.add(matcher.group(2));
            } else {
                server.add(matcher.group(2));
            }
        }

        for (String port : management) {
            addPortFromProperty(ports, port, mgmtSsl);
        }

        for (String port : server) {
            addPortFromProperty(ports, port, serverSsl);
        }
    }

    static List<String> portsFromCommandLine(String command) {
        if (command == null) {
            return List.of();
        }

        List<String> management = new ArrayList<>();
        List<String> server = new ArrayList<>();
        Matcher matcher = COMMAND_LINE_PORT.matcher(command);

        while (matcher.find()) {
            if (matcher.group(1).startsWith("management")) {
                management.add(matcher.group(2));
            } else {
                server.add(matcher.group(2));
            }
        }

        management.addAll(server);
        return management;
    }

    private void addPortFromProperty(Map<Integer, Boolean> ports, String portStr, boolean sslHint) {
        if (portStr != null && !portStr.isBlank()) {
            try {
                int port = Integer.parseInt(portStr.trim());
                if (port > 0 && port <= 65535) {
                    ports.merge(port, sslHint, Boolean::logicalOr);
                }
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private static boolean isTrue(String value) {
        return value != null && Boolean.parseBoolean(value.trim());
    }

    public record ProbeResult(String url, String rawJson) {}

    private record ProbeHit(String scheme, String body) {}
}
