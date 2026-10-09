package dev.jvmmcp.core.spring;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.model.SpringContextBeans;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.IOException;
import java.util.*;

/**
 * High-level service inspecting Spring Beans via a dual strategy: Actuator HTTP with automatic JMX fallback.
 */
public class SpringBeansClient {

    private final ActuatorProbe actuatorProbe;
    private final SpringBeansParser beansParser;

    public SpringBeansClient() {
        this(new ActuatorProbe(), new SpringBeansParser());
    }

    public SpringBeansClient(ActuatorProbe actuatorProbe, SpringBeansParser beansParser) {
        this.actuatorProbe = actuatorProbe;
        this.beansParser = beansParser;
    }

    public SpringBeansReport inspectBeans(long pid, VirtualMachine vm, MBeanServerConnection mbsc, String filterGlob) {
        // Strategy 1: Attempt Actuator HTTP discovery
        if (vm != null) {
            Optional<ActuatorProbe.ProbeResult> probeResult = actuatorProbe.probe(vm);
            if (probeResult.isPresent()) {
                SpringBeansReport report = beansParser.parse(pid, "ACTUATOR_HTTP (" + probeResult.get().url() + ")", probeResult.get().rawJson());
                return applyFilter(report, filterGlob);
            }
        }

        // Strategy 2: Attempt JMX MBean inspection
        if (mbsc != null) {
            Optional<SpringBeansReport> jmxReport = queryJmxBeans(pid, mbsc);
            if (jmxReport.isPresent()) {
                return applyFilter(jmxReport.get(), filterGlob);
            }
        }

        return new SpringBeansReport(pid, "NOT_AVAILABLE (Actuator HTTP not reachable and JMX Beans endpoint missing)", 0, List.of());
    }

    public SpringBeansReport inspectFromActuatorUrl(long pid, String baseUrl, String filterGlob) {
        Optional<String> json = actuatorProbe.fetchFromUrl(baseUrl);
        if (json.isPresent()) {
            SpringBeansReport report = beansParser.parse(pid, "ACTUATOR_HTTP (" + baseUrl + ")", json.get());
            return applyFilter(report, filterGlob);
        }
        return new SpringBeansReport(pid, "ACTUATOR_UNREACHABLE (" + baseUrl + ")", 0, List.of());
    }

    public Optional<SpringBeanDetail> getBeanDetail(SpringBeansReport report, String beanName) {
        if (report == null || beanName == null || beanName.isBlank()) {
            return Optional.empty();
        }

        return report.getAllBeans().stream()
            .filter(b -> b.name().equalsIgnoreCase(beanName) || b.aliases().stream().anyMatch(a -> a.equalsIgnoreCase(beanName)))
            .findFirst();
    }

    private Optional<SpringBeansReport> queryJmxBeans(long pid, MBeanServerConnection mbsc) {
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
                    } catch (Exception ignored) {
                        try {
                            result = mbsc.getAttribute(objectName, "Data");
                        } catch (Exception ignored2) {
                            try {
                                result = mbsc.getAttribute(objectName, "beans");
                            } catch (Exception ignored3) {}
                        }
                    }

                    if (result instanceof Map<?, ?> map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> casted = (Map<String, Object>) map;
                        return Optional.of(beansParser.parseMap(pid, "JMX_MBEAN (" + name + ")", casted));
                    } else if (result instanceof String json) {
                        return Optional.of(beansParser.parse(pid, "JMX_MBEAN (" + name + ")", json));
                    }
                }
            } catch (Exception ignored) {}
        }

        return Optional.empty();
    }

    private SpringBeansReport applyFilter(SpringBeansReport report, String filterGlob) {
        if (filterGlob == null || filterGlob.isBlank() || "*".equals(filterGlob)) {
            return report;
        }

        java.util.regex.Pattern pattern = GlobMatcher.compileGlob(filterGlob);
        List<SpringContextBeans> filteredContexts = new ArrayList<>();
        int filteredTotal = 0;

        for (SpringContextBeans context : report.contexts()) {
            List<SpringBeanDetail> matchedBeans = context.beans().stream()
                .filter(bean -> (bean.name() != null && pattern.matcher(bean.name()).matches()) 
                             || (bean.type() != null && pattern.matcher(bean.type()).matches())
                             || bean.aliases().stream().anyMatch(a -> a != null && pattern.matcher(a).matches()))
                .toList();

            if (!matchedBeans.isEmpty()) {
                filteredTotal += matchedBeans.size();
                filteredContexts.add(new SpringContextBeans(
                    context.contextId(),
                    context.parentId(),
                    matchedBeans.size(),
                    matchedBeans
                ));
            }
        }

        return new SpringBeansReport(report.pid(), report.discoverySource(), filteredTotal, filteredContexts);
    }
}
