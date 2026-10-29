package dev.jvmmcp.server.tool;

import dev.jvmmcp.core.spring.ActuatorAuth;
import dev.jvmmcp.core.spring.ActuatorClient;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Handles Spring Boot Actuator tools: get_actuator_health, get_actuator_metrics,
 * get_actuator_startup.
 */
public class ActuatorToolHandler implements ToolHandler {

    @FunctionalInterface
    public interface ActuatorClientFactory {
        ActuatorClient create(String url, ActuatorAuth auth);
    }

    private final ActuatorClientFactory clientFactory;
    private final Function<String, String> envProvider;
    private final String defaultUrl;

    public ActuatorToolHandler() {
        this(ActuatorClient::new, System::getenv);
    }

    public ActuatorToolHandler(String defaultUrl) {
        this(ActuatorClient::new, System::getenv, defaultUrl);
    }

    public ActuatorToolHandler(ActuatorClientFactory clientFactory) {
        this(clientFactory, System::getenv);
    }

    public ActuatorToolHandler(
            ActuatorClientFactory clientFactory, Function<String, String> envProvider) {
        this(clientFactory, envProvider, null);
    }

    public ActuatorToolHandler(
            ActuatorClientFactory clientFactory,
            Function<String, String> envProvider,
            String defaultUrl) {
        this.clientFactory = clientFactory;
        this.envProvider = envProvider;
        this.defaultUrl = defaultUrl;
    }

    private List<String> requiredUrlArguments() {
        return defaultUrl == null || defaultUrl.isBlank() ? List.of("url") : List.of();
    }

    @Override
    public List<String> toolNames() {
        return List.of("get_actuator_health", "get_actuator_metrics", "get_actuator_startup");
    }

    @Override
    public List<Map<String, Object>> toolDefinitions() {
        return List.of(
                Map.of(
                        "name", "get_actuator_health",
                        "description", "Fetches Spring Boot Actuator /health endpoint",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "url",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                "Actuator Base URL (e.g. http://localhost:8080)")),
                                        "required", requiredUrlArguments())),
                Map.of(
                        "name", "get_actuator_metrics",
                        "description",
                                "Fetches Spring Boot Actuator /metrics endpoint. Optionally pass metricName to get a specific metric.",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "url",
                                                                Map.of(
                                                                        "type",
                                                                        "string",
                                                                        "description",
                                                                        "Actuator Base URL (e.g. http://localhost:8080)"),
                                                        "metrics",
                                                                Map.of(
                                                                        "type",
                                                                        "string",
                                                                        "description",
                                                                        "Optional comma-separated list of metric names (e.g. jvm.memory.used,jvm.threads.live)")),
                                        "required", requiredUrlArguments())),
                Map.of(
                        "name", "get_actuator_startup",
                        "description", "Fetches Spring Boot Actuator /startup endpoint",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "url",
                                                        Map.of(
                                                                "type",
                                                                "string",
                                                                "description",
                                                                "Actuator Base URL (e.g. http://localhost:8080)")),
                                        "required", requiredUrlArguments())));
    }

    @Override
    public Map<String, Object> execute(String toolName, Map<String, Object> args) throws Exception {
        String url = ToolArgs.getStringArg(args, "url", false);

        if (url == null || url.isBlank()) {
            url = defaultUrl;
        }

        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("Missing required argument: url");
        }
        String user = envProvider.apply("ACTUATOR_USER");
        String password = envProvider.apply("ACTUATOR_PASSWORD");
        String token = envProvider.apply("ACTUATOR_TOKEN");
        boolean insecure = "true".equalsIgnoreCase(envProvider.apply("ACTUATOR_INSECURE"));

        ActuatorAuth auth =
                token != null
                        ? ActuatorAuth.bearer(token, insecure)
                        : ActuatorAuth.basic(user, password, insecure);
        auth.validate()
                .ifPresent(
                        error -> {
                            throw new IllegalArgumentException(error);
                        });

        ActuatorClient client = clientFactory.create(url, auth);
        List<Map<String, Object>> content = new ArrayList<>();

        if ("get_actuator_health".equals(toolName)) {
            content.add(Map.of("type", "text", "text", client.getHealth()));
        } else if ("get_actuator_metrics".equals(toolName)) {
            String metricsParam = ToolArgs.getStringArg(args, "metrics", false);
            if (metricsParam != null && !metricsParam.isBlank()) {
                if (metricsParam.contains(",")) {
                    List<String> metricList =
                            Arrays.stream(metricsParam.split(","))
                                    .map(String::trim)
                                    .filter(s -> !s.isEmpty())
                                    .toList();
                    content.add(Map.of("type", "text", "text", client.getMetricsBatch(metricList)));
                } else {
                    content.add(
                            Map.of("type", "text", "text", client.getMetric(metricsParam.trim())));
                }
            } else {
                content.add(Map.of("type", "text", "text", client.getMetrics()));
            }
        } else if ("get_actuator_startup".equals(toolName)) {
            content.add(Map.of("type", "text", "text", client.getStartup()));
        } else {
            throw new IllegalArgumentException("Unknown actuator tool: " + toolName);
        }

        return Map.of("content", content);
    }
}
