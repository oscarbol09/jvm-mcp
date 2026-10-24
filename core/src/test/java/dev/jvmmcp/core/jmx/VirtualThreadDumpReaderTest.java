package dev.jvmmcp.core.jmx;

import dev.jvmmcp.core.model.ThreadDetail;
import dev.jvmmcp.core.model.ThreadStackFrame;
import dev.jvmmcp.core.model.VirtualThreadSummary;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("VirtualThreadDumpReader Unit Tests")
class VirtualThreadDumpReaderTest {

    @Test
    @DisplayName("Constructor throws IllegalArgumentException when MBeanServerConnection is null")
    void shouldThrowWhenMbscIsNull() {
        assertThatThrownBy(() -> new VirtualThreadDumpReader(null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("MBeanServerConnection cannot be null");
    }

    @Test
    @DisplayName("parseVirtualThreadSummary correctly counts distinct virtual threads across multiple containers")
    void shouldParseVirtualThreadSummaryAcrossContainers() {
        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mock(MBeanServerConnection.class));

        Map<String, Object> dumpJson = Map.of(
            "threadDump", Map.of(
                "processId", "1234",
                "threadContainers", List.of(
                    Map.of(
                        "container", "<root>",
                        "threads", List.of(
                            Map.of("tid", "1", "name", "main", "stack", List.of("pkg.Main.main(Main.java:10)")),
                            Map.of("tid", "20", "name", "vthread-1", "stack", List.of("java.base/java.lang.VirtualThread.run(VirtualThread.java:329)"))
                        )
                    ),
                    Map.of(
                        "container", "java.util.concurrent.ThreadPerTaskExecutor@123",
                        "threads", List.of(
                            Map.of("tid", "21", "name", "vthread-2", "stack", List.of("java.base/java.lang.VirtualThread.run(VirtualThread.java:329)")),
                            // Duplicate tid 21 in another container should not be counted twice
                            Map.of("tid", "21", "name", "vthread-2-dup", "stack", List.of("java.base/java.lang.VirtualThread.run(VirtualThread.java:329)"))
                        )
                    ),
                    Map.of(
                        "container", "ForkJoinPool-1/jdk.internal.vm.SharedThreadContainer@456",
                        "threads", List.of(
                            Map.of("tid", "2", "name", "ForkJoinPool-1-worker-1", "stack", List.of("java.base/java.util.concurrent.ForkJoinPool.runWorker(ForkJoinPool.java:1809)"))
                        )
                    )
                )
            )
        );

        // Platform thread IDs: 1 (main), 2 (carrier worker)
        Set<Long> platformIds = Set.of(1L, 2L);

        VirtualThreadSummary summary = reader.parseVirtualThreadSummary(dumpJson, platformIds);
        assertThat(summary).isNotNull();
        assertThat(summary.totalCount()).isEqualTo(2); // vthread-1 (20) and vthread-2 (21)
    }

    @Test
    @DisplayName("parseVirtualThreads returns structured ThreadDetail list with VIRTUAL state")
    void shouldParseVirtualThreadsList() {
        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mock(MBeanServerConnection.class));

        Map<String, Object> dumpJson = Map.of(
            "threadDump", Map.of(
                "threadContainers", List.of(
                    Map.of(
                        "container", "<root>",
                        "threads", List.of(
                            Map.of("tid", "1", "name", "main", "stack", List.of()),
                            Map.of("tid", "101", "name", "order-processor", "stack", List.of(
                                "com.example.OrderService.process(OrderService.java:42)",
                                "java.base/java.lang.VirtualThread.run(VirtualThread.java:329)"
                            ))
                        )
                    )
                )
            )
        );

        Set<Long> platformIds = Set.of(1L);
        List<ThreadDetail> virtualThreads = reader.parseVirtualThreads(dumpJson, platformIds);

        assertThat(virtualThreads).hasSize(1);
        ThreadDetail detail = virtualThreads.get(0);
        assertThat(detail.threadId()).isEqualTo(101L);
        assertThat(detail.threadName()).isEqualTo("order-processor");
        assertThat(detail.state()).isEqualTo("VIRTUAL");
        assertThat(detail.stackTrace()).hasSize(2);
        assertThat(detail.stackTrace().get(0).className()).isEqualTo("com.example.OrderService");
        assertThat(detail.stackTrace().get(0).methodName()).isEqualTo("process");
        assertThat(detail.stackTrace().get(0).fileName()).isEqualTo("OrderService.java");
        assertThat(detail.stackTrace().get(0).lineNumber()).isEqualTo(42);
        assertThat(detail.stackTrace().get(1).className()).isEqualTo("java.lang.VirtualThread");
    }

