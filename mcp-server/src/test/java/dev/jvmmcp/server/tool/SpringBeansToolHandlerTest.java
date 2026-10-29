package dev.jvmmcp.server.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.model.SpringBeanDetail;
import dev.jvmmcp.core.model.SpringBeansReport;
import dev.jvmmcp.core.model.SpringContextBeans;
import dev.jvmmcp.core.spring.ActuatorProbe;
import dev.jvmmcp.core.spring.SpringBeansClient;
import dev.jvmmcp.core.spring.SpringBeansParser;
import dev.jvmmcp.core.util.SimpleJson;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class SpringBeansToolHandlerTest {

    @Mock private JvmAttachService mockAttachService;

    @Mock private SpringBeansClient mockBeansClient;

    @Mock private VirtualMachine mockVm;

    @Mock private ActuatorProbe mockActuatorProbe;

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
        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(mockAttachService, mockBeansClient);

        assertThat(handler.toolNames())
                .containsExactlyInAnyOrder("list_spring_beans", "get_bean_detail");
        assertThat(handler.toolDefinitions()).hasSize(2);
    }

    @Test
    @DisplayName("execute throws IllegalStateException when attach fails")
    void testAttachFailure() {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.processNotFound("123"));

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(mockAttachService, mockBeansClient);
        assertThatThrownBy(() -> handler.execute("list_spring_beans", Map.of("pid", 123)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PID 123 was not found");
    }

    @Test
    @DisplayName("execute throws IllegalArgumentException when pid is missing")
    void testMissingPid() {
        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(mockAttachService, mockBeansClient);
        assertThatThrownBy(() -> handler.execute("list_spring_beans", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required argument: pid");
    }

    @Test
    @DisplayName("detaches on JMX connection error")
    void testDetachOnJmxConnectionError() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", mockVm));

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(
                        mockAttachService,
                        mockBeansClient,
                        (vm, p) -> {
                            throw new IOException("JMX error");
                        });
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            SpringBeansClient.JmxConnectionProvider provider =
                                    invocation.getArgument(2);
                            provider.connect();
                            return null;
                        });

        Map<String, Object> result = handler.execute("list_spring_beans", Map.of("pid", 123));
        assertThat(result.get("isError")).isEqualTo(true);
        assertThat(
                        ((List<Map<String, Object>>) result.get("content"))
                                .get(0)
                                .get("text")
                                .toString())
                .contains("JMX connection failed (IOException)");

        verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("detaches and ignores detachment exception during cleanup")
    void testDetachThrowsExceptionIgnored() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", mockVm));
        doThrow(new RuntimeException("Detach failed")).when(mockAttachService).detach(mockVm);

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(
                        mockAttachService,
                        mockBeansClient,
                        (vm, p) -> {
                            throw new IOException("JMX error");
                        });
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any()))
                .thenAnswer(
                        invocation -> {
                            SpringBeansClient.JmxConnectionProvider provider =
                                    invocation.getArgument(2);
                            provider.connect();
                            return null;
                        });

        Map<String, Object> result = handler.execute("list_spring_beans", Map.of("pid", 123));
        assertThat(result.get("isError")).isEqualTo(true);

        verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("Actuator discovery avoids JMX and detaches the attached VM")
    void testActuatorDiscoverySkipsJmxConnection() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", mockVm));
        when(mockActuatorProbe.probe(mockVm))
                .thenReturn(
                        Optional.of(
                                new ActuatorProbe.ProbeResult(
                                        "http://localhost:8080/actuator/beans",
                                        """
                {"contexts":{"application":{"beans":{"orderService":{"aliases":[],"scope":"singleton","type":"example.OrderService","dependencies":[]}}}}}
                """)));

        SpringBeansClient client =
                new SpringBeansClient(mockActuatorProbe, new SpringBeansParser());
        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(
                        mockAttachService,
                        client,
                        (vm, p) -> {
                            throw new AssertionError(
                                    "JMX must not be opened when Actuator responds");
                        });

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
        SpringBeanDetail bean =
                new SpringBeanDetail(
                        "orderService",
                        List.of(),
                        "singleton",
                        "com.example.OrderService",
                        null,
                        List.of());
        SpringBeansReport report =
                new SpringBeansReport(
                        123,
                        "JMX",
                        1,
                        List.of(new SpringContextBeans("root", null, 1, List.of(bean))));
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), eq("*Service*")))
                .thenAnswer(
                        invocation -> {
                            SpringBeansClient.JmxConnectionProvider provider =
                                    invocation.getArgument(2);
                            provider.connect().close();
                            return report;
                        });

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(
                        mockAttachService,
                        mockBeansClient,
                        (vm, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result =
                handler.execute("list_spring_beans", Map.of("pid", 123, "filter", "*Service*"));
        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("text").toString()).contains("orderService");
    }

    @Test
    @DisplayName("list_spring_beans returns an error when discovery is unavailable")
    void testListSpringBeansDiscoveryFailure() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));
        SpringBeansReport report =
                new SpringBeansReport(
                        123,
                        "NOT_AVAILABLE (JMX MBean not registered: org.springframework.boot:type=Endpoint,name=Beans)",
                        0,
                        List.of());
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any()))
                .thenReturn(report);

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(mockAttachService, mockBeansClient);

        Map<String, Object> result = handler.execute("list_spring_beans", Map.of("pid", 123));

        assertThat(result.get("isError")).isEqualTo(true);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text").toString())
                .contains("Unable to discover Spring beans")
                .contains("approved management configuration")
                .contains("spring.jmx.enabled=true")
                .contains("MBean not registered");
    }

    @Test
    @DisplayName("list_spring_beans paginates globally in context and bean-name order")
    @SuppressWarnings("unchecked")
    void testPaginationAcrossContexts() throws Exception {
        SpringBeanDetail alpha =
                new SpringBeanDetail(
                        "alpha", List.of(), "singleton", "example.Alpha", null, List.of());
        SpringBeanDetail beta =
                new SpringBeanDetail(
                        "beta", List.of(), "singleton", "example.Beta", null, List.of());
        SpringBeanDetail delta =
                new SpringBeanDetail(
                        "delta", List.of(), "singleton", "example.Delta", null, List.of());
        SpringBeanDetail gamma =
                new SpringBeanDetail(
                        "gamma", List.of(), "singleton", "example.Gamma", null, List.of());

        SpringBeansReport report =
                new SpringBeansReport(
                        123L,
                        "TEST",
                        4,
                        List.of(
                                new SpringContextBeans("b-context", null, 2, List.of(gamma, delta)),
                                new SpringContextBeans(
                                        "a-context", null, 2, List.of(beta, alpha))));

        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any()))
                .thenReturn(report);

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(mockAttachService, mockBeansClient);

        Map<String, Object> result =
                handler.execute("list_spring_beans", Map.of("pid", 123, "limit", 2, "offset", 1));

        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        Map<String, Object> response = SimpleJson.parseObject((String) content.get(0).get("text"));

        assertThat(((Number) response.get("totalBeans")).intValue()).isEqualTo(4);
        assertThat(((Number) response.get("returnedBeans")).intValue()).isEqualTo(2);
        assertThat(response.get("truncated")).isEqualTo(true);

        List<Map<String, Object>> contexts = (List<Map<String, Object>>) response.get("contexts");
        assertThat(contexts).hasSize(2);
        assertThat(contexts.get(0).get("contextId")).isEqualTo("a-context");
        assertThat(contexts.get(1).get("contextId")).isEqualTo("b-context");

        List<Map<String, Object>> firstBeans =
                (List<Map<String, Object>>) contexts.get(0).get("beans");
        List<Map<String, Object>> secondBeans =
                (List<Map<String, Object>>) contexts.get(1).get("beans");

        assertThat(firstBeans).hasSize(1);
        assertThat(firstBeans.get(0).get("name")).isEqualTo("beta");
        assertThat(secondBeans).hasSize(1);
        assertThat(secondBeans.get(0).get("name")).isEqualTo("delta");
    }

    @Test
    @DisplayName("get_bean_detail returns bean detail JSON with owning context and match mode")
    void testGetBeanDetailSuccess() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));
        SpringBeanDetail bean =
                new SpringBeanDetail(
                        "orderService",
                        List.of(),
                        "singleton",
                        "com.example.OrderService",
                        null,
                        List.of());
        SpringBeansReport report =
                new SpringBeansReport(
                        123,
                        "JMX",
                        1,
                        List.of(new SpringContextBeans("root", null, 1, List.of(bean))));
        when(mockBeansClient.inspectBeanDetailWithJmxProvider(
                        eq(123L), any(), any(), eq("orderService")))
                .thenAnswer(
                        invocation -> {
                            SpringBeansClient.JmxConnectionProvider provider =
                                    invocation.getArgument(2);
                            provider.connect().close();
                            return report;
                        });
        when(mockBeansClient.getBeanDetailMatch(report, "orderService"))
                .thenReturn(
                        Optional.of(new SpringBeansClient.BeanDetailMatch("root", bean, "EXACT")));

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(
                        mockAttachService,
                        mockBeansClient,
                        (vm, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result =
                handler.execute("get_bean_detail", Map.of("pid", 123, "beanName", "orderService"));
        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("text").toString())
                .contains("com.example.OrderService", "root", "EXACT", "contextId", "matchedBy");
    }

    @Test
    @DisplayName(
            "get_bean_detail returns descriptive error and isError true when bean is not found")
    void testGetBeanDetailNotFound() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));
        SpringBeansReport report =
                new SpringBeansReport(
                        123, "JMX", 1, List.of(new SpringContextBeans("root", null, 0, List.of())));
        when(mockBeansClient.inspectBeanDetailWithJmxProvider(
                        eq(123L), any(), any(), eq("missingBean")))
                .thenAnswer(
                        invocation -> {
                            SpringBeansClient.JmxConnectionProvider provider =
                                    invocation.getArgument(2);
                            provider.connect().close();
                            return report;
                        });
        when(mockBeansClient.getBeanDetailMatch(report, "missingBean"))
                .thenReturn(Optional.empty());

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(
                        mockAttachService,
                        mockBeansClient,
                        (vm, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result =
                handler.execute("get_bean_detail", Map.of("pid", 123, "beanName", "missingBean"));
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

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(
                        mockAttachService,
                        mockBeansClient,
                        (vm, p) -> JmxConnectionManager.connectLocal());

        assertThatThrownBy(() -> handler.execute("get_bean_detail", Map.of("pid", 123)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required argument: beanName");
        org.mockito.Mockito.verifyNoInteractions(mockAttachService, mockBeansClient);
    }

    @Test
    @DisplayName("execute throws on unknown tool name")
    void testUnknownTool() {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(
                        mockAttachService,
                        mockBeansClient,
                        (vm, p) -> JmxConnectionManager.connectLocal());

        assertThatThrownBy(() -> handler.execute("unknown_beans_tool", Map.of("pid", 123)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown Spring Beans tool");
    }

    @Test
    @DisplayName("detail discovery preserves ambiguity and detaches after HTTP success")
    void testAmbiguousDetailOverHttp() throws Exception {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", mockVm));
        when(mockActuatorProbe.probe(mockVm))
                .thenReturn(
                        Optional.of(
                                new ActuatorProbe.ProbeResult(
                                        "http://localhost/actuator/beans",
                                        """
                        {"contexts": {
                          "a": {"beans": {"shared": "example.First"}},
                          "b": {"beans": {"shared": "example.Second"}}
                        }}
                        """)));
        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(
                        mockAttachService,
                        new SpringBeansClient(mockActuatorProbe, new SpringBeansParser()),
                        (vm, pid) -> {
                            throw new AssertionError("JMX must stay unopened");
                        });
        Map<String, Object> result =
                handler.execute(
                        "get_bean_detail",
                        Map.of("pid", 123, "beanName", "shared", "limit", 1, "offset", 100));
        assertThat(result.get("isError")).isEqualTo(true);
        assertThat(result.get("content").toString()).contains("Ambiguous bean lookup", "[a, b]");
        verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("limit and offset use defaults and are clamped to their allowed range")
    @SuppressWarnings("unchecked")
    void testPaginationDefaultsAndBounds() throws Exception {
        List<SpringBeanDetail> beans =
                List.of("a", "b", "c").stream()
                        .map(
                                name ->
                                        new SpringBeanDetail(
                                                name,
                                                List.of(),
                                                "singleton",
                                                "example.Bean",
                                                null,
                                                List.of()))
                        .toList();
        SpringBeansReport report =
                new SpringBeansReport(
                        123L, "TEST", 3, List.of(new SpringContextBeans("ctx", null, 3, beans)));

        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any()))
                .thenReturn(report);

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(mockAttachService, mockBeansClient);
        java.util.function.Function<Map<String, Object>, Map<String, Object>> list =
                args -> {
                    try {
                        List<Map<String, Object>> content =
                                (List<Map<String, Object>>)
                                        handler.execute("list_spring_beans", args).get("content");
                        return SimpleJson.parseObject((String) content.get(0).get("text"));
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                };

        Map<String, Object> defaults = list.apply(Map.of("pid", 123));
        assertThat(((Number) defaults.get("limit")).intValue()).isEqualTo(200);
        assertThat(((Number) defaults.get("offset")).intValue()).isZero();
        assertThat(((Number) defaults.get("returnedBeans")).intValue()).isEqualTo(3);
        assertThat(defaults.get("truncated")).isEqualTo(false);

        Map<String, Object> overMax = list.apply(Map.of("pid", 123, "limit", 5000));
        assertThat(((Number) overMax.get("limit")).intValue()).isEqualTo(2000);

        Map<String, Object> belowMin = list.apply(Map.of("pid", 123, "limit", 0, "offset", -5));
        assertThat(((Number) belowMin.get("limit")).intValue()).isEqualTo(1);
        assertThat(((Number) belowMin.get("offset")).intValue()).isZero();
        assertThat(((Number) belowMin.get("returnedBeans")).intValue()).isEqualTo(1);
        assertThat(belowMin.get("truncated")).isEqualTo(true);

        Map<String, Object> pastEnd = list.apply(Map.of("pid", 123, "offset", 10));
        assertThat(((Number) pastEnd.get("returnedBeans")).intValue()).isZero();
        assertThat(pastEnd.get("truncated")).isEqualTo(false);
        assertThat((List<?>) pastEnd.get("contexts")).isEmpty();
    }

    @Test
    @DisplayName("non-numeric limit is rejected before attaching")
    void testInvalidLimitRejected() {
        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(mockAttachService, mockBeansClient);

        assertThatThrownBy(
                        () ->
                                handler.execute(
                                        "list_spring_beans", Map.of("pid", 123, "limit", "abc")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    @DisplayName("summary counts all matching beans and ranks packages independently of pagination")
    @SuppressWarnings("unchecked")
    void testSummaryIgnoresPagination() throws Exception {
        SpringBeanDetail orderService =
                new SpringBeanDetail(
                        "orderService",
                        List.of("orders"),
                        "singleton",
                        "com.example.service.OrderService",
                        "OrderService.class",
                        List.of("orderRepository"));
        SpringBeanDetail paymentService =
                new SpringBeanDetail(
                        "paymentService",
                        List.of(),
                        "singleton",
                        "com.example.service.PaymentService",
                        null,
                        List.of());
        SpringBeanDetail orderRepository =
                new SpringBeanDetail(
                        "orderRepository",
                        List.of(),
                        "singleton",
                        "com.example.repository.OrderRepository",
                        null,
                        List.of());
        SpringBeansReport report =
                new SpringBeansReport(
                        123L,
                        "TEST",
                        3,
                        List.of(
                                new SpringContextBeans(
                                        "application",
                                        null,
                                        3,
                                        List.of(orderRepository, orderService, paymentService))));

        when(mockAttachService.attach("123")).thenReturn(AttachResult.success("123", null));
        when(mockBeansClient.inspectBeansWithJmxProvider(eq(123L), any(), any(), any()))
                .thenReturn(report);

        SpringBeansToolHandler handler =
                new SpringBeansToolHandler(mockAttachService, mockBeansClient);
        Map<String, Object> result =
                handler.execute(
                        "list_spring_beans",
                        Map.of("pid", 123, "summary", true, "limit", 1, "offset", 2));

        assertThat(result.get("isError")).isNull();
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        Map<String, Object> response = SimpleJson.parseObject((String) content.get(0).get("text"));

        assertThat(response.get("summary")).isEqualTo(true);
        assertThat(((Number) response.get("totalBeans")).intValue()).isEqualTo(3);
        assertThat(response).doesNotContainKeys("limit", "offset", "returnedBeans", "truncated");

        List<Map<String, Object>> contexts = (List<Map<String, Object>>) response.get("contexts");
        assertThat(contexts).hasSize(1);
        Map<String, Object> context = contexts.get(0);
        assertThat(context.get("contextId")).isEqualTo("application");
        assertThat(context).containsEntry("parentId", null);
        assertThat(((Number) context.get("beanCount")).intValue()).isEqualTo(3);
        assertThat(context).doesNotContainKeys("beans", "dependencies", "aliases", "resource");

        List<Map<String, Object>> topPackages =
                (List<Map<String, Object>>) context.get("topPackages");
        assertThat(topPackages).hasSize(2);
        assertThat(topPackages.get(0).get("package")).isEqualTo("com.example.service");
        assertThat(((Number) topPackages.get(0).get("beanCount")).intValue()).isEqualTo(2);
        assertThat(topPackages.get(1).get("package")).isEqualTo("com.example.repository");
        assertThat(((Number) topPackages.get(1).get("beanCount")).intValue()).isEqualTo(1);
    }
}
