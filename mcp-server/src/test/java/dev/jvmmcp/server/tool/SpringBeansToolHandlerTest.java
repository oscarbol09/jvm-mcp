package dev.jvmmcp.server.tool;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.model.SpringContextBeans;
import dev.jvmmcp.core.spring.SpringBeansClient;
import dev.jvmmcp.core.spring.ActuatorProbe;
import dev.jvmmcp.core.spring.SpringBeansParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpringBeansToolHandlerTest {

    @Mock
    private JvmAttachService mockAttachService;

    @Mock
    private SpringBeansClient mockBeansClient;

    @Mock
    private VirtualMachine mockVm;

    @Mock
    private ActuatorProbe mockActuatorProbe;

    @Test
    @DisplayName("constructors initialize properly")
    void testConstructors() {
        SpringBeansToolHandler h1 = new SpringBeansToolHandler();
        assertThat(h1.toolNames()).isNotEmpty();

        SpringBeansToolHandler h2 = new SpringBeansToolHandler(mockAttachService);
        assertThat(h2.toolNames()).isNotEmpty();
    }

    @Test
    @DisplayName("toolNames and toolDefinitions return both Spring Bean tools")
    void testToolNamesAndDefinitions() {
        SpringBeansToolHandler handler = new SpringBeansToolHandler(mockAttachService, mockBeansClient);

        assertThat(handler.toolNames()).containsExactlyInAnyOrder("list_spring_beans", "get_bean_detail");
        assertThat(handler.toolDefinitions()).hasSize(2);
    }

    @Test
    @DisplayName("execute throws IllegalStateException when attach fails")
    void testAttachFailure() {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.processNotFound("123"));

        SpringBeansToolHandler handler = new SpringBeansToolHandler(mockAttachService, mockBeansClient);
        assertThatThrownBy(() -> handler.execute("list_spring_beans", Map.of("pid", 123)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("PID 123 was not found");
    }

    @Test
    @DisplayName("execute throws IllegalArgumentException when pid is missing")
    void testMissingPid() {
        SpringBeansToolHandler handler = new SpringBeansToolHandler(mockAttachService, mockBeansClient);
        assertThatThrownBy(() -> handler.execute("list_spring_beans", Map.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required argument: pid");
    }

    @Test
    @DisplayName("detaches on JMX connection error")
    void testDetachOnJmxConnectionError() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", mockVm));

        SpringBeansToolHandler handler = new SpringBeansToolHandler(
            mockAttachService,
            mockBeansClient,
            (vm, p) -> { throw new IOException("JMX error"); }
        );
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any())).thenAnswer(invocation -> {
            SpringBeansClient.JmxConnectionProvider provider = invocation.getArgument(2);
            provider.connect();
            return null;
        });

        assertThatThrownBy(() -> handler.execute("list_spring_beans", Map.of("pid", 123)))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("JMX error");

        verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("detaches and ignores detachment exception during cleanup")
    void testDetachThrowsExceptionIgnored() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", mockVm));
        doThrow(new RuntimeException("Detach failed")).when(mockAttachService).detach(mockVm);

        SpringBeansToolHandler handler = new SpringBeansToolHandler(
            mockAttachService,
            mockBeansClient,
            (vm, p) -> { throw new IOException("JMX error"); }
        );
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any())).thenAnswer(invocation -> {
            SpringBeansClient.JmxConnectionProvider provider = invocation.getArgument(2);
            provider.connect();
            return null;
        });

        assertThatThrownBy(() -> handler.execute("list_spring_beans", Map.of("pid", 123)))
            .isInstanceOf(IOException.class);

        verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("Actuator discovery avoids JMX and detaches the attached VM")
    void testActuatorDiscoverySkipsJmxConnection() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", mockVm));
        when(mockActuatorProbe.probe(mockVm)).thenReturn(Optional.of(new ActuatorProbe.ProbeResult(
            "http://localhost:8080/actuator/beans",
            """
                {"contexts":{"application":{"beans":{"orderService":{"aliases":[],"scope":"singleton","type":"example.OrderService","dependencies":[]}}}}}
                """
        )));

        SpringBeansClient client = new SpringBeansClient(mockActuatorProbe, new SpringBeansParser());
        SpringBeansToolHandler handler = new SpringBeansToolHandler(
            mockAttachService,
            client,
            (vm, p) -> { throw new AssertionError("JMX must not be opened when Actuator responds"); }
        );

        Map<String, Object> result = handler.execute("list_spring_beans", Map.of("pid", 123));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text").toString()).contains("ACTUATOR_HTTP", "orderService");
        verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("list_spring_beans returns report JSON")
    void testListSpringBeansSuccess() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));
        SpringBeanDetail bean = new SpringBeanDetail("orderService", List.of(), "singleton", "com.example.OrderService", null, List.of());
        SpringBeansReport report = new SpringBeansReport(123, "JMX", 1, List.of(
            new SpringContextBeans("root", null, 1, List.of(bean))
        ));
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), eq("*Service*"))).thenAnswer(invocation -> {
            SpringBeansClient.JmxConnectionProvider provider = invocation.getArgument(2);
            provider.connect().close();
            return report;
        });

        SpringBeansToolHandler handler = new SpringBeansToolHandler(
            mockAttachService,
            mockBeansClient,
            (vm, p) -> JmxConnectionManager.connectLocal()
        );

        Map<String, Object> result = handler.execute("list_spring_beans", Map.of("pid", 123, "filter", "*Service*"));
        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("text").toString()).contains("orderService");
    }

    @Test
    @DisplayName("get_bean_detail returns bean detail JSON")
    void testGetBeanDetailSuccess() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));
        SpringBeanDetail bean = new SpringBeanDetail("orderService", List.of(), "singleton", "com.example.OrderService", null, List.of());
        SpringBeansReport report = new SpringBeansReport(123, "JMX", 1, List.of(
            new SpringContextBeans("root", null, 1, List.of(bean))
        ));
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any())).thenAnswer(invocation -> {
            SpringBeansClient.JmxConnectionProvider provider = invocation.getArgument(2);
            provider.connect().close();
            return report;
        });
        when(mockBeansClient.getBeanDetail(report, "orderService")).thenReturn(Optional.of(bean));

        SpringBeansToolHandler handler = new SpringBeansToolHandler(
            mockAttachService,
            mockBeansClient,
            (vm, p) -> JmxConnectionManager.connectLocal()
        );

        Map<String, Object> result = handler.execute("get_bean_detail", Map.of("pid", 123, "beanName", "orderService"));
        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("text").toString()).contains("com.example.OrderService");
    }

    @Test
    @DisplayName("get_bean_detail returns descriptive error and isError true when bean is not found")
    void testGetBeanDetailNotFound() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));
        SpringBeansReport report = new SpringBeansReport(123, "JMX", 1, List.of(
            new SpringContextBeans("root", null, 0, List.of())
        ));
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any())).thenAnswer(invocation -> {
            SpringBeansClient.JmxConnectionProvider provider = invocation.getArgument(2);
            provider.connect().close();
            return report;
        });
        when(mockBeansClient.getBeanDetail(report, "missingBean")).thenReturn(Optional.empty());

        SpringBeansToolHandler handler = new SpringBeansToolHandler(
            mockAttachService,
            mockBeansClient,
            (vm, p) -> JmxConnectionManager.connectLocal()
        );

        Map<String, Object> result = handler.execute("get_bean_detail", Map.of("pid", 123, "beanName", "missingBean"));
        assertThat(result.get("isError")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("text").toString())
            .contains("Bean not found: 'missingBean'", "list_spring_beans");
    }

    @Test
    @DisplayName("get_bean_detail throws when beanName is missing")
    void testGetBeanDetailMissingName() {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));

        SpringBeansToolHandler handler = new SpringBeansToolHandler(
            mockAttachService,
            mockBeansClient,
            (vm, p) -> JmxConnectionManager.connectLocal()
        );

        assertThatThrownBy(() -> handler.execute("get_bean_detail", Map.of("pid", 123)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Missing required argument: beanName");
    }

    @Test
    @DisplayName("execute throws on unknown tool name")
    void testUnknownTool() {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));

        SpringBeansToolHandler handler = new SpringBeansToolHandler(
            mockAttachService,
            mockBeansClient,
            (vm, p) -> JmxConnectionManager.connectLocal()
        );

        assertThatThrownBy(() -> handler.execute("unknown_beans_tool", Map.of("pid", 123)))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Unknown Spring Beans tool");
    }
}
