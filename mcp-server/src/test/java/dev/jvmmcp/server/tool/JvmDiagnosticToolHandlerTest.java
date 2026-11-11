package dev.jvmmcp.server.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.model.Framework;
import dev.jvmmcp.core.model.JvmProcess;
import dev.jvmmcp.core.util.SimpleJson;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class JvmDiagnosticToolHandlerTest {

    @Mock private JvmAttachService mockAttachService;

    @Mock private VirtualMachine mockVm;

    @Test
    @DisplayName("get_thread_dump exposes virtual-thread details as opt-in default false")
    void testThreadDumpSchemaIncludesVirtualThreadOption() {
        Map<String, Object> definition =
                new JvmDiagnosticToolHandler()
                        .toolDefinitions().stream()
                                .filter(tool -> "get_thread_dump".equals(tool.get("name")))
                                .findFirst()
                                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) definition.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKey("includeVirtualThreads");
        Map<?, ?> virtualOption = (Map<?, ?>) properties.get("includeVirtualThreads");
        assertThat(virtualOption.get("type")).isEqualTo("boolean");
        assertThat(virtualOption.get("default")).isEqualTo(false);
    }

    @Test
    @DisplayName("get_thread_dump default uses legacy call without version lookup")
    void testGetThreadDumpSkipsVersionLookupByDefault() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", mockVm));
        VirtualMachine vm = mockVm;

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (v, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result = handler.execute("get_thread_dump", Map.of("pid", 100));
        assertThat(result.get("isError")).isNull();
        verify(vm, org.mockito.Mockito.never()).getSystemProperties();
    }

    @Test
    @DisplayName("get_thread_dump rejects invalid boolean includeVirtualThreads before attach")
    void testGetThreadDumpInvalidBooleanRejected() {
        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (v, p) -> JmxConnectionManager.connectLocal());

        assertThatThrownBy(
                        () ->
                                handler.execute(
                                        "get_thread_dump",
                                        Map.of("pid", 100, "includeVirtualThreads", "notabool")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mockAttachService, org.mockito.Mockito.never())
                .attach(org.mockito.Mockito.anyString());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"17", "21"})
    void testThreadDumpOptInForwardsTargetVersion(String version) throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", mockVm));
        java.util.Properties props = new java.util.Properties();
        props.setProperty("java.specification.version", version);
        when(mockVm.getSystemProperties()).thenReturn(props);

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        try (var clients =
                org.mockito.Mockito.mockConstruction(
                        dev.jvmmcp.core.jmx.ThreadMXBeanClient.class,
                        (mock, context) ->
                                when(mock.getThreadDump(100L, false, false, true, version))
                                        .thenReturn(
                                                new dev.jvmmcp.core.model.ThreadDump(
                                                        100L,
                                                        java.time.Instant.EPOCH,
                                                        0,
                                                        List.of())))) {
            Map<String, Object> result =
                    handler.execute(
                            "get_thread_dump", Map.of("pid", 100, "includeVirtualThreads", true));

            assertThat(result.get("isError")).isNull();
            assertThat(clients.constructed()).hasSize(1);
            verify(clients.constructed().getFirst())
                    .getThreadDump(100L, false, false, true, version);
            verify(mockVm).getSystemProperties();
            verify(clients.constructed().getFirst(), org.mockito.Mockito.never())
                    .getThreadDump(100L, false, false);
        }
    }

    @Test
    @DisplayName("constructors initialize properly")
    void testConstructors() {
        JvmDiagnosticToolHandler h1 = new JvmDiagnosticToolHandler();
        assertThat(h1.toolNames()).isNotEmpty();

        JvmDiagnosticToolHandler h2 = new JvmDiagnosticToolHandler(mockAttachService);
        assertThat(h2.toolNames()).isNotEmpty();
    }

    @Test
    @DisplayName("get_thread_diagnostics exposes virtual-thread inspection as opt-in")
    void testThreadDiagnosticsSchemaIncludesVirtualThreadOption() {
        Map<String, Object> definition =
                new JvmDiagnosticToolHandler()
                        .toolDefinitions().stream()
                                .filter(tool -> "get_thread_diagnostics".equals(tool.get("name")))
                                .findFirst()
                                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) definition.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");

        assertThat(properties).containsKey("includeVirtualThreads");
        assertThat(properties.get("includeVirtualThreads")).isInstanceOf(Map.class);
        Map<?, ?> virtualOption = (Map<?, ?>) properties.get("includeVirtualThreads");
        assertThat(virtualOption.get("default")).isEqualTo(false);
    }

    @Test
    @DisplayName("toolNames and toolDefinitions return all 6 JVM diagnostic tools")
    void testToolNamesAndDefinitions() {
        JvmDiagnosticToolHandler handler = new JvmDiagnosticToolHandler(mockAttachService);

        assertThat(handler.toolNames())
                .containsExactlyInAnyOrder(
                        "list_jvms",
                        "get_memory_summary",
                        "get_thread_diagnostics",
                        "get_heap_histogram",
                        "get_thread_dump",
                        "list_hikari_pools");
        assertThat(handler.toolDefinitions()).hasSize(6);
    }

    @Test
    @DisplayName("list_jvms executes through attachService")
    void testListJvms() throws Exception {
        when(mockAttachService.listJvms())
                .thenReturn(
                        List.of(
                                new JvmProcess(
                                        101L,
                                        "dev.jvmmcp.Main",
                                        "dev.jvmmcp.Main",
                                        "21",
                                        Framework.PLAIN_JAVA,
                                        true)));

        JvmDiagnosticToolHandler handler = new JvmDiagnosticToolHandler(mockAttachService);
        Map<String, Object> result = handler.execute("list_jvms", Map.of());

        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);
        assertThat(content.get(0).get("text").toString()).contains("101");
    }

    @Test
    @DisplayName("execute throws IllegalStateException when attach fails")
    void testAttachFailure() {
        when(mockAttachService.attach("999")).thenReturn(AttachResult.processNotFound("999"));

        JvmDiagnosticToolHandler handler = new JvmDiagnosticToolHandler(mockAttachService);
        assertThatThrownBy(() -> handler.execute("get_memory_summary", Map.of("pid", 999)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PID 999 was not found");
    }

    @Test
    @DisplayName("execute throws IllegalArgumentException on missing required pid")
    void testMissingPid() {
        JvmDiagnosticToolHandler handler = new JvmDiagnosticToolHandler(mockAttachService);
        assertThatThrownBy(() -> handler.execute("get_memory_summary", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Missing required argument: pid");
    }

    @Test
    @DisplayName("detaches on JMX connection error")
    void testDetachOnJmxConnectionError() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", mockVm));

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService,
                        (vm, p) -> {
                            throw new IOException("JMX failed");
                        });

        assertThatThrownBy(() -> handler.execute("get_memory_summary", Map.of("pid", 100)))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("JMX failed");

        verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("detaches and ignores detachment exception during cleanup")
    void testDetachThrowsExceptionIgnored() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", mockVm));
        doThrow(new RuntimeException("Detach failed")).when(mockAttachService).detach(mockVm);

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService,
                        (vm, p) -> {
                            throw new IOException("JMX failed");
                        });

        assertThatThrownBy(() -> handler.execute("get_memory_summary", Map.of("pid", 100)))
                .isInstanceOf(IOException.class);

        verify(mockAttachService).detach(mockVm);
    }

    @Test
    @DisplayName("get_memory_summary executes successfully using local JMX")
    void testGetMemorySummary() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", null));

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result = handler.execute("get_memory_summary", Map.of("pid", 100));
        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text").toString())
                .contains("heapUsage")
                .contains("classLoading")
                .contains("loadedClassCount")
                .contains("bufferPools")
                .contains("direct")
                .contains("mapped");
    }

    @Test
    @DisplayName("get_thread_diagnostics returns summary, deadlocks, and top CPU threads")
    void testGetThreadDiagnostics() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", null));

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result =
                handler.execute("get_thread_diagnostics", Map.of("pid", 100, "topCpuLimit", 1));
        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(3);
        assertThat(content.get(0).get("text").toString()).contains("totalCount");
        // Both blocks are plain JSON, so a client can parse each one (#92).
        assertThat(SimpleJson.parseObject(content.get(0).get("text").toString()))
                .containsKey("totalCount");
        Map<String, Object> deadlocks =
                SimpleJson.parseObject(content.get(1).get("text").toString());
        assertThat(deadlocks).containsEntry("status", "NONE");
        assertThat(deadlocks).containsKey("deadlockCount");
        Object topCpuThreads = SimpleJson.parse(content.get(2).get("text").toString());
        assertThat(topCpuThreads).isInstanceOf(List.class);
        assertThat((List<?>) topCpuThreads).hasSizeLessThanOrEqualTo(1);
        if (!((List<?>) topCpuThreads).isEmpty()) {
            @SuppressWarnings("unchecked")
            Map<String, Object> topThread = (Map<String, Object>) ((List<?>) topCpuThreads).get(0);
            assertThat(topThread).containsKeys("cpuTimeNs", "userTimeNs", "threadId", "threadName");
        }
    }

    @Test
    @DisplayName("get_thread_dump executes successfully")
    void testGetThreadDump() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", null));

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result = handler.execute("get_thread_dump", Map.of("pid", 100));
        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content.get(0).get("text").toString()).contains("threads");
    }

    @Test
    @DisplayName("list_hikari_pools returns a note when no HikariCP MBeans are registered")
    void testListHikariPools() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", null));

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result = handler.execute("list_hikari_pools", Map.of("pid", 100));
        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(1);

        String json = content.get(0).get("text").toString();
        Map<String, Object> payload = SimpleJson.parseObject(json);
        assertThat(payload.get("pools")).isEqualTo(List.of());
        assertThat(payload.get("note"))
                .asString()
                .contains("spring.datasource.hikari.register-mbeans=true")
                .contains("hikaricp.connections");
    }

    @Test
    @DisplayName("get_heap_histogram documents the live-only full-GC default")
    void testHeapHistogramSchemaDocumentsLiveOnlyDefault() {
        Map<String, Object> definition =
                new JvmDiagnosticToolHandler()
                        .toolDefinitions().stream()
                                .filter(tool -> "get_heap_histogram".equals(tool.get("name")))
                                .findFirst()
                                .orElseThrow();

        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) definition.get("inputSchema");
        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<?, ?> liveOnly = (Map<?, ?>) properties.get("liveOnly");

        assertThat(liveOnly.get("type")).isEqualTo("boolean");
        assertThat(liveOnly.get("default")).isEqualTo(true);
        assertThat(definition.get("description").toString())
                .contains("full stop-the-world GC")
                .contains("unreachable objects");
    }

    @Test
    @DisplayName("get_heap_histogram can include unreachable objects without a full GC")
    void testGetHeapHistogramAllObjectsMode() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", null));

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result =
                handler.execute("get_heap_histogram", Map.of("pid", 100, "liveOnly", false));
        assertThat(result.get("isError")).isNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        Map<String, Object> histogram =
                SimpleJson.parseObject(content.getFirst().get("text").toString());
        assertThat(histogram).containsEntry("liveOnly", false);
    }

    @Test
    @DisplayName("get_heap_histogram rejects a non-boolean liveOnly argument before attaching")
    void testHeapHistogramRejectsInvalidLiveOnlyArgument() {
        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        assertThatThrownBy(
                        () ->
                                handler.execute(
                                        "get_heap_histogram",
                                        Map.of("pid", 100, "liveOnly", "sometimes")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("liveOnly")
                .hasMessageContaining("boolean");
        verify(mockAttachService, org.mockito.Mockito.never()).attach(org.mockito.Mockito.anyString());
    }

    @Test
    @DisplayName("get_heap_histogram executes with default and bounded limits")
    void testGetHeapHistogramLimits() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", null));

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        // limit <= 0: boundary at 0 and -5
        Map<String, Object> resultNegative =
                handler.execute("get_heap_histogram", Map.of("pid", 100, "limit", -5));
        assertThat(resultNegative.get("isError")).isNull();
        Map<String, Object> resultZero =
                handler.execute("get_heap_histogram", Map.of("pid", 100, "limit", 0));
        assertThat(resultZero.get("isError")).isNull();

        // boundary at 1
        Map<String, Object> resultOne =
                handler.execute("get_heap_histogram", Map.of("pid", 100, "limit", 1));
        assertThat(resultOne.get("isError")).isNull();

        // boundary at 1000
        Map<String, Object> result1000 =
                handler.execute("get_heap_histogram", Map.of("pid", 100, "limit", 1000));
        assertThat(result1000.get("isError")).isNull();

        // boundary at 1001 (> 1000)
        Map<String, Object> result1001 =
                handler.execute("get_heap_histogram", Map.of("pid", 100, "limit", 1001));
        assertThat(result1001.get("isError")).isNull();

        // missing limit
        Map<String, Object> resultMissing =
                handler.execute("get_heap_histogram", Map.of("pid", 100));
        assertThat(resultMissing.get("isError")).isNull();
    }

    @Test
    @DisplayName("execute throws on unknown tool name")
    void testUnknownTool() {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", null));
        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        assertThatThrownBy(() -> handler.execute("invalid_jvm_tool", Map.of("pid", 100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown JVM diagnostic tool");
    }

    @Test
    @DisplayName("uses default PID when pid is omitted")
    void testDefaultPidWhenPidIsMissing() {
        when(mockAttachService.attach("123")).thenReturn(AttachResult.processNotFound("123"));

        JvmDiagnosticToolHandler handler =
                JvmDiagnosticToolHandler.withDefaultPid(mockAttachService, 123);

        assertThatThrownBy(() -> handler.execute("get_memory_summary", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PID 123 was not found");

        verify(mockAttachService).attach("123");
    }

    @Test
    @DisplayName("explicit PID overrides default PID")
    void testExplicitPidOverridesDefault() {
        when(mockAttachService.attach("456")).thenReturn(AttachResult.processNotFound("456"));

        JvmDiagnosticToolHandler handler =
                JvmDiagnosticToolHandler.withDefaultPid(mockAttachService, 123);

        assertThatThrownBy(() -> handler.execute("get_memory_summary", Map.of("pid", 456)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PID 456 was not found");

        verify(mockAttachService).attach("456");
    }
}
