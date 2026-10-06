package dev.jvmmcp.core.spring;

import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class SpringBeansParserTest {

    private final SpringBeansParser parser = new SpringBeansParser();

    @Test
    @DisplayName("parse accurately extracts beans, scopes, types, and dependencies from standard Spring Boot Actuator JSON")
    void shouldParseActuatorBeansJson() {
        String actuatorPayload = """
            {
              "contexts": {
                "application": {
                  "beans": {
                    "orderService": {
                      "aliases": ["orderSvc"],
                      "scope": "singleton",
                      "type": "com.example.service.OrderService",
                      "resource": "class path resource [com/example/service/OrderService.class]",
                      "dependencies": ["orderRepository", "paymentClient"]
                    },
                    "paymentClient": {
                      "aliases": [],
                      "scope": "prototype",
                      "type": "com.example.client.PaymentClient",
                      "resource": null,
                      "dependencies": []
                    }
                  },
                  "parentId": "parentContext"
                }
              }
            }
            """;

        SpringBeansReport report = parser.parse(45231L, "ACTUATOR_HTTP (http://localhost:8080)", actuatorPayload);

        assertThat(report).isNotNull();
        assertThat(report.pid()).isEqualTo(45231L);
        assertThat(report.discoverySource()).contains("ACTUATOR_HTTP");
        assertThat(report.totalBeans()).isEqualTo(2);
        assertThat(report.contexts()).hasSize(1);
        assertThat(report.contexts().get(0).contextId()).isEqualTo("application");
        assertThat(report.contexts().get(0).parentId()).isEqualTo("parentContext");

        Optional<SpringBeanDetail> orderService = report.getAllBeans().stream()
            .filter(b -> "orderService".equals(b.name()))
            .findFirst();

        assertThat(orderService).isPresent();
        assertThat(orderService.get().type()).isEqualTo("com.example.service.OrderService");
        assertThat(orderService.get().scope()).isEqualTo("singleton");
        assertThat(orderService.get().aliases()).containsExactly("orderSvc");
        assertThat(orderService.get().dependencies()).containsExactly("orderRepository", "paymentClient");
    }

    @Test
    @DisplayName("parse handles direct beans object and flat key-value map structures")
    void shouldParseDirectBeansAndFlatMaps() {
        // Direct "beans" top-level key
        String directBeans = """
            {
              "beans": {
                "directService": {
                  "type": "com.example.DirectService",
                  "scope": "singleton"
                }
              }
            }
            """;

        SpringBeansReport directReport = parser.parse(1L, "TEST", directBeans);
        assertThat(directReport.totalBeans()).isEqualTo(1);
        assertThat(directReport.getAllBeans().get(0).name()).isEqualTo("directService");

        // Flat map structure
        Map<String, Object> flatMap = Map.of(
            "shorthandService", "com.example.ShorthandService",
            "fullService", Map.of(
                "type", "com.example.FullService",
                "aliases", new String[]{"alias1"},
                "dependencies", new String[]{"dep1"}
            )
        );

        SpringBeansReport flatReport = parser.parseMap(2L, "JMX", flatMap);
        assertThat(flatReport.totalBeans()).isEqualTo(2);

        SpringBeanDetail shorthand = flatReport.getAllBeans().stream()
            .filter(b -> "shorthandService".equals(b.name()))
            .findFirst()
            .orElseThrow();
        assertThat(shorthand.type()).isEqualTo("com.example.ShorthandService");

        SpringBeanDetail full = flatReport.getAllBeans().stream()
            .filter(b -> "fullService".equals(b.name()))
            .findFirst()
            .orElseThrow();
        assertThat(full.aliases()).containsExactly("alias1");
        assertThat(full.dependencies()).containsExactly("dep1");
    }

    @Test
    @DisplayName("parse returns empty report for null or blank inputs")
    void shouldHandleNullAndBlankInputs() {
        SpringBeansReport nullJson = parser.parse(1L, "TEST", null);
        assertThat(nullJson.totalBeans()).isZero();
        assertThat(nullJson.contexts()).isEmpty();

        SpringBeansReport blankJson = parser.parse(1L, "TEST", "   ");
        assertThat(blankJson.totalBeans()).isZero();

        SpringBeansReport nullMap = parser.parseMap(1L, "TEST", null);
        assertThat(nullMap.totalBeans()).isZero();

        SpringBeansReport emptyMap = parser.parseMap(1L, "TEST", Map.of());
        assertThat(emptyMap.totalBeans()).isZero();
    }

    @Test
    @DisplayName("parse gracefully ignores non-map context or bean entries")
    void shouldIgnoreInvalidContextEntries() {
        Map<String, Object> malformedContexts = Map.of(
            "contexts", Map.of("invalidEntry", "not-a-map")
        );

        SpringBeansReport report = parser.parseMap(1L, "TEST", malformedContexts);
        assertThat(report.totalBeans()).isZero();
    }
}
