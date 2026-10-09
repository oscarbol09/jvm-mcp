package dev.jvmmcp;

import dev.jvmmcp.core.spring.ActuatorAuth;
import dev.jvmmcp.core.spring.ActuatorClient;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

@Command(
    name = "actuator",
    description = "Remotely inspect Spring Boot Actuator endpoints (health, metrics, startup)."
)
public class ActuatorCommand implements Callable<Integer> {

    @Option(names = {"--url"}, required = true, description = "Target Actuator Base URL (e.g. http://localhost:8080)")
    private String url;

    @Option(names = "--user", description = "Basic Auth username")
    private String user;

    @Option(names = "--password", description = "Basic Auth password")
    private String password;

    @Option(names = "--token", description = "Bearer token")
    private String token;

    @Option(names = "--insecure", description = "Disable TLS certificate validation")
    private boolean insecure;

    @Parameters(index = "0", description = "Endpoint to query: health, metrics, startup, or metrics/<name>")
    private String endpoint;

    @Override
    public Integer call() {
        try {
            ActuatorAuth auth = token != null 
                ? ActuatorAuth.bearer(token, insecure) 
                : ActuatorAuth.basic(user, password, insecure);
                
            auth.validate().ifPresent(error -> {
                throw new IllegalArgumentException(error);
            });

            if (auth.hasCredentials() && !url.startsWith("https") && !url.contains("localhost")) {
                System.err.println("WARNING: Sending Actuator credentials over unencrypted HTTP.");
            }

            ActuatorClient client = new ActuatorClient(url, auth);
            
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
                    result = client.getMetricsBatch(java.util.Arrays.asList(metricName.split(",")));
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
}
