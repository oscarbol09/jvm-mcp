package dev.jvmmcp.server.tool;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.model.SpringContextBeans;
import dev.jvmmcp.core.spring.SpringBeansClient;
import dev.jvmmcp.core.util.SimpleJson;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Handles Spring bean inspection tools: list_spring_beans, get_bean_detail. */
public class SpringBeansToolHandler implements ToolHandler {

    @FunctionalInterface
    public interface JmxConnector {
        JmxConnectionManager connect(VirtualMachine vm, long pid) throws Exception;
    }

    private static final int DEFAULT_BEAN_LIMIT = 200;
    private static final int MAX_BEAN_LIMIT = 2000;
    private static final int TOP_PACKAGE_LIMIT = 10;

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
                        "description",
                                "Lists Spring beans with pagination, or summarizes all matching beans by context and package",
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
                                                                "Optional glob filter (e.g. *Service*)"),
                                                        "limit",
                                                        Map.of(
                                                                "type",
                                                                "integer",
                                                                "default",
                                                                DEFAULT_BEAN_LIMIT,
                                                                "minimum",
                                                                1,
                                                                "maximum",
                                                                MAX_BEAN_LIMIT,
                                                                "description",
                                                                "Maximum number of beans to return"),
                                                        "offset",
                                                        Map.of(
                                                                "type",
                                                                "integer",
                                                                "default",
                                                                0,
                                                                "minimum",
                                                                0,
                                                                "description",
                                                                "Number of matching beans to skip"),
                                                        "summary",
                                                        Map.of(
                                                                "type",
                                                                "boolean",
                                                                "default",
                                                                false,
                                                                "description",
                                                                "Return counts and top 10 packages per context for all matching beans; limit and offset do not affect the summary")),
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

        int limit = DEFAULT_BEAN_LIMIT;
        int offset = 0;
        boolean summary = false;
        if ("list_spring_beans".equals(toolName)) {
            limit = ToolArgs.getIntArg(args, "limit", DEFAULT_BEAN_LIMIT, 1, MAX_BEAN_LIMIT);
            offset = ToolArgs.getIntArg(args, "offset", 0, 0, Integer.MAX_VALUE);
            summary = ToolArgs.getBooleanArg(args, "summary", false);
        }

        String beanName =
                "get_bean_detail".equals(toolName)
                        ? ToolArgs.getStringArg(args, "beanName", true)
                        : null;
        AttachResult attachResult = attachService.attach(String.valueOf(pid));
        if (!attachResult.isSuccessful()) {
            throw new IllegalStateException(attachResult.message());
        }

        VirtualMachine vm = attachResult.virtualMachine().orElse(null);
        boolean[] jmxManagerOwnsVm = {false};
        List<Map<String, Object>> content = new ArrayList<>();
        try {
            String filterGlob = ToolArgs.getStringArg(args, "filter", false);
            SpringBeansClient.JmxConnectionProvider provider =
                    () -> {
                        JmxConnectionManager manager = jmxConnector.connect(vm, pid);
                        jmxManagerOwnsVm[0] = true;
                        return manager;
                    };
            SpringBeansReport report =
                    beanName != null
                            ? beansClient.inspectBeanDetailWithJmxProvider(
                                    pid, vm, provider, beanName)
                            : beansClient.inspectBeansWithJmxProvider(
                                    pid, vm, provider, filterGlob);

            if ("list_spring_beans".equals(toolName)) {
                Map<String, Object> response =
                        summary ? summarizeBeans(report) : paginateBeans(report, limit, offset);
                content.add(Map.of("type", "text", "text", SimpleJson.toJson(response)));
            } else if ("get_bean_detail".equals(toolName)) {
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

    private static Map<String, Object> paginateBeans(
            SpringBeansReport report, int limit, int offset) {

        List<SpringContextBeans> pageContexts = new ArrayList<>();
        int remainingOffset = offset;
        int returnedBeans = 0;

        List<SpringContextBeans> sortedContexts =
                report.contexts().stream()
                        .sorted(Comparator.comparing(SpringContextBeans::contextId))
                        .toList();

        for (SpringContextBeans context : sortedContexts) {
            if (returnedBeans == limit) {
                break;
            }

            if (remainingOffset >= context.beans().size()) {
                remainingOffset -= context.beans().size();
                continue;
            }

            List<SpringBeanDetail> sortedBeans =
                    context.beans().stream()
                            .sorted(Comparator.comparing(SpringBeanDetail::name))
                            .toList();

            int start = remainingOffset;
            int count = Math.min(limit - returnedBeans, sortedBeans.size() - start);

            List<SpringBeanDetail> pageBeans =
                    List.copyOf(sortedBeans.subList(start, start + count));

            pageContexts.add(
                    new SpringContextBeans(
                            context.contextId(), context.parentId(), pageBeans.size(), pageBeans));

            returnedBeans += pageBeans.size();
            remainingOffset = 0;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", report.pid());
        result.put("discoverySource", report.discoverySource());
        result.put("totalBeans", report.totalBeans());
        result.put("limit", limit);
        result.put("offset", offset);
        result.put("returnedBeans", returnedBeans);
        result.put("truncated", (long) offset + returnedBeans < report.totalBeans());
        result.put("contexts", pageContexts);
        return result;
    }

    private static String packageName(String type) {
        if (type == null || type.isBlank() || "unknown".equals(type)) {
            return "<unknown>";
        }

        int lastDot = type.lastIndexOf('.');
        return lastDot < 0 ? "<default>" : type.substring(0, lastDot);
    }

    private static Map<String, Object> summarizeBeans(SpringBeansReport report) {
        List<Map<String, Object>> contexts = new ArrayList<>();

        List<SpringContextBeans> sortedContexts =
                report.contexts().stream()
                        .sorted(Comparator.comparing(SpringContextBeans::contextId))
                        .toList();

        for (SpringContextBeans context : sortedContexts) {
            Map<String, Integer> packageCounts = new LinkedHashMap<>();

            for (SpringBeanDetail bean : context.beans()) {
                packageCounts.merge(packageName(bean.type()), 1, Integer::sum);
            }

            List<Map<String, Object>> topPackages =
                    packageCounts.entrySet().stream()
                            .sorted(
                                    Map.Entry.<String, Integer>comparingByValue()
                                            .reversed()
                                            .thenComparing(Map.Entry.comparingByKey()))
                            .limit(TOP_PACKAGE_LIMIT)
                            .map(
                                    entry ->
                                            Map.<String, Object>of(
                                                    "package", entry.getKey(),
                                                    "beanCount", entry.getValue()))
                            .toList();

            Map<String, Object> contextSummary = new LinkedHashMap<>();
            contextSummary.put("contextId", context.contextId());
            contextSummary.put("parentId", context.parentId());
            contextSummary.put("beanCount", context.beans().size());
            contextSummary.put("topPackages", topPackages);
            contexts.add(contextSummary);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("pid", report.pid());
        result.put("discoverySource", report.discoverySource());
        result.put("summary", true);
        result.put("totalBeans", report.totalBeans());
        result.put("contexts", contexts);
        return result;
    }
}
