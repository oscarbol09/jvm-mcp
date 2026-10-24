package dev.jvmmcp.core.spring;

import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

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
        assertThat(orderService.get().resource())
            .isEqualTo("class path resource [com/example/service/OrderService.class]");

        SpringBeanDetail paymentClient = report.getAllBeans().stream()
            .filter(b -> "paymentClient".equals(b.name()))
            .findFirst()
            .orElseThrow();
        assertThat(paymentClient.resource()).isNull();
    }

    @Test
    @DisplayName("parse converts non-string parent ids and resources to strings")
    void shouldConvertNonStringParentIdsAndResources() {
        Map<String, Object> payload = Map.of(
            "contexts", Map.of(
                "numericContext", Map.of(
                    "parentId", 42,
                    "beans", Map.of("numericResource", Map.of(
                        "aliases", java.util.List.of("numericAlias"),
                        "scope", "prototype",
                        "type", "com.example.NumericResource",
                        "resource", 17,
                        "dependencies", java.util.List.of("numericDependency")
                    ))
                ),
                "booleanContext", Map.of(
                    "parentId", true,
                    "beans", Map.of("booleanResource", Map.of(
                        "type", "com.example.BooleanResource",
                        "resource", false
                    ))
                ),
                "objectContext", Map.of(
                    "parentId", Map.of("tenant", "blue"),
                    "beans", Map.of("objectResource", Map.of(
                        "type", "com.example.ObjectResource",
                        "resource", Map.of("path", "generated")
                    ))
                )
            )
        );

        SpringBeansReport report = parser.parseMap(3L, "JMX", payload);

        assertThat(report.totalBeans()).isEqualTo(3);
        assertThat(report.contexts()).extracting("parentId")
            .containsExactlyInAnyOrder("42", "true", "{tenant=blue}");

        SpringBeanDetail numeric = report.getAllBeans().stream()
            .filter(bean -> bean.name().equals("numericResource"))
            .findFirst()
            .orElseThrow();
        assertThat(numeric.resource()).isEqualTo("17");
        assertThat(numeric.aliases()).containsExactly("numericAlias");
        assertThat(numeric.scope()).isEqualTo("prototype");
        assertThat(numeric.type()).isEqualTo("com.example.NumericResource");
        assertThat(numeric.dependencies()).containsExactly("numericDependency");

        assertThat(report.getAllBeans()).extracting(SpringBeanDetail::resource)
            .containsExactlyInAnyOrder("17", "false", "{path=generated}");
    }

    @Test
    @DisplayName("parse ignores non-map beans property")
    void shouldIgnoreNonMapBeans() {
        String actuatorPayload = """
            {
              "contexts": {
                "application": {
                  "beans": "not-a-map"
                }
              }
            }
            """;

        SpringBeansReport report = parser.parse(45231L, "TEST", actuatorPayload);
        assertThat(report.totalBeans()).isEqualTo(0);
    }

    @Test
    @DisplayName("parse ignores bad bean types")
    void shouldIgnoreBadBeanTypes() {
        String actuatorPayload = """
            {
              "contexts": {
                "application": {
                  "beans": {
                    "goodStringBean": "com.example.StringBean",
                    "badIntBean": 123
                  }
                }
              }
            }
            """;

        SpringBeansReport report = parser.parse(45231L, "TEST", actuatorPayload);
        assertThat(report.totalBeans()).isEqualTo(1);
        assertThat(report.getAllBeans().get(0).type()).isEqualTo("com.example.StringBean");
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
        assertThat(directReport.contexts().get(0).parentId()).isNull();
        assertThat(directReport.getAllBeans().get(0).resource()).isNull();

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
        assertThat(full.resource()).isNull();
        assertThat(flatReport.contexts().get(0).parentId()).isNull();
    }

    @Test
    @DisplayName("parse reads aliases and dependencies returned as Object[] over JMX")
    void shouldParseObjectArrayAliasesAndDependencies() {
        Map<String, Object> payload = Map.of(
            "contexts", Map.of(
                "application", Map.of(
                    "beans", Map.of("orderService", Map.of(
                        "type", "com.example.OrderService",
                        "aliases", new Object[]{"orderSvc"},
                        "dependencies", new Object[]{"orderRepository", "paymentClient"}
                    ))
                )
            )
        );

        SpringBeanDetail bean = parser.parseMap(1L, "JMX", payload).getAllBeans().get(0);

        assertThat(bean.aliases()).containsExactly("orderSvc");
        assertThat(bean.dependencies()).containsExactly("orderRepository", "paymentClient");
    }

    @Test
    @DisplayName("parse skips null elements and stringifies values in Object[] aliases and dependencies")
    void shouldSkipNullElementsInObjectArrays() {
        Map<String, Object> payload = Map.of(
            "beans", Map.of("paymentClient", Map.of(
                "type", "com.example.PaymentClient",
                "aliases", new Object[]{null, "payments"},
                "dependencies", new Object[]{"httpClient", null, 42}
            ))
        );

        SpringBeanDetail bean = parser.parseMap(1L, "JMX", payload).getAllBeans().get(0);

        assertThat(bean.aliases()).containsExactly("payments");
        assertThat(bean.dependencies()).containsExactly("httpClient", "42");
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
