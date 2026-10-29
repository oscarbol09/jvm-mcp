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
    @DisplayName("get_thread_diagnostics executes successfully and returns deadlock report")
    void testGetThreadDiagnostics() throws Exception {
        when(mockAttachService.attach("100")).thenReturn(AttachResult.success("100", null));

        JvmDiagnosticToolHandler handler =
                new JvmDiagnosticToolHandler(
                        mockAttachService, (vm, p) -> JmxConnectionManager.connectLocal());

        Map<String, Object> result = handler.execute("get_thread_diagnostics", Map.of("pid", 100));
        assertThat(result.get("isError")).isNull();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> content = (List<Map<String, Object>>) result.get("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).get("text").toString()).contains("totalCount");
        // Both blocks are plain JSON, so a client can parse each one (#92).
        assertThat(SimpleJson.parseObject(content.get(0).get("text").toString()))
                .containsKey("totalCount");
        Map<String, Object> deadlocks =
                SimpleJson.parseObject(content.get(1).get("text").toString());
        assertThat(deadlocks).containsEntry("status", "NONE");
        assertThat(deadlocks).containsKey("deadlockCount");
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