    @Test
    @DisplayName("parseVirtualThreadSummary identifies virtual threads by stack trace fallback when platform IDs are empty")
    void shouldFallbackToStackInspectionWhenPlatformIdsAreEmpty() {
        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mock(MBeanServerConnection.class));

        Map<String, Object> dumpJson = Map.of(
            "threadDump", Map.of(
                "threadContainers", List.of(
                    Map.of(
                        "container", "<root>",
                        "threads", List.of(
                            Map.of("tid", "1", "name", "main", "stack", List.of("pkg.Main.main(Main.java:10)")),
                            Map.of("tid", "99", "name", "vt-fallback", "stack", List.of(
                                "pkg.Worker.work(Worker.java:20)",
                                "java.base/java.lang.VirtualThread.run(VirtualThread.java:329)"
                            ))
                        )
                    )
                )
            )
        );

        VirtualThreadSummary summary = reader.parseVirtualThreadSummary(dumpJson, Set.of());
        assertThat(summary).isNotNull();
        assertThat(summary.totalCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("parseVirtualThreadSummary returns 0 when no virtual threads exist")
    void shouldReturnZeroWhenNoVirtualThreadsExist() {
        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mock(MBeanServerConnection.class));

        Map<String, Object> dumpJson = Map.of(
            "threadDump", Map.of(
                "threadContainers", List.of(
                    Map.of(
                        "container", "<root>",
                        "threads", List.of(
                            Map.of("tid", "1", "name", "main", "stack", List.of("pkg.Main.main(Main.java:10)")),
                            Map.of("tid", "2", "name", "Reference Handler", "stack", List.of())
                        )
                    )
                )
            )
        );

        VirtualThreadSummary summary = reader.parseVirtualThreadSummary(dumpJson, Set.of(1L, 2L));
        assertThat(summary).isNotNull();
        assertThat(summary.totalCount()).isZero();
    }

    @Test
    @DisplayName("parseVirtualThreadSummary returns null for missing or incorrectly typed threadContainers")
    void shouldReturnNullWhenThreadContainersMissingOrIncorrectType() {
        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mock(MBeanServerConnection.class));

        // Missing threadContainers field
        assertThat(reader.parseVirtualThreadSummary(Map.of("threadDump", Map.of()), Set.of())).isNull();

