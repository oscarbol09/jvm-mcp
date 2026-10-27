package dev.jvmmcp.server.tool;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.spring.SpringBeansClient;
import dev.jvmmcp.core.util.SimpleJson;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Handles Spring bean inspection tools: list_spring_beans, get_bean_detail. */
public class SpringBeansToolHandler implements ToolHandler {

    @FunctionalInterface
    public interface JmxConnector {
        JmxConnectionManager connect(VirtualMachine vm, long pid) throws Exception;
    }

    private final JvmAttachService attachService;
    private final SpringBeansClient beansClient;
    private final JmxConnector jmxConnector;

    public SpringBeansToolHandler() {
        this(new JvmAttachService());
    }

    public SpringBeansToolHandler(JvmAttachService attachService) {
        this(
                attachService,
                new SpringBeansClient(),
                (vm, pid) ->
                        vm != null
                                ? JmxConnectionManager.connect(vm, pid)
                                : JmxConnectionManager.connectLocal());
    }

    public SpringBeansToolHandler(JvmAttachService attachService, SpringBeansClient beansClient) {
        this(
                attachService,
                beansClient,
                (vm, pid) ->
                        vm != null
                                ? JmxConnectionManager.connect(vm, pid)
                                : JmxConnectionManager.connectLocal());
    }

    public SpringBeansToolHandler(
            JvmAttachService attachService,
            SpringBeansClient beansClient,
            JmxConnector jmxConnector) {
        this.attachService = attachService;
        this.beansClient = beansClient;
        this.jmxConnector = jmxConnector;
    }

    @Override
    public List<String> toolNames() {
        return List.of("list_spring_beans", "get_bean_detail");
    }

    @Override
    public List<Map<String, Object>> toolDefinitions() {
        return List.of(
                Map.of(
                        "name", "list_spring_beans",
                        "description", "Lists instantiated Spring Beans in the ApplicationContext",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "pid",
                                                                Map.of(
                                                                        "type",
                                                                        "number",
                                                                        "description",
                                                                        "Target JVM Process ID"),
                                                        "filter",
                                                                Map.of(
                                                                        "type",
                                                                        "string",
                                                                        "description",
                                                                        "Optional glob filter (e.g. *Service*)")),
                                        "required", List.of("pid"))),
                Map.of(
                        "name", "get_bean_detail",
                        "description",
                                "Gets detailed information about a Spring bean; exact case-sensitive name or alias is preferred, with case-insensitive fallback only when unambiguous",
                        "inputSchema",
                                Map.of(
                                        "type", "object",
                                        "properties",
                                                Map.of(
                                                        "pid",
                                                                Map.of(
                                                                        "type",
                                                                        "number",
                                                                        "description",
                                                                        "Target JVM Process ID"),
                                                        "beanName",
                                                                Map.of(
                                                                        "type",
                                                                        "string",
                                                                        "description",
                                                                        "Bean name or alias; case-sensitive exact match is preferred, with case-insensitive fallback only when unambiguous")),
                                        "required", List.of("pid", "beanName"))));
    }

    @Override
    public Map<String, Object> execute(String toolName, Map<String, Object> args) throws Exception {
        long pid = ToolArgs.getPositiveLongArg(args, "pid", true);
        AttachResult attachResult = attachService.attach(String.valueOf(pid));
        if (!attachResult.isSuccessful()) {
            throw new IllegalStateException(attachResult.message());
        }

        VirtualMachine vm = attachResult.virtualMachine().orElse(null);
        boolean[] jmxManagerOwnsVm = {false};
        List<Map<String, Object>> content = new ArrayList<>();
        try {
            String filterGlob = ToolArgs.getStringArg(args, "filter", false);
            SpringBeansReport report;
            try {
                report =
                        beansClient.inspectBeansWithJmxProvider(
                                pid,
                                vm,
                                () -> {
                                    JmxConnectionManager manager = jmxConnector.connect(vm, pid);
                                    jmxManagerOwnsVm[0] = true;
                                    return manager;
                                },
                                filterGlob);
            } catch (Exception e) {
                return discoveryErrorResult(
                        "JMX connection failed (" + e.getClass().getSimpleName() + ")");
            }

            if ("list_spring_beans".equals(toolName)) {
                if (report.discoverySource() != null
                        && report.discoverySource().startsWith("NOT_AVAILABLE")) {
                    return discoveryErrorResult(report.discoverySource());
                }
                content.add(Map.of("type", "text", "text", SimpleJson.toJson(report)));
            } else if ("get_bean_detail".equals(toolName)) {
                String beanName = ToolArgs.getStringArg(args, "beanName", true);
                try {
                    var detailOpt = beansClient.getBeanDetailMatch(report, beanName);
                    if (detailOpt.isPresent()) {
                        content.add(
                                Map.of("type", "text", "text", SimpleJson.toJson(detailOpt.get())));
                    } else {
                        return Map.of(
                                "content",
                                List.of(
                                        Map.of(
                                                "type",
                                                "text",
                                                "text",
                                                "Bean not found: '"
                                                        + beanName
                                                        + "'. Use 'list_spring_beans' to inspect registered bean names and their contexts.")),
                                "isError",
                                true);
                    }
                } catch (SpringBeansClient.AmbiguousBeanMatchException e) {
                    return Map.of(
                            "content",
                            List.of(Map.of("type", "text", "text", e.getMessage())),
                            "isError",
                            true);
                }
            } else {
                throw new IllegalArgumentException("Unknown Spring Beans tool: " + toolName);
            }
        } finally {
            if (vm != null && !jmxManagerOwnsVm[0]) {
                try {
                    attachService.detach(vm);
                } catch (Exception ignored) {
                }
            }
        }

        return Map.of("content", content);
    }

    private Map<String, Object> discoveryErrorResult(String diagnostic) {
        return Map.of(
                "content",
                List.of(
                        Map.of(
                                "type",
                                "text",
                                "text",
                                "Unable to discover Spring beans: "
                                        + diagnostic
                                        + ". Expose the Actuator beans endpoint with "
                                        + "management.endpoints.web.exposure.include=beans "
                                        + "or enable Spring JMX with spring.jmx.enabled=true.")),
                "isError",
                true);
    }
}
