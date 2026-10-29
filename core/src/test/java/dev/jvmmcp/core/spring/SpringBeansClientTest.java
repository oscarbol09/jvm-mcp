package dev.jvmmcp.core.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.model.SpringContextBeans;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpringBeansClientTest {

    private static final String SAMPLE_ACTUATOR_JSON =
            """
        {
          "contexts": {
            "app": {
              "beans": {
                "orderService": {
                  "aliases": ["orderSvc"],
                  "scope": "singleton",
                  "type": "com.example.OrderService",
                  "resource": null,
                  "dependencies": ["orderRepository"]
                },
                "orderRepository": {
                  "aliases": [],
                  "scope": "singleton",
                  "type": "com.example.OrderRepository",
                  "resource": null,
                  "dependencies": []
                }
              }
            }
          }
        }
        """;

    @Mock private ActuatorProbe actuatorProbe;

    @Mock private VirtualMachine virtualMachine;

    @Mock private MBeanServerConnection mbsc;

    private SpringBeansClient client;

    @BeforeEach
    void setUp() {
        client = new SpringBeansClient(actuatorProbe, new SpringBeansParser());
    }

    @Test
    @DisplayName("inspectBeans discovers beans via Actuator HTTP (Strategy 1)")
    void shouldDiscoverBeansViaActuatorHttp() {
        when(actuatorProbe.probe(virtualMachine))
                .thenReturn(
                        Optional.of(
                                new ActuatorProbe.ProbeResult(
                                        "http://localhost:8080/actuator/beans",
                                        SAMPLE_ACTUATOR_JSON)));

        SpringBeansReport report = client.inspectBeans(123L, virtualMachine, mbsc, null);

        assertThat(report).isNotNull();
        assertThat(report.pid()).isEqualTo(123L);
        assertThat(report.discoverySource()).contains("ACTUATOR_HTTP");
        assertThat(report.totalBeans()).isEqualTo(2);
    }

    @Test
    @DisplayName("lazy JMX provider is not called when Actuator HTTP returns beans")
    void shouldSkipJmxConnectionWhenActuatorHttpSucceeds() throws Exception {
        when(actuatorProbe.probe(virtualMachine))
                .thenReturn(
                        Optional.of(
                                new ActuatorProbe.ProbeResult(
                                        "http://localhost:8080/actuator/beans",
                                        SAMPLE_ACTUATOR_JSON)));

        SpringBeansReport report =
                client.inspectBeansWithJmxProvider(
                        123L,
                        virtualMachine,
                        () -> {
                            throw new AssertionError("JMX must remain unopened");
                        },
                        "*Service*");

        assertThat(report.discoverySource()).contains("ACTUATOR_HTTP");
        assertThat(report.totalBeans()).isEqualTo(1);
        assertThat(report.getAllBeans().get(0).name()).isEqualTo("orderService");
    }

    @Test
    @DisplayName("lazy JMX provider is used and closed after Actuator HTTP misses")
    void shouldUseAndCloseJmxConnectionAfterActuatorHttpMisses() throws Exception {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());

        JmxConnectionManager manager = org.mockito.Mockito.mock(JmxConnectionManager.class);
        when(manager.getMBeanServerConnection()).thenReturn(mbsc);

        ObjectName endpointName =
                new ObjectName("org.springframework.boot:type=Endpoint,name=Beans");

        when(mbsc.isRegistered(endpointName)).thenReturn(true);
        when(mbsc.invoke(eq(endpointName), eq("beans"), any(), any()))
                .thenReturn(SAMPLE_ACTUATOR_JSON);

        SpringBeansReport report =
                client.inspectBeansWithJmxProvider(456L, virtualMachine, () -> manager, null);

        assertThat(report.discoverySource()).contains("JMX_MBEAN");
        verify(manager).close();
    }

    @Test
    @DisplayName("lazy JMX connection failure propagates after Actuator HTTP misses")
    void shouldPropagateJmxConnectionFailureAfterHttpMisses() {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                client.inspectBeansWithJmxProvider(
                                        123L,
                                        virtualMachine,
                                        () -> {
                                            throw new java.io.IOException("JMX unavailable");
                                        },
                                        null))
                .isInstanceOf(java.io.IOException.class)
                .hasMessage("JMX unavailable");
    }

    @Test
    @DisplayName("inspectBeans falls back to JMX MBean operation 'beans' (Strategy 2)")
    void shouldDiscoverBeansViaJmxOperation() throws Exception {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());

        ObjectName endpointName =
                new ObjectName("org.springframework.boot:type=Endpoint,name=Beans");

        when(mbsc.isRegistered(endpointName)).thenReturn(true);
        when(mbsc.invoke(eq(endpointName), eq("beans"), any(), any()))
                .thenReturn(SAMPLE_ACTUATOR_JSON);

        SpringBeansReport report = client.inspectBeans(456L, virtualMachine, mbsc, null);

        assertThat(report).isNotNull();
        assertThat(report.pid()).isEqualTo(456L);
        assertThat(report.discoverySource()).contains("JMX_MBEAN");
        assertThat(report.totalBeans()).isEqualTo(2);
    }

    @Test
    @DisplayName("inspectBeans falls back to JMX attribute 'Data' when operation throws")
    void shouldDiscoverBeansViaJmxDataAttribute() throws Exception {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());

        ObjectName endpointName =
                new ObjectName("org.springframework.boot:type=Endpoint,name=Beans");

        when(mbsc.isRegistered(endpointName)).thenReturn(true);
        when(mbsc.invoke(eq(endpointName), eq("beans"), any(), any()))
                .thenThrow(new RuntimeException("Operation not found"));

        when(mbsc.getAttribute(endpointName, "Data"))
                .thenReturn(
                        Map.of(
                                "userService",
                                Map.of(
                                        "type",
                                        "com.example.UserService",
                                        "scope",
                                        "singleton",
                                        "aliases",
                                        List.of(),
                                        "dependencies",
                                        List.of())));

        SpringBeansReport report = client.inspectBeans(789L, virtualMachine, mbsc, null);

        assertThat(report).isNotNull();
        assertThat(report.totalBeans()).isEqualTo(1);
        assertThat(report.getAllBeans().get(0).name()).isEqualTo("userService");
    }

    @Test
    @DisplayName("inspectBeans falls back to JMX attribute 'beans' on lowercase endpoint name")
    void shouldDiscoverBeansViaJmxBeansAttribute() throws Exception {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());

        ObjectName upperEndpoint =
                new ObjectName("org.springframework.boot:type=Endpoint,name=Beans");
        ObjectName lowerEndpoint =
                new ObjectName("org.springframework.boot:type=Endpoint,name=beans");

        when(mbsc.isRegistered(upperEndpoint)).thenReturn(false);
        when(mbsc.isRegistered(lowerEndpoint)).thenReturn(true);
        when(mbsc.invoke(eq(lowerEndpoint), eq("beans"), any(), any()))
                .thenThrow(new RuntimeException());
        when(mbsc.getAttribute(lowerEndpoint, "Data")).thenThrow(new RuntimeException());
        when(mbsc.getAttribute(lowerEndpoint, "beans")).thenReturn(SAMPLE_ACTUATOR_JSON);

        SpringBeansReport report = client.inspectBeans(999L, virtualMachine, mbsc, null);

        assertThat(report).isNotNull();
        assertThat(report.totalBeans()).isEqualTo(2);
    }

    @Test
    @DisplayName("inspectBeans returns NOT_AVAILABLE when both Actuator HTTP and JMX fail")
    void shouldReturnNotAvailableWhenBothStrategiesFail() {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());

        SpringBeansReport report = client.inspectBeans(123L, virtualMachine, null, null);

        assertThat(report.discoverySource())
                .contains("NOT_AVAILABLE")
                .contains("Actuator HTTP discovery returned no reachable beans endpoint")
                .contains("JMX discovery was not attempted");
        assertThat(report.totalBeans()).isZero();
        assertThat(report.contexts()).isEmpty();
    }

    @Test
    @DisplayName("inspectBeans reports missing JMX endpoint names after Actuator misses")
    void shouldReportMissingJmxMBeans() throws Exception {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());
        when(mbsc.isRegistered(any(ObjectName.class))).thenReturn(false);

        SpringBeansReport report = client.inspectBeans(123L, virtualMachine, mbsc, null);

        assertThat(report.discoverySource())
                .contains("NOT_AVAILABLE")
                .contains("org.springframework.boot:type=Endpoint,name=Beans")
                .contains("org.springframework.boot:type=Endpoint,name=beans");
        assertThat(report.totalBeans()).isZero();
    }

    @Test
    @DisplayName("inspectBeans reports JMX operation and attribute failures safely")
    void shouldReportJmxOperationFailure() throws Exception {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());
        ObjectName upperEndpoint =
                new ObjectName("org.springframework.boot:type=Endpoint,name=Beans");
        ObjectName lowerEndpoint =
                new ObjectName("org.springframework.boot:type=Endpoint,name=beans");
        when(mbsc.isRegistered(upperEndpoint)).thenReturn(true);
        when(mbsc.isRegistered(lowerEndpoint)).thenReturn(false);
        when(mbsc.invoke(eq(upperEndpoint), eq("beans"), any(), any()))
                .thenThrow(new IllegalStateException("credentials must not leak"));
        when(mbsc.getAttribute(upperEndpoint, "Data"))
                .thenThrow(new UnsupportedOperationException("private detail"));
        when(mbsc.getAttribute(upperEndpoint, "beans"))
                .thenThrow(new IllegalArgumentException("private detail"));

        SpringBeansReport report = client.inspectBeans(123L, virtualMachine, mbsc, null);

        assertThat(report.discoverySource())
                .contains("operation 'beans' failed (IllegalStateException)")
                .contains("attribute 'Data' failed (UnsupportedOperationException)")
                .contains("attribute 'beans' failed (IllegalArgumentException)")
                .doesNotContain("credentials must not leak")
                .doesNotContain("private detail");
    }

    @Test
    @DisplayName("a reachable empty Actuator context is a successful empty result")
    void shouldPreserveEmptyActuatorContext() {
        when(actuatorProbe.probe(virtualMachine))
                .thenReturn(
                        Optional.of(
                                new ActuatorProbe.ProbeResult(
                                        "http://localhost:8080/actuator/beans",
                                        "{\"contexts\":{\"application\":{\"beans\":{}}}}")));

        SpringBeansReport report = client.inspectBeans(123L, virtualMachine, null, null);

        assertThat(report.discoverySource()).contains("ACTUATOR_HTTP");
        assertThat(report.totalBeans()).isZero();
        assertThat(report.contexts()).hasSize(1);
    }

    @Test
    @DisplayName("inspectBeans applies glob filtering on bean name, type, and alias")
    void shouldFilterBeansByGlobPattern() {
        when(actuatorProbe.probe(virtualMachine))
                .thenReturn(
                        Optional.of(
                                new ActuatorProbe.ProbeResult(
                                        "http://localhost:8080/actuator/beans",
                                        SAMPLE_ACTUATOR_JSON)));

        // Filter matching type
        SpringBeansReport typeFiltered =
                client.inspectBeans(123L, virtualMachine, mbsc, "*Service*");

        assertThat(typeFiltered.totalBeans()).isEqualTo(1);
        assertThat(typeFiltered.getAllBeans().get(0).name()).isEqualTo("orderService");

        // Filter matching alias
        SpringBeansReport aliasFiltered =
                client.inspectBeans(123L, virtualMachine, mbsc, "orderSvc");

        assertThat(aliasFiltered.totalBeans()).isEqualTo(1);
        assertThat(aliasFiltered.getAllBeans().get(0).name()).isEqualTo("orderService");

        // Filter matching name
        SpringBeansReport nameFiltered =
                client.inspectBeans(123L, virtualMachine, mbsc, "orderRepository");

        assertThat(nameFiltered.totalBeans()).isEqualTo(1);
        assertThat(nameFiltered.getAllBeans().get(0).name()).isEqualTo("orderRepository");

        // Filter with wildcard returns all
        SpringBeansReport allFiltered = client.inspectBeans(123L, virtualMachine, mbsc, "*");

        assertThat(allFiltered.totalBeans()).isEqualTo(2);
    }

    @Test
    @DisplayName("inspectFromActuatorUrl correctly handles reachable vs unreachable URLs")
    void shouldInspectDirectActuatorUrl() {
        when(actuatorProbe.fetchFromUrl("http://reachable:8080"))
                .thenReturn(Optional.of(SAMPLE_ACTUATOR_JSON));
        when(actuatorProbe.fetchFromUrl("http://unreachable:8080")).thenReturn(Optional.empty());

        SpringBeansReport reachableReport =
                client.inspectFromActuatorUrl(123L, "http://reachable:8080", null);

        assertThat(reachableReport.discoverySource()).contains("ACTUATOR_HTTP");
        assertThat(reachableReport.totalBeans()).isEqualTo(2);

        SpringBeansReport unreachableReport =
                client.inspectFromActuatorUrl(123L, "http://unreachable:8080", null);

        assertThat(unreachableReport.discoverySource()).contains("ACTUATOR_UNREACHABLE");
        assertThat(unreachableReport.totalBeans()).isZero();
    }

    @Test
    @DisplayName("getBeanDetail handles invalid input, null bean names, and aliases safely")
    void shouldHandleGetBeanDetail() {
        SpringBeanDetail nullNameBean =
                new SpringBeanDetail(
                        null, null, "singleton", "com.example.Unknown", null, List.of());
        SpringBeanDetail bean =
                new SpringBeanDetail(
                        "orderService",
                        List.of("orderSvc"),
                        "singleton",
                        "com.example.OrderService",
                        null,
                        List.of());

        SpringBeansReport report =
                new SpringBeansReport(
                        123L,
                        "TEST",
                        2,
                        List.of(
                                new SpringContextBeans(
                                        "ctx", null, 2, List.of(nullNameBean, bean))));

        assertThat(client.getBeanDetail(null, "orderService")).isEmpty();
        assertThat(client.getBeanDetail(report, null)).isEmpty();
        assertThat(client.getBeanDetail(report, "   ")).isEmpty();

        assertThat(client.getBeanDetail(report, "orderService")).contains(bean);
        assertThat(client.getBeanDetail(report, "ORDERSERVICE")).contains(bean);
        assertThat(client.getBeanDetail(report, "ORDERSVC")).contains(bean);
        assertThat(client.getBeanDetail(report, "unknownBean")).isEmpty();

        assertThat(client.getBeanDetailMatch(report, "orderService"))
                .contains(new SpringBeansClient.BeanDetailMatch("ctx", bean, "EXACT"));

        assertThat(client.getBeanDetailMatch(report, "ORDERSERVICE"))
                .contains(
                        new SpringBeansClient.BeanDetailMatch(
                                "ctx", bean, "CASE_INSENSITIVE_FALLBACK"));
    }

    @Test
    @DisplayName(
            "getBeanDetail prefers the exact case-sensitive name even if another context appears first")
    void shouldPreferExactNameOverCaseInsensitiveName() {
        SpringBeanDetail upperCaseBean =
                new SpringBeanDetail(
                        "DataSource",
                        List.of(),
                        "singleton",
                        "example.UpperDataSource",
                        null,
                        List.of());
        SpringBeanDetail exactBean =
                new SpringBeanDetail(
                        "dataSource",
                        List.of(),
                        "singleton",
                        "example.LowerDataSource",
                        null,
                        List.of());

        SpringBeansReport report =
                new SpringBeansReport(
                        123L,
                        "TEST",
                        2,
                        List.of(
                                new SpringContextBeans(
                                        "uppercase-context", null, 1, List.of(upperCaseBean)),
                                new SpringContextBeans(
                                        "exact-context", null, 1, List.of(exactBean))));

        assertThat(client.getBeanDetailMatch(report, "dataSource"))
                .contains(
                        new SpringBeansClient.BeanDetailMatch("exact-context", exactBean, "EXACT"));
    }

    @Test
    @DisplayName("getBeanDetail reports ambiguity when the exact name exists in multiple contexts")
    void shouldReportAmbiguousBeanAcrossContexts() {
        SpringBeanDetail firstBean =
                new SpringBeanDetail(
                        "orderService",
                        List.of(),
                        "singleton",
                        "example.FirstOrderService",
                        null,
                        List.of());
        SpringBeanDetail secondBean =
                new SpringBeanDetail(
                        "orderService",
                        List.of(),
                        "singleton",
                        "example.SecondOrderService",
                        null,
                        List.of());

        SpringBeansReport report =
                new SpringBeansReport(
                        123L,
                        "TEST",
                        2,
                        List.of(
                                new SpringContextBeans("context-a", null, 1, List.of(firstBean)),
                                new SpringContextBeans("context-b", null, 1, List.of(secondBean))));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.getBeanDetailMatch(report, "orderService"))
                .isInstanceOf(SpringBeansClient.AmbiguousBeanMatchException.class)
                .hasMessageContaining("context-a", "context-b");
    }

    @Test
    @DisplayName(
            "getBeanDetail reports ambiguity when case-insensitive fallback matches multiple contexts")
    void shouldReportAmbiguousCaseInsensitiveFallback() {
        SpringBeanDetail lowerCaseBean =
                new SpringBeanDetail(
                        "dataSource",
                        List.of(),
                        "singleton",
                        "example.LowerDataSource",
                        null,
                        List.of());
        SpringBeanDetail upperCaseBean =
                new SpringBeanDetail(
                        "DataSource",
                        List.of(),
                        "singleton",
                        "example.UpperDataSource",
                        null,
                        List.of());

        SpringBeansReport report =
                new SpringBeansReport(
                        123L,
                        "TEST",
                        2,
                        List.of(
                                new SpringContextBeans(
                                        "context-a", null, 1, List.of(lowerCaseBean)),
                                new SpringContextBeans(
                                        "context-b", null, 1, List.of(upperCaseBean))));

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.getBeanDetailMatch(report, "DATASOURCE"))
                .isInstanceOf(SpringBeansClient.AmbiguousBeanMatchException.class)
                .hasMessageContaining("context-a", "context-b");
    }

    @Test
    @DisplayName("Default constructor initializes with default probe and parser")
    void shouldInstantiateWithDefaultConstructor() {
        SpringBeansClient defaultClient = new SpringBeansClient();
        assertThat(defaultClient).isNotNull();
    }

    @Test
    void shouldDiscoverOnlyDetailCandidatesOverHttp() throws Exception {
        when(actuatorProbe.probe(virtualMachine))
                .thenReturn(
                        Optional.of(
                                new ActuatorProbe.ProbeResult(
                                        "http://localhost/actuator/beans", SAMPLE_ACTUATOR_JSON)));
        SpringBeansReport report =
                client.inspectBeanDetailWithJmxProvider(
                        123L,
                        virtualMachine,
                        () -> {
                            throw new AssertionError("JMX must stay unopened");
                        },
                        "ORDERSVC");
        assertThat(report.totalBeans()).isEqualTo(1);
        assertThat(report.getAllBeans().getFirst().dependencies())
                .containsExactly("orderRepository");
        assertThat(client.getBeanDetailMatch(report, "ORDERSVC").orElseThrow().matchedBy())
                .isEqualTo("CASE_INSENSITIVE_FALLBACK");
    }

    @Test
    void shouldRetainExactPriorityAndAmbiguityThroughJmxDiscovery() throws Exception {
        JmxConnectionManager manager = org.mockito.Mockito.mock(JmxConnectionManager.class);
        when(manager.getMBeanServerConnection()).thenReturn(mbsc);
        ObjectName endpoint = new ObjectName("org.springframework.boot:type=Endpoint,name=Beans");
        when(mbsc.isRegistered(endpoint)).thenReturn(true);
        Map<String, Object> payload =
                Map.of(
                        "contexts",
                        Map.of(
                                "a",
                                        Map.of(
                                                "beans",
                                                Map.of(
                                                        "DataSource",
                                                        "example.Upper",
                                                        "unrelated",
                                                        "example.Other")),
                                "b", Map.of("beans", Map.of("dataSource", "example.Lower"))));
        for (Object result : List.of(payload, dev.jvmmcp.core.util.SimpleJson.toJson(payload))) {
            when(mbsc.invoke(eq(endpoint), eq("beans"), any(), any())).thenReturn(result);
            SpringBeansReport report =
                    client.inspectBeanDetailWithJmxProvider(
                            123L, null, () -> manager, "dataSource");
            assertThat(report.totalBeans()).isEqualTo(2);
            var match = client.getBeanDetailMatch(report, "dataSource").orElseThrow();
            assertThat(match.contextId()).isEqualTo("b");
            assertThat(match.matchedBy()).isEqualTo("EXACT");
            org.assertj.core.api.Assertions.assertThatThrownBy(
                            () -> client.getBeanDetailMatch(report, "DATASOURCE"))
                    .isInstanceOf(SpringBeansClient.AmbiguousBeanMatchException.class)
                    .hasMessageContaining("a", "b");
        }
        verify(manager, org.mockito.Mockito.times(2)).close();
    }

    @Test
    void shouldRetainAmbiguousExactAliasesAcrossContexts() throws Exception {
        String json =
                """
                {"contexts": {
                  "a": {"beans": {"first": {"aliases": ["shared"]}}},
                  "b": {"beans": {"second": {"aliases": ["shared"]}}}
                }}
                """;
        when(actuatorProbe.probe(virtualMachine))
                .thenReturn(
                        Optional.of(
                                new ActuatorProbe.ProbeResult(
                                        "http://localhost/actuator/beans", json)));
        SpringBeansReport report =
                client.inspectBeanDetailWithJmxProvider(
                        123L,
                        virtualMachine,
                        () -> {
                            throw new AssertionError("JMX must stay unopened");
                        },
                        "shared");
        assertThat(report.totalBeans()).isEqualTo(2);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> client.getBeanDetailMatch(report, "shared"))
                .isInstanceOf(SpringBeansClient.AmbiguousBeanMatchException.class);
    }
}