        // Incorrectly typed threadContainers (string, number, map)
        assertThat(reader.parseVirtualThreadSummary(Map.of("threadDump", Map.of("threadContainers", "not-a-list")), Set.of())).isNull();
        assertThat(reader.parseVirtualThreadSummary(Map.of("threadDump", Map.of("threadContainers", 12345)), Set.of())).isNull();
        assertThat(reader.parseVirtualThreadSummary(Map.of("threadDump", Map.of("threadContainers", Map.of())), Set.of())).isNull();
    }

    @Test
    @DisplayName("parseVirtualThreadSummary returns genuine zero count for valid empty threadContainers list")
    void shouldReturnZeroCountForValidEmptyThreadContainers() {
        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mock(MBeanServerConnection.class));

        Map<String, Object> dumpJson = Map.of(
            "threadDump", Map.of("threadContainers", List.of())
        );

        VirtualThreadSummary summary = reader.parseVirtualThreadSummary(dumpJson, Set.of());
        assertThat(summary).isNotNull();
        assertThat(summary.totalCount()).isZero();
    }

    @Test
    @DisplayName("parseVirtualThreadSummary handles malformed, null, or invalid JSON structures gracefully")
    void shouldHandleMalformedJsonGracefully() {
        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mock(MBeanServerConnection.class));

        assertThat(reader.parseVirtualThreadSummary(null, Set.of())).isNull();
        assertThat(reader.parseVirtualThreadSummary(Map.of(), Set.of())).isNull();
        assertThat(reader.parseVirtualThreadSummary(Map.of("threadDump", "invalid-type"), Set.of())).isNull();

        assertThat(reader.parseVirtualThreads(null, Set.of())).isNull();
        assertThat(reader.parseVirtualThreads(Map.of(), Set.of())).isNull();
        assertThat(reader.parseVirtualThreads(Map.of("threadDump", "invalid-type"), Set.of())).isNull();

        Map<String, Object> invalidContainers = Map.of(
            "threadDump", Map.of(
                "threadContainers", List.of(
                    "not-a-map",
                    Map.of("threads", "not-a-list"),
                    Map.of("threads", List.of(
                        "not-a-thread-map",
                        Map.of("tid", "invalid-number"),
                        Map.of("no-tid", "value")
                    ))
                )
            )
        );
        assertThat(reader.parseVirtualThreadSummary(invalidContainers, Set.of()).totalCount()).isZero();
        assertThat(reader.parseVirtualThreads(invalidContainers, Set.of())).isEmpty();
    }

    @Test
    @DisplayName("readDumpJson cleans up temporary file on success")
    void shouldCleanUpTempFileOnSuccess() throws Exception {
        MBeanServerConnection mbsc = mock(MBeanServerConnection.class);

        // When threadDumpToFile is invoked, write dummy JSON to the output file
        when(mbsc.invoke(any(ObjectName.class), eq("threadDumpToFile"), any(), any()))
            .thenAnswer(invocation -> {
                Object[] args = (Object[]) invocation.getArgument(2);
                String[] params = (String[]) args[0];
                String path = params[2];
                Files.writeString(Path.of(path), "{\"threadDump\":{\"threadContainers\":[]}}");
                return "Created " + path;
            });

        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mbsc);
        Optional<Map<String, Object>> result = reader.readDumpJson();

        assertThat(result).isPresent();
        assertThat(result.get()).containsKey("threadDump");
    }

    @Test
    @DisplayName("readDumpJson cleans up temporary file and returns empty on MBean failure")
    void shouldCleanUpTempFileOnFailure() throws Exception {
        MBeanServerConnection mbsc = mock(MBeanServerConnection.class);
        when(mbsc.invoke(any(ObjectName.class), eq("threadDumpToFile"), any(), any()))
            .thenThrow(new MBeanException(new RuntimeException("jcmd error")));

        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mbsc);
        Optional<Map<String, Object>> result = reader.readDumpJson();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("readVirtualThreadSummary and readVirtualThreads return empty/emptyList when JMX invocation fails")
    void shouldReturnEmptyWhenJmxFails() throws Exception {
        MBeanServerConnection mbsc = mock(MBeanServerConnection.class);
        when(mbsc.invoke(any(ObjectName.class), any(), any(), any()))
            .thenThrow(new IOException("Connection reset"));

        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mbsc);

        Optional<VirtualThreadSummary> summary = reader.readVirtualThreadSummary(new long[]{1L});
        Optional<List<ThreadDetail>> threads = reader.readVirtualThreads(new long[]{1L});

        assertThat(summary).isEmpty();
        assertThat(threads).isEmpty();
    }

    @Test
    @DisplayName("readVirtualThreads returns empty when dump contains missing threadContainers, and empty list when valid empty list")
    void shouldDifferentiateMissingContainersFromEmptyContainersInReadThreads() throws Exception {
        MBeanServerConnection mbsc = mock(MBeanServerConnection.class);

        // First invocation writes JSON without threadContainers (malformed)
        when(mbsc.invoke(any(ObjectName.class), eq("threadDumpToFile"), any(), any()))
            .thenAnswer(invocation -> {
                Object[] args = (Object[]) invocation.getArgument(2);
                String[] params = (String[]) args[0];
                String path = params[2];
                Files.writeString(Path.of(path), "{\"threadDump\":{}}");
                return "Created " + path;
            })
            // Second invocation writes JSON with valid empty threadContainers
            .thenAnswer(invocation -> {
                Object[] args = (Object[]) invocation.getArgument(2);
                String[] params = (String[]) args[0];
                String path = params[2];
                Files.writeString(Path.of(path), "{\"threadDump\":{\"threadContainers\":[]}}");
                return "Created " + path;
            });

        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mbsc);

        // Missing threadContainers -> malformed -> Optional.empty()
        Optional<List<ThreadDetail>> missingResult = reader.readVirtualThreads(new long[]{1L});
        assertThat(missingResult).isEmpty();

        // Valid empty threadContainers -> genuine zero threads -> Optional.of(List.of())
        Optional<List<ThreadDetail>> emptyResult = reader.readVirtualThreads(new long[]{1L});
        assertThat(emptyResult).isPresent();
        assertThat(emptyResult.get()).isEmpty();
    }

    @Test
    @DisplayName("readVirtualThreadSummary returns empty when dump contains missing threadContainers, and zero when valid empty list")
    void shouldDifferentiateMissingContainersFromEmptyContainersInReadSummary() throws Exception {
        MBeanServerConnection mbsc = mock(MBeanServerConnection.class);

        // First invocation writes JSON without threadContainers (malformed)
        when(mbsc.invoke(any(ObjectName.class), eq("threadDumpToFile"), any(), any()))
            .thenAnswer(invocation -> {
                Object[] args = (Object[]) invocation.getArgument(2);
                String[] params = (String[]) args[0];
                String path = params[2];
                Files.writeString(Path.of(path), "{\"threadDump\":{}}");
                return "Created " + path;
            })
            // Second invocation writes JSON with valid empty threadContainers
            .thenAnswer(invocation -> {
                Object[] args = (Object[]) invocation.getArgument(2);
                String[] params = (String[]) args[0];
                String path = params[2];
                Files.writeString(Path.of(path), "{\"threadDump\":{\"threadContainers\":[]}}");
                return "Created " + path;
            });

        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mbsc);

        // Missing threadContainers -> malformed -> Optional.empty()
        Optional<VirtualThreadSummary> missingResult = reader.readVirtualThreadSummary(new long[]{1L});
        assertThat(missingResult).isEmpty();

        // Valid empty threadContainers -> genuine zero count -> Optional.of(VirtualThreadSummary(0))
        Optional<VirtualThreadSummary> emptyResult = reader.readVirtualThreadSummary(new long[]{1L});
        assertThat(emptyResult).isPresent();
        assertThat(emptyResult.get().totalCount()).isZero();
    }

    @Test
    @DisplayName("readVirtualThreadSummary returns empty when dump contains incorrectly typed threadContainers")
    void shouldReturnEmptyWhenThreadContainersIsIncorrectlyTypedInReadSummary() throws Exception {
        MBeanServerConnection mbsc = mock(MBeanServerConnection.class);
        Path[] writtenPath = new Path[1];

        when(mbsc.invoke(any(ObjectName.class), eq("threadDumpToFile"), any(), any()))
            .thenAnswer(invocation -> {
                Object[] args = (Object[]) invocation.getArgument(2);
                String[] params = (String[]) args[0];
                String path = params[2];
                writtenPath[0] = Path.of(path);
                Files.writeString(writtenPath[0], "{\"threadDump\":{\"threadContainers\":\"invalid-string-not-a-list\"}}");
                return "Created " + path;
            });

        VirtualThreadDumpReader reader = new VirtualThreadDumpReader(mbsc);
        Optional<VirtualThreadSummary> result = reader.readVirtualThreadSummary(new long[]{1L});
        assertThat(result).isEmpty();
        assertThat(writtenPath[0]).isNotNull().doesNotExist();
    }

    @Test
    @DisplayName("ThreadStackFrame.parse parses standard, native, unknown source, and module frames")
    void shouldParseVariousStackFrameStrings() {
        ThreadStackFrame frame1 = ThreadStackFrame.parse("java.base/java.lang.Thread.getStackTrace(Thread.java:2451)");
        assertThat(frame1.className()).isEqualTo("java.lang.Thread");
        assertThat(frame1.methodName()).isEqualTo("getStackTrace");
        assertThat(frame1.fileName()).isEqualTo("Thread.java");
        assertThat(frame1.lineNumber()).isEqualTo(2451);
        assertThat(frame1.isNativeMethod()).isFalse();

        ThreadStackFrame frameNative = ThreadStackFrame.parse("java.base/java.lang.Object.wait0(Native Method)");
        assertThat(frameNative.className()).isEqualTo("java.lang.Object");
        assertThat(frameNative.methodName()).isEqualTo("wait0");
        assertThat(frameNative.isNativeMethod()).isTrue();
        assertThat(frameNative.lineNumber()).isEqualTo(-2);

        ThreadStackFrame frameUnknown = ThreadStackFrame.parse("com.example.App.main(Unknown Source)");
        assertThat(frameUnknown.className()).isEqualTo("com.example.App");
        assertThat(frameUnknown.methodName()).isEqualTo("main");
        assertThat(frameUnknown.fileName()).isNull();
        assertThat(frameUnknown.lineNumber()).isEqualTo(-1);

        ThreadStackFrame frameNull = ThreadStackFrame.parse(null);
        assertThat(frameNull.className()).isEqualTo("Unknown");
        assertThat(frameNull.methodName()).isEqualTo("unknown");

        ThreadStackFrame frameBlank = ThreadStackFrame.parse("   ");
        assertThat(frameBlank.className()).isEqualTo("Unknown");

        ThreadStackFrame frameNoParen = ThreadStackFrame.parse("MalformedStringWithoutParentheses");
        assertThat(frameNoParen.className()).isEqualTo("Unknown");
        assertThat(frameNoParen.methodName()).isEqualTo("MalformedStringWithoutParentheses");
    }
}
