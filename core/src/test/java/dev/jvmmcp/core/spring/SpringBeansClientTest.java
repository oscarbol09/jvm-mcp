package dev.jvmmcp.core.spring;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.model.SpringContextBeans;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringBeansClientTest {

    private static final String SAMPLE_ACTUATOR_JSON = """
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

    @Mock
    private ActuatorProbe actuatorProbe;

    @Mock
    private VirtualMachine virtualMachine;

    @Mock
    private MBeanServerConnection mbsc;

    private SpringBeansClient client;

    @BeforeEach
    void setUp() {
        client = new SpringBeansClient(actuatorProbe, new SpringBeansParser());
    }

    @Test
    @DisplayName("inspectBeans discovers beans via Actuator HTTP (Strategy 1)")
    void shouldDiscoverBeansViaActuatorHttp() {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(
            Optional.of(new ActuatorProbe.ProbeResult("http://localhost:8080/actuator/beans", SAMPLE_ACTUATOR_JSON))
        );

        SpringBeansReport report = client.inspectBeans(123L, virtualMachine, mbsc, null);

        assertThat(report).isNotNull();
        assertThat(report.pid()).isEqualTo(123L);
        assertThat(report.discoverySource()).contains("ACTUATOR_HTTP");
        assertThat(report.totalBeans()).isEqualTo(2);
    }

    @Test
    @DisplayName("inspectBeans falls back to JMX MBean operation 'beans' (Strategy 2)")
    void shouldDiscoverBeansViaJmxOperation() throws Exception {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());

        ObjectName endpointName = new ObjectName("org.springframework.boot:type=Endpoint,name=Beans");
        when(mbsc.isRegistered(endpointName)).thenReturn(true);
        when(mbsc.invoke(eq(endpointName), eq("beans"), any(), any())).thenReturn(SAMPLE_ACTUATOR_JSON);

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

        ObjectName endpointName = new ObjectName("org.springframework.boot:type=Endpoint,name=Beans");
        when(mbsc.isRegistered(endpointName)).thenReturn(true);
        when(mbsc.invoke(eq(endpointName), eq("beans"), any(), any())).thenThrow(new RuntimeException("Operation not found"));
        when(mbsc.getAttribute(endpointName, "Data")).thenReturn(Map.of(
            "userService", Map.of("type", "com.example.UserService", "scope", "singleton", "aliases", List.of(), "dependencies", List.of())
        ));

        SpringBeansReport report = client.inspectBeans(789L, virtualMachine, mbsc, null);

        assertThat(report).isNotNull();
        assertThat(report.totalBeans()).isEqualTo(1);
        assertThat(report.getAllBeans().get(0).name()).isEqualTo("userService");
    }

    @Test
    @DisplayName("inspectBeans falls back to JMX attribute 'beans' on lowercase endpoint name")
    void shouldDiscoverBeansViaJmxBeansAttribute() throws Exception {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(Optional.empty());

        ObjectName upperEndpoint = new ObjectName("org.springframework.boot:type=Endpoint,name=Beans");
        ObjectName lowerEndpoint = new ObjectName("org.springframework.boot:type=Endpoint,name=beans");
        when(mbsc.isRegistered(upperEndpoint)).thenReturn(false);
        when(mbsc.isRegistered(lowerEndpoint)).thenReturn(true);
        when(mbsc.invoke(eq(lowerEndpoint), eq("beans"), any(), any())).thenThrow(new RuntimeException());
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

        assertThat(report.discoverySource()).contains("NOT_AVAILABLE");
        assertThat(report.totalBeans()).isZero();
        assertThat(report.contexts()).isEmpty();
    }

    @Test
    @DisplayName("inspectBeans applies glob filtering on bean name, type, and alias")
    void shouldFilterBeansByGlobPattern() {
        when(actuatorProbe.probe(virtualMachine)).thenReturn(
            Optional.of(new ActuatorProbe.ProbeResult("http://localhost:8080/actuator/beans", SAMPLE_ACTUATOR_JSON))
        );

        // Filter matching type
        SpringBeansReport typeFiltered = client.inspectBeans(123L, virtualMachine, mbsc, "*Service*");
        assertThat(typeFiltered.totalBeans()).isEqualTo(1);
        assertThat(typeFiltered.getAllBeans().get(0).name()).isEqualTo("orderService");

        // Filter matching alias
        SpringBeansReport aliasFiltered = client.inspectBeans(123L, virtualMachine, mbsc, "orderSvc");
        assertThat(aliasFiltered.totalBeans()).isEqualTo(1);
        assertThat(aliasFiltered.getAllBeans().get(0).name()).isEqualTo("orderService");

        // Filter matching name
        SpringBeansReport nameFiltered = client.inspectBeans(123L, virtualMachine, mbsc, "orderRepository");
        assertThat(nameFiltered.totalBeans()).isEqualTo(1);
        assertThat(nameFiltered.getAllBeans().get(0).name()).isEqualTo("orderRepository");

        // Filter with wildcard returns all
        SpringBeansReport allFiltered = client.inspectBeans(123L, virtualMachine, mbsc, "*");
        assertThat(allFiltered.totalBeans()).isEqualTo(2);
    }

    @Test
    @DisplayName("inspectFromActuatorUrl correctly handles reachable vs unreachable URLs")
    void shouldInspectDirectActuatorUrl() {
        when(actuatorProbe.fetchFromUrl("http://reachable:8080")).thenReturn(Optional.of(SAMPLE_ACTUATOR_JSON));
        when(actuatorProbe.fetchFromUrl("http://unreachable:8080")).thenReturn(Optional.empty());

        SpringBeansReport reachableReport = client.inspectFromActuatorUrl(123L, "http://reachable:8080", null);
        assertThat(reachableReport.discoverySource()).contains("ACTUATOR_HTTP");
        assertThat(reachableReport.totalBeans()).isEqualTo(2);

        SpringBeansReport unreachableReport = client.inspectFromActuatorUrl(123L, "http://unreachable:8080", null);
        assertThat(unreachableReport.discoverySource()).contains("ACTUATOR_UNREACHABLE");
        assertThat(unreachableReport.totalBeans()).isZero();
    }

    @Test
    @DisplayName("getBeanDetail handles edge cases and finds beans by case-insensitive name or alias")
    void shouldHandleGetBeanDetail() {
        SpringBeanDetail bean = new SpringBeanDetail("orderService", List.of("orderSvc"), "singleton", "com.example.OrderService", null, List.of());
        SpringBeansReport report = new SpringBeansReport(123L, "TEST", 1, List.of(new SpringContextBeans("ctx", null, 1, List.of(bean))));

        assertThat(client.getBeanDetail(null, "orderService")).isEmpty();
        assertThat(client.getBeanDetail(report, null)).isEmpty();
        assertThat(client.getBeanDetail(report, "   ")).isEmpty();
        assertThat(client.getBeanDetail(report, "ORDERSERVICE")).contains(bean);
        assertThat(client.getBeanDetail(report, "ORDERSVC")).contains(bean);
        assertThat(client.getBeanDetail(report, "unknownBean")).isEmpty();
    }

    @Test
    @DisplayName("Default constructor initializes with default probe and parser")
    void shouldInstantiateWithDefaultConstructor() {
        SpringBeansClient defaultClient = new SpringBeansClient();
        assertThat(defaultClient).isNotNull();
    }
}
