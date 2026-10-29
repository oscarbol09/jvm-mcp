package dev.jvmmcp.core.spring;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.model.SpringContextBeans;
import java.util.*;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;

/**
 * High-level service inspecting Spring Beans via a dual strategy: Actuator HTTP with automatic JMX
 * fallback.
 */
public class SpringBeansClient {

    private static final String EXACT_MATCH = "EXACT";
    private static final String CASE_INSENSITIVE_FALLBACK = "CASE_INSENSITIVE_FALLBACK";

    @FunctionalInterface
    public interface JmxConnectionProvider {
        JmxConnectionManager connect() throws Exception;
    }

    public record BeanDetailMatch(String contextId, SpringBeanDetail bean, String matchedBy) {}

    public static final class AmbiguousBeanMatchException extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        private final List<String> contextIds;

        public AmbiguousBeanMatchException(String beanName, List<String> contextIds) {
            super(
                    "Ambiguous bean lookup for '"
                            + beanName
                            + "': matching beans were found in Spring contexts "
                            + contextIds
                            + ". Use a unique bean name or alias.");
            this.contextIds = List.copyOf(contextIds);
        }

        public List<String> contextIds() {
            return contextIds;
        }
    }

    private final ActuatorProbe actuatorProbe;
    private final SpringBeansParser beansParser;

    public SpringBeansClient() {
        this(new ActuatorProbe(), new SpringBeansParser());
    }

    public SpringBeansClient(ActuatorProbe actuatorProbe, SpringBeansParser beansParser) {
        this.actuatorProbe = actuatorProbe;
        this.beansParser = beansParser;
    }

    public SpringBeansReport inspectBeans(
            long pid, VirtualMachine vm, MBeanServerConnection mbsc, String filterGlob) {
        return inspectBeans(pid, vm, mbsc, filterGlob, null);
    }

    private SpringBeansReport inspectBeans(
            long pid,
            VirtualMachine vm,
            MBeanServerConnection mbsc,
            String filterGlob,
            String beanName) {
        List<String> diagnostics = new ArrayList<>();
        // Strategy 1: Attempt Actuator HTTP discovery
        if (vm != null) {
            Optional<ActuatorProbe.ProbeResult> probeResult;
            try {
                probeResult = actuatorProbe.probe(vm);
            } catch (Exception e) {
                diagnostics.add(
                        "Actuator HTTP discovery failed (" + e.getClass().getSimpleName() + ")");
                probeResult = Optional.empty();
            }
            if (probeResult.isPresent()) {
                SpringBeansReport report =
                        beansParser.parse(
                                pid,
                                "ACTUATOR_HTTP (" + probeResult.get().url() + ")",
                                probeResult.get().rawJson(),
                                beanName);
                return applyFilter(report, filterGlob);
            }
            diagnostics.add("Actuator HTTP discovery returned no reachable beans endpoint");
        } else {
            diagnostics.add(
                    "Actuator HTTP discovery was not attempted because no VM was available");
        }

        return inspectJmxBeans(pid, mbsc, filterGlob, beanName, diagnostics);
    }

    public SpringBeansReport inspectBeansWithJmxProvider(
            long pid,
            VirtualMachine vm,
            JmxConnectionProvider jmxConnectionProvider,
            String filterGlob)
            throws Exception {
        return inspectBeansWithJmxProvider(pid, vm, jmxConnectionProvider, filterGlob, null);
    }

    /** Discovers literal name/alias candidates without constructing unrelated bean details. */
    public SpringBeansReport inspectBeanDetailWithJmxProvider(
            long pid,
            VirtualMachine vm,
            JmxConnectionProvider jmxConnectionProvider,
            String beanName)
            throws Exception {
        if (beanName == null || beanName.isBlank()) {
            throw new IllegalArgumentException("Bean name cannot be blank");
        }
        return inspectBeansWithJmxProvider(pid, vm, jmxConnectionProvider, null, beanName);
    }

    private SpringBeansReport inspectBeansWithJmxProvider(
            long pid,
            VirtualMachine vm,
            JmxConnectionProvider jmxConnectionProvider,
            String filterGlob,
            String beanName)
            throws Exception {

        List<String> diagnostics = new ArrayList<>();
        if (vm != null) {
            Optional<ActuatorProbe.ProbeResult> probeResult;
            try {
                probeResult = actuatorProbe.probe(vm);
            } catch (Exception e) {
                diagnostics.add(
                        "Actuator HTTP discovery failed (" + e.getClass().getSimpleName() + ")");
                probeResult = Optional.empty();
            }
            if (probeResult.isPresent()) {
                SpringBeansReport report =
                        beansParser.parse(
                                pid,
                                "ACTUATOR_HTTP (" + probeResult.get().url() + ")",
                                probeResult.get().rawJson(),
                                beanName);
                return applyFilter(report, filterGlob);
            }
            diagnostics.add("Actuator HTTP discovery returned no reachable beans endpoint");
        } else {
            diagnostics.add(
                    "Actuator HTTP discovery was not attempted because no VM was available");
        }

        try (JmxConnectionManager jmxManager = jmxConnectionProvider.connect()) {
            return inspectJmxBeans(
                    pid, jmxManager.getMBeanServerConnection(), filterGlob, beanName, diagnostics);
        }
    }

    public SpringBeansReport inspectFromActuatorUrl(long pid, String baseUrl, String filterGlob) {

        Optional<String> json = actuatorProbe.fetchFromUrl(baseUrl);
        if (json.isPresent()) {
            SpringBeansReport report =
                    beansParser.parse(pid, "ACTUATOR_HTTP (" + baseUrl + ")", json.get());
            return applyFilter(report, filterGlob);
        }

        return new SpringBeansReport(pid, "ACTUATOR_UNREACHABLE (" + baseUrl + ")", 0, List.of());
    }

    /**
     * Backward-compatible lookup returning only the bean detail.
     *
     * <p>Exact, case-sensitive names and aliases take priority. A case-insensitive fallback is
     * considered only if no exact match exists.
     *
     * @throws AmbiguousBeanMatchException when multiple beans match at the selected priority
     */
    public Optional<SpringBeanDetail> getBeanDetail(SpringBeansReport report, String beanName) {

        return getBeanDetailMatch(report, beanName).map(BeanDetailMatch::bean);
    }

    /**
     * Looks up a bean while retaining its owning Spring context and match method.
     *
     * @return the unique match, or empty when no bean matches
     * @throws AmbiguousBeanMatchException when multiple beans match at the selected priority
     */
    public Optional<BeanDetailMatch> getBeanDetailMatch(SpringBeansReport report, String beanName) {

        if (report == null || beanName == null || beanName.isBlank()) {
            return Optional.empty();
        }

        List<BeanDetailMatch> exactMatches = findBeanMatches(report, beanName, false, EXACT_MATCH);

        if (!exactMatches.isEmpty()) {
            return Optional.of(resolveUniqueMatch(beanName, exactMatches));
        }

        List<BeanDetailMatch> fallbackMatches =
                findBeanMatches(report, beanName, true, CASE_INSENSITIVE_FALLBACK);

        if (fallbackMatches.isEmpty()) {
            return Optional.empty();
        }

        return Optional.of(resolveUniqueMatch(beanName, fallbackMatches));
    }

    private List<BeanDetailMatch> findBeanMatches(
            SpringBeansReport report, String beanName, boolean ignoreCase, String matchedBy) {

        if (report.contexts() == null) {
            return List.of();
        }

        List<BeanDetailMatch> matches = new ArrayList<>();

        for (SpringContextBeans context : report.contexts()) {
            if (context == null || context.beans() == null) {
                continue;
            }

            String contextId = context.contextId();
            if (contextId == null || contextId.isBlank()) {
                contextId = "<unknown>";
            }

            for (SpringBeanDetail bean : context.beans()) {
                if (bean == null) {
                    continue;
                }

                if (matchesBeanNameOrAlias(bean, beanName, ignoreCase)) {
                    matches.add(new BeanDetailMatch(contextId, bean, matchedBy));
                }
            }
        }

        return matches;
    }

    private boolean matchesBeanNameOrAlias(
            SpringBeanDetail bean, String beanName, boolean ignoreCase) {

        if (matchesName(bean.name(), beanName, ignoreCase)) {
            return true;
        }

        List<String> aliases = bean.aliases();
        return aliases != null
                && aliases.stream().anyMatch(alias -> matchesName(alias, beanName, ignoreCase));
    }

    private boolean matchesName(String candidate, String requestedName, boolean ignoreCase) {

        if (candidate == null) {
            return false;
        }

        return ignoreCase
                ? candidate.equalsIgnoreCase(requestedName)
                : candidate.equals(requestedName);
    }

    private BeanDetailMatch resolveUniqueMatch(String beanName, List<BeanDetailMatch> matches) {

        if (matches.size() > 1) {
            List<String> contextIds =
                    matches.stream().map(BeanDetailMatch::contextId).distinct().sorted().toList();

            throw new AmbiguousBeanMatchException(beanName, contextIds);
        }

        return matches.get(0);
    }

    private Optional<SpringBeansReport> queryJmxBeans(
            long pid, MBeanServerConnection mbsc, String beanName, List<String> diagnostics) {

        String[] candidateObjectNames = {
            "org.springframework.boot:type=Endpoint,name=Beans",
            "org.springframework.boot:type=Endpoint,name=beans"
        };

        for (String name : candidateObjectNames) {
            try {
                ObjectName objectName = new ObjectName(name);
                if (mbsc.isRegistered(objectName)) {
                    // Try invoking beans() operation or reading Data / beans attribute
                    Object result = null;

                    try {
                        result = mbsc.invoke(objectName, "beans", new Object[0], new String[0]);
                    } catch (Exception e) {
                        diagnostics.add(
                                "JMX MBean '"
                                        + name
                                        + "' operation 'beans' failed ("
                                        + e.getClass().getSimpleName()
                                        + ")");
                        try {
                            result = mbsc.getAttribute(objectName, "Data");
                        } catch (Exception e2) {
                            diagnostics.add(
                                    "JMX MBean '"
                                            + name
                                            + "' attribute 'Data' failed ("
                                            + e2.getClass().getSimpleName()
                                            + ")");
                            try {
                                result = mbsc.getAttribute(objectName, "beans");
                            } catch (Exception e3) {
                                diagnostics.add(
                                        "JMX MBean '"
                                                + name
                                                + "' attribute 'beans' failed ("
                                                + e3.getClass().getSimpleName()
                                                + ")");
                            }
                        }
                    }

                    if (result instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> casted = (Map<String, Object>) map;

                        return Optional.of(
                                beansParser.parseMap(
                                        pid, "JMX_MBEAN (" + name + ")", casted, beanName));
                    } else if (result instanceof String json) {
                        return Optional.of(
                                beansParser.parse(pid, "JMX_MBEAN (" + name + ")", json, beanName));
                    }
                    diagnostics.add("JMX MBean '" + name + "' returned no readable beans data");
                } else {
                    diagnostics.add("JMX MBean not registered: " + name);
                }
            } catch (Exception e) {
                diagnostics.add(
                        "JMX MBean '"
                                + name
                                + "' inspection failed ("
                                + e.getClass().getSimpleName()
                                + ")");
            }
        }

        return Optional.empty();
    }

    private SpringBeansReport inspectJmxBeans(
            long pid,
            MBeanServerConnection mbsc,
            String filterGlob,
            String beanName,
            List<String> diagnostics) {
        if (mbsc != null) {
            Optional<SpringBeansReport> jmxReport = queryJmxBeans(pid, mbsc, beanName, diagnostics);
            if (jmxReport.isPresent()) {
                return applyFilter(jmxReport.get(), filterGlob);
            }
        } else {
            diagnostics.add(
                    "JMX discovery was not attempted because no MBean connection was available");
        }
        return unavailableReport(pid, diagnostics);
    }

    private SpringBeansReport unavailableReport(long pid, List<String> diagnostics) {
        String details = String.join("; ", diagnostics);
        String source = details.isBlank() ? "NOT_AVAILABLE" : "NOT_AVAILABLE (" + details + ")";
        return new SpringBeansReport(pid, source, 0, List.of());
    }

    private SpringBeansReport applyFilter(SpringBeansReport report, String filterGlob) {

        if (filterGlob == null || filterGlob.isBlank() || "*".equals(filterGlob)) {
            return report;
        }

        java.util.regex.Pattern pattern = GlobMatcher.compileGlob(filterGlob);
        List<SpringContextBeans> filteredContexts = new ArrayList<>();
        int filteredTotal = 0;

        for (SpringContextBeans context : report.contexts()) {
            List<SpringBeanDetail> matchedBeans =
                    context.beans().stream()
                            .filter(
                                    bean ->
                                            (bean.name() != null
                                                            && pattern.matcher(bean.name())
                                                                    .matches())
                                                    || (bean.type() != null
                                                            && pattern.matcher(bean.type())
                                                                    .matches())
                                                    || (bean.aliases() != null
                                                            && bean.aliases().stream()
                                                                    .anyMatch(
                                                                            alias ->
                                                                                    alias != null
                                                                                            && pattern.matcher(
                                                                                                            alias)
                                                                                                    .matches())))
                            .toList();

            if (!matchedBeans.isEmpty()) {
                filteredTotal += matchedBeans.size();
                filteredContexts.add(
                        new SpringContextBeans(
                                context.contextId(),
                                context.parentId(),
                                matchedBeans.size(),
                                matchedBeans));
            }
        }

        return new SpringBeansReport(
                report.pid(), report.discoverySource(), filteredTotal, filteredContexts);
    }
}
