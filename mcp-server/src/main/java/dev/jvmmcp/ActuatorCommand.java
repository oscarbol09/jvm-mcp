package dev.jvmmcp;

import dev.jvmmcp.core.spring.ActuatorAuth;
import dev.jvmmcp.core.spring.ActuatorClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;
import java.util.function.Function;

@Command(
    name = "actuator",
    mixinStandardHelpOptions = true,
    description = "Remotely inspect Spring Boot Actuator endpoints (health, metrics, startup)."
)
public class ActuatorCommand implements Callable<Integer> {

    @FunctionalInterface
    public interface ActuatorClientFactory {
        ActuatorClient create(String url, ActuatorAuth auth);
    }

    private final ActuatorClientFactory clientFactory;
    private final Function<String, String> envProvider;

    public ActuatorCommand() {
        this(ActuatorClient::new, System::getenv);
    }

    public ActuatorCommand(Function<String, String> envProvider) {
        this(ActuatorClient::new, envProvider);
    }

    public ActuatorCommand(ActuatorClientFactory clientFactory, Function<String, String> envProvider) {
        this.clientFactory = clientFactory;
        this.envProvider = envProvider;
    }

    @Option(names = {"--url"}, required = true, description = "Target Actuator Base URL (e.g. http://localhost:8080)")
    private String url;

    @Option(names = {"--user", "--actuator-user"}, description = "Basic Auth username")
    private String user;

    @Option(names = {"--password", "--actuator-password"}, description = "Basic Auth password")
    private String password;

    @Option(names = {"--token", "--actuator-token"}, description = "Bearer token")
    private String token;

    @Option(names = "--insecure", description = "Disable TLS certificate validation")
    private boolean insecure;

    @Parameters(
        index = "0",
        arity = "0..1",
        defaultValue = "health",
        description = "Endpoint to query: health, metrics, startup, or metrics/<name> (default: health)"
    )
    private String endpoint = "health";

    @Override
    public Integer call() {
        try {
            ActuatorAuth auth;
            if (token != null) {
                auth = ActuatorAuth.bearer(token, insecure);
            } else if (user != null || password != null) {
                String effectiveUser = user != null ? user : envProvider.apply("ACTUATOR_USER");
                String effectivePassword = password != null ? password : envProvider.apply("ACTUATOR_PASSWORD");
                auth = ActuatorAuth.basic(effectiveUser, effectivePassword, insecure);
            } else {
                String envToken = envProvider.apply("ACTUATOR_TOKEN");
                String envUser = envProvider.apply("ACTUATOR_USER");
                String envPassword = envProvider.apply("ACTUATOR_PASSWORD");
                auth = new ActuatorAuth(envUser, envPassword, envToken, insecure);
            }
                
            auth.validate().ifPresent(error -> {
                throw new IllegalArgumentException(error);
            });

            if (auth.hasCredentials() && !url.startsWith("https") && !isLoopbackUrl(url)) {
                System.err.println("WARNING: Sending Actuator credentials over unencrypted HTTP.");
            }

            ActuatorClient client = clientFactory.create(url, auth);
            
            String result;
            if ("health".equalsIgnoreCase(endpoint)) {
                result = client.getHealth();
            } else if ("startup".equalsIgnoreCase(endpoint)) {
                result = client.getStartup();
            } else if ("metrics".equalsIgnoreCase(endpoint)) {
                result = client.getMetrics();
            } else if (endpoint.toLowerCase().startsWith("metrics/")) {
                String metricName = endpoint.substring("metrics/".length());
                if (metricName.contains(",")) {
                    java.util.List<String> metricList = java.util.Arrays.stream(metricName.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
                    result = client.getMetricsBatch(metricList);
                } else {
                    result = client.getMetric(metricName);
                }
            } else {
                result = client.fetchEndpoint(endpoint);
            }

            System.out.println(result);
            return 0;

        } catch (Exception e) {
            System.err.println("Error inspecting Actuator: " + e.getMessage());
            return 1;
        }
    }

    private static boolean isLoopbackUrl(String url) {
        if (url == null) return false;
        try {
            java.net.URI uri = java.net.URI.create(url.trim());
            String host = uri.getHost();
            if (host == null) return false;
            String cleanHost = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            if (cleanHost.equalsIgnoreCase("localhost")
                || cleanHost.equals("127.0.0.1")
                || cleanHost.equals("::1")
                || cleanHost.equals("0:0:0:0:0:0:0:1")) {
                return true;
            }
            java.net.InetAddress[] addrs = java.net.InetAddress.getAllByName(cleanHost);
            for (java.net.InetAddress addr : addrs) {
                if (!addr.isLoopbackAddress()) {
                    return false;
                }
            }
            return addrs.length > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
