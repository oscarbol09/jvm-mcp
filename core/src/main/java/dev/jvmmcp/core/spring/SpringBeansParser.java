package dev.jvmmcp.core.spring;

import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.model.SpringContextBeans;
import dev.jvmmcp.core.util.SimpleJson;

import java.util.*;

/**
 * Robust parser converting Spring Boot Actuator and JMX beans payloads into immutable diagnostic reports.
 */
public class SpringBeansParser {

    public SpringBeansReport parse(long pid, String discoverySource, String jsonContent) {
        if (jsonContent == null || jsonContent.isBlank()) {
            return new SpringBeansReport(pid, discoverySource, 0, List.of());
        }

        Map<String, Object> root = SimpleJson.parseObject(jsonContent);
        return parseMap(pid, discoverySource, root);
    }

    public SpringBeansReport parseMap(long pid, String discoverySource, Map<String, Object> root) {
        if (root == null || root.isEmpty()) {
            return new SpringBeansReport(pid, discoverySource, 0, List.of());
        }

        List<SpringContextBeans> contextList = new ArrayList<>();
        int totalBeans = 0;

        // Spring Boot standard structure: { "contexts": { "contextId": { "beans": { ... }, "parentId": ... } } }
        Object contextsObj = root.get("contexts");
        if (contextsObj instanceof Map<?, ?> contextsMap) {
            for (Map.Entry<?, ?> entry : contextsMap.entrySet()) {
                String contextId = Objects.toString(entry.getKey(), "default");
                if (entry.getValue() instanceof Map<?, ?> contextData) {
                    String parentId = Objects.toString(contextData.get("parentId"), null);
                    Object beansObj = contextData.get("beans");
                    List<SpringBeanDetail> beans = parseBeansMap(beansObj);
                    totalBeans += beans.size();
                    contextList.add(new SpringContextBeans(contextId, parentId, beans.size(), beans));
                }
            }
        } else if (root.containsKey("beans")) {
            // Direct { "beans": { ... } } structure
            List<SpringBeanDetail> beans = parseBeansMap(root.get("beans"));
            totalBeans = beans.size();
            contextList.add(new SpringContextBeans("root", null, totalBeans, beans));
        } else {
            // Flat key-value map of beans
            List<SpringBeanDetail> beans = parseBeansMap(root);
            totalBeans = beans.size();
            contextList.add(new SpringContextBeans("root", null, totalBeans, beans));
        }

        return new SpringBeansReport(pid, discoverySource, totalBeans, contextList);
    }

    private List<SpringBeanDetail> parseBeansMap(Object beansObj) {
        List<SpringBeanDetail> beans = new ArrayList<>();
        if (!(beansObj instanceof Map<?, ?> map)) {
            return beans;
        }

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String beanName = Objects.toString(entry.getKey(), "");
            if (entry.getValue() instanceof Map<?, ?> data) {
                List<String> aliases = toStringList(data.get("aliases"));
                String scope = Objects.toString(data.get("scope"), "singleton");
                String type = Objects.toString(data.get("type"), "unknown");
                String resource = Objects.toString(data.get("resource"), null);
                List<String> dependencies = toStringList(data.get("dependencies"));

                beans.add(new SpringBeanDetail(beanName, aliases, scope, type, resource, dependencies));
            } else if (entry.getValue() instanceof String typeStr) {
                beans.add(new SpringBeanDetail(beanName, List.of(), "singleton", typeStr, null, List.of()));
            }
        }

        return beans;
    }

    private List<String> toStringList(Object obj) {
        if (obj instanceof List<?> list) {
            return list.stream()
                .filter(Objects::nonNull)
                .map(Object::toString)
                .toList();
        }
        if (obj instanceof String[] arr) {
            return Arrays.asList(arr);
        }
        return List.of();
    }
}
