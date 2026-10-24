package dev.jvmmcp;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.sun.tools.attach.spi.AttachProvider;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.MemoryMXBeanClient;
import dev.jvmmcp.core.model.BufferPoolInfo;
import dev.jvmmcp.core.model.ClassLoadingInfo;
import dev.jvmmcp.core.model.HeapSummary;
import dev.jvmmcp.core.model.MemoryPoolInfo;
import dev.jvmmcp.core.model.MemoryPressure;
import dev.jvmmcp.core.model.MemoryPressureLevel;
import dev.jvmmcp.core.model.MemoryUsageInfo;
import dev.jvmmcp.core.util.SimpleJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemoryCommandTest {

    @Mock
    private JvmAttachService mockAttachService;

    public static class DummyAttachProvider extends AttachProvider {
        @Override public String name() { return "dummy"; }
        @Override public String type() { return "dummy"; }
        @Override public VirtualMachine attachVirtualMachine(String id) { return null; }
        @Override public List<VirtualMachineDescriptor> listVirtualMachines() { return List.of(); }
    }

    public static class StubVirtualMachine extends VirtualMachine {
        private final InputStream stream;
        private final boolean shouldThrow;

        StubVirtualMachine(InputStream stream) {
            super(new DummyAttachProvider(), "100");
            this.stream = stream;
            this.shouldThrow = false;
        }

        StubVirtualMachine(boolean shouldThrow) {
            super(new DummyAttachProvider(), "100");
            this.stream = null;
            this.shouldThrow = shouldThrow;
        }

        public InputStream executeJCmd(String command) throws Exception {
            if (shouldThrow) {
                throw new RuntimeException("jcmd error");
            }
            return stream;
        }

        @Override
        public void detach() {}

        @Override
        public void loadAgent(String agent, String options) {}

        @Override
        public void loadAgentLibrary(String agentLibrary, String options) {}

        @Override
        public void loadAgentPath(String agentPath, String options) {}

        @Override
        public Properties getSystemProperties() {
            return new Properties();
        }

        @Override
        public Properties getAgentProperties() {
            return new Properties();
        }

        @Override
        public String startLocalManagementAgent() {
            return null;
        }

        @Override
        public void startManagementAgent(Properties agentProperties) {}
    }

    @Test
    @DisplayName("memory command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("memory", "--help");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("memory command with invalid or missing PID should return error exit code")
    void shouldFailOnMissingOrInvalidPid() {
        CommandLine cmd = new CommandLine(new JvmMcp());

        int noPidExit = cmd.execute("memory");
        int negativePidExit = cmd.execute("memory", "-1");
        int zeroPidExit = cmd.execute("memory", "0");

        assertThat(noPidExit).isNotZero();
        assertThat(negativePidExit).isEqualTo(1);
        assertThat(zeroPidExit).isEqualTo(1);
    }

    @Test
    @DisplayName("memory command with non-existent PID should return error exit code")
    void shouldFailGracefullyOnNonExistentPid() {
        when(mockAttachService.attach("999999999")).thenReturn(AttachResult.processNotFound("999999999"));

        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = 999999999L;

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Process with PID 999999999 was not found");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("memory command on successfully attached PID should report memory metrics")
    void shouldReportMemoryMetrics() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("=".repeat(80));
            assertThat(output).contains(" JVM MEMORY DIAGNOSTICS FOR PID " + targetPid);
            assertThat(output).contains("Heap Usage");
            assertThat(output).contains("Non-Heap Usage");
            assertThat(output).contains("Pressure Status");
            assertThat(output).contains("Recommendation");
            assertThat(output).contains("-".repeat(80));
            assertThat(output).contains("MEMORY POOLS");
            assertThat(output).contains("POOL NAME");
            assertThat(output).contains("USED (MB)");
        } finally {
            System.setOut(originalOut);
        }
    }

    @ParameterizedTest(name = "unbounded heap: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("memory command should label unbounded quotas instead of printing a committed max")
    void shouldLabelUnboundedPoolsWithoutFakeCommittedQuota(boolean unboundedHeap) throws Exception {
        long targetPid = 100L;
        long mb = 1024L * 1024L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        MemoryUsageInfo heap = MemoryUsageInfo.from(new MemoryUsage(0, 512 * mb, 1024 * mb, unboundedHeap ? -1 : 2048 * mb));
        MemoryUsageInfo nonHeap = MemoryUsageInfo.from(new MemoryUsage(0, 64 * mb, 256 * mb, -1));
        MemoryUsageInfo eden = MemoryUsageInfo.from(new MemoryUsage(0, 256 * mb, 512 * mb, 2048 * mb));
        MemoryUsageInfo metaspace = MemoryUsageInfo.from(new MemoryUsage(0, 48 * mb, 128 * mb, -1));
        MemoryUsageInfo zeroBoundPool = MemoryUsageInfo.from(new MemoryUsage(0, 0, 0, 0));
        HeapSummary summary = new HeapSummary(
            targetPid,
            heap,
            nonHeap,
            List.of(
                new MemoryPoolInfo("G1 Eden Space", "HEAP", eden, eden),
                new MemoryPoolInfo("Metaspace", "NON_HEAP", metaspace, metaspace),
                new MemoryPoolInfo("Code Cache", "NON_HEAP", zeroBoundPool, zeroBoundPool)
            ),
            List.of(),
            new MemoryPressure(MemoryPressureLevel.NORMAL, 0.25, 0L, 0L, "Heap looks healthy")
        );

        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
	Locale originalFormatLocale = Locale.getDefault(Locale.Category.FORMAT);
        try (var clients = mockConstruction(MemoryMXBeanClient.class,
                (client, context) -> when(client.getHeapSummary(anyLong())).thenReturn(summary))) {
	    Locale.setDefault(Locale.Category.FORMAT, Locale.forLanguageTag("pt-BR"));
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(clients.constructed()).hasSize(1);
            String output = out.toString();

            // Unbounded non-heap memory: explicit unknown label, never the committed value used as a quota.
            String nonHeapLine = lineContaining(output, "Non-Heap Usage");
            assertThat(nonHeapLine).containsIgnoringCase("unbounded");
            assertThat(nonHeapLine).doesNotContain("256.00");

            // Unbounded pool (Metaspace): same contract in the MAX (MB) column.
            String metaspaceLine = lineContaining(output, "Metaspace");
            assertThat(metaspaceLine).containsIgnoringCase("unbounded");
            assertThat(metaspaceLine).doesNotContain("128.00");

            // Bounded heap and bounded pool keep the numeric rendering unchanged.
            String heapLine = lineContaining(output, "Heap Usage");
            if (unboundedHeap) {
                assertThat(heapLine).contains("512.00 MB / unbounded (N/A used)");
            } else {
                assertThat(heapLine).contains("512.00 MB / 2048.00 MB (25.0% used)");
                assertThat(heapLine).doesNotContain("unbounded");
            }

            String edenLine = lineContaining(output, "G1 Eden Space");
            assertThat(edenLine).contains("256.00").contains("2048.00");
            assertThat(edenLine).doesNotContain("unbounded");

            // An exact zero max is a real bound, not an unknown one.
            String codeCacheLine = lineContaining(output, "Code Cache");
            assertThat(codeCacheLine).contains("0.00");
            assertThat(codeCacheLine).doesNotContain("unbounded");
            assertThat(codeCacheLine).doesNotContain("N/A");

            // No raw sentinel may leak into any column.
            assertThat(output).doesNotContain("-1.00");
        } finally {
            System.setOut(originalOut);
	    Locale.setDefault(Locale.Category.FORMAT, originalFormatLocale);
        }
    }

    @Test
    @DisplayName("memory command with --histogram and VM attached should print histogram table")
    void shouldPrintHeapHistogramWhenVmPresent() {
        long targetPid = ProcessHandle.current().pid();
        String rawHistogram = """
             num     #instances         #bytes  class name (module)
            -------------------------------------------------------
               1:         10000        2400000  java.lang.String (java.base@21)
               2:          5000        1200000  java.util.HashMap$Node (java.base@21)
            Total         15000        3600000
            """;
        InputStream stream = new ByteArrayInputStream(rawHistogram.getBytes(StandardCharsets.UTF_8));
        VirtualMachine stubVm = new StubVirtualMachine(stream);
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), stubVm));

        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;
        command.histogram = true;
        command.topN = 5;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("-".repeat(80));
            assertThat(output).contains("HEAP HISTOGRAM (TOP 5 CLASSES)");
            assertThat(output).contains("RANK   INSTANCES      BYTES (MB)     CLASS NAME");
            assertThat(output).contains("java.lang.String");
            assertThat(output).contains("java.util.HashMap$Node");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("memory command with --histogram and VM absent should query histogram over JMX")
    void shouldPrintHeapHistogramWhenVmAbsent() {
        long targetPid = ProcessHandle.current().pid();
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));

        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;
        command.histogram = true;
        command.topN = 5;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("HEAP HISTOGRAM (TOP 5 CLASSES)");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("memory command should handle histogram extraction errors gracefully")
    void shouldHandleHistogramExtractionError() {
        long targetPid = ProcessHandle.current().pid();
        VirtualMachine stubVm = new StubVirtualMachine(true);
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), stubVm));

        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;
        command.histogram = true;
        command.topN = 5;

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(err.toString()).contains("[jvm-mcp] Could not extract live heap histogram");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("memory command should catch unexpected JMX connection failures")
    void shouldCatchJmxConnectionFailure() {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenThrow(new RuntimeException("Fatal attach failure"));

        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream originalErr = System.err;
        try {
            System.setErr(new PrintStream(err));
            Integer exitCode = command.call();

            assertThat(exitCode).isEqualTo(1);
            assertThat(err.toString()).contains("Fatal attach failure");
        } finally {
            System.setErr(originalErr);
        }
    }

    @Test
    @DisplayName("Default constructor should initialize properly")
    void shouldInitializeWithDefaultConstructor() {
        MemoryCommand command = new MemoryCommand();
        assertThat(command).isNotNull();
    }

    @Test
    @DisplayName("detaches VirtualMachine when JMX connection fails")
    void shouldDetachWhenJmxConnectFails() throws Exception {
        long targetPid = 999999L;
        StubVirtualMachine mockVm = new StubVirtualMachine(false);
        when(mockAttachService.attach(String.valueOf(targetPid)))
            .thenReturn(AttachResult.success(String.valueOf(targetPid), mockVm));

        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;

        int exitCode = command.call();
        assertThat(exitCode).isEqualTo(1);
        org.mockito.Mockito.verify(mockAttachService).detach(mockVm);
    }

    private static String lineContaining(String output, String needle) {
        return output.lines()
            .filter(line -> line.contains(needle))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "No output line containing '" + needle + "' in:\n" + output));
    }
    @Test
    @DisplayName("memory command should output pure JSON with --json")
    void shouldOutputJsonSummary() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));
        long mb = 1024L * 1024L;
        MemoryUsageInfo heap = MemoryUsageInfo.from(new MemoryUsage(0, 512 * mb, 1024 * mb, 2048 * mb));
        MemoryUsageInfo nonHeap = MemoryUsageInfo.from(new MemoryUsage(0, 64 * mb, 128 * mb, 256 * mb));
        HeapSummary summary = new HeapSummary(targetPid, heap, nonHeap, List.of(), List.of(),
            new MemoryPressure(MemoryPressureLevel.NORMAL, 0.25, 0L, 0L, "Heap looks healthy"));
        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;
        command.json = true;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (var clients = mockConstruction(MemoryMXBeanClient.class,
                (client, context) -> when(client.getHeapSummary(anyLong())).thenReturn(summary))) {
            System.setOut(new PrintStream(out));
            assertThat(command.call()).isZero();
            String output = out.toString().trim();
            assertThat(SimpleJson.parse(output)).isInstanceOf(java.util.Map.class);
            assertThat(output).contains("\"pid\":100").doesNotContain("JVM MEMORY DIAGNOSTICS", "MEMORY POOLS");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("memory command displays class loading metrics in table output when present")
    void shouldDisplayClassLoadingMetricsInTableOutput() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));
        long mb = 1024L * 1024L;
        MemoryUsageInfo heap = MemoryUsageInfo.from(new MemoryUsage(0, 512 * mb, 1024 * mb, 2048 * mb));
        MemoryUsageInfo nonHeap = MemoryUsageInfo.from(new MemoryUsage(0, 64 * mb, 128 * mb, 256 * mb));
        ClassLoadingInfo classLoading = new ClassLoadingInfo(12345L, 14000L, 1655L);
        HeapSummary summary = new HeapSummary(
            targetPid, heap, nonHeap, List.of(), List.of(),
            new MemoryPressure(MemoryPressureLevel.NORMAL, 0.25, 0L, 0L, "Healthy"),
            classLoading
        );
        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (var clients = mockConstruction(MemoryMXBeanClient.class,
                (client, context) -> when(client.getHeapSummary(anyLong())).thenReturn(summary))) {
            System.setOut(new PrintStream(out));
            assertThat(command.call()).isZero();
            String output = out.toString();
            assertThat(output).contains("Classes Loaded : 12345 (Total: 14000, Unloaded: 1655)");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("memory command omits class loading metrics in table output when classLoading is null")
    void shouldOmitClassLoadingMetricsInTableOutputWhenNull() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));
        long mb = 1024L * 1024L;
        MemoryUsageInfo heap = MemoryUsageInfo.from(new MemoryUsage(0, 512 * mb, 1024 * mb, 2048 * mb));
        MemoryUsageInfo nonHeap = MemoryUsageInfo.from(new MemoryUsage(0, 64 * mb, 128 * mb, 256 * mb));
        HeapSummary summary = new HeapSummary(
            targetPid, heap, nonHeap, List.of(), List.of(),
            new MemoryPressure(MemoryPressureLevel.NORMAL, 0.25, 0L, 0L, "Healthy"),
            null
        );
        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (var clients = mockConstruction(MemoryMXBeanClient.class,
                (client, context) -> when(client.getHeapSummary(anyLong())).thenReturn(summary))) {
            System.setOut(new PrintStream(out));
            assertThat(command.call()).isZero();
            String output = out.toString();
            assertThat(output).doesNotContain("Classes Loaded");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("memory command includes class loading metrics in json output")
    void shouldIncludeClassLoadingInJsonOutput() throws Exception {
        long targetPid = 100L;
        when(mockAttachService.attach(String.valueOf(targetPid))).thenReturn(AttachResult.success(String.valueOf(targetPid), null));
        long mb = 1024L * 1024L;
        MemoryUsageInfo heap = MemoryUsageInfo.from(new MemoryUsage(0, 512 * mb, 1024 * mb, 2048 * mb));
        MemoryUsageInfo nonHeap = MemoryUsageInfo.from(new MemoryUsage(0, 64 * mb, 128 * mb, 256 * mb));
        ClassLoadingInfo classLoading = new ClassLoadingInfo(9876L, 10000L, 124L);
        List<BufferPoolInfo> bufferPools = List.of(
            new BufferPoolInfo("direct", 1L, 1024L, 2048L),
            new BufferPoolInfo("mapped", 2L, 4096L, 3072L)
        );
        HeapSummary summary = new HeapSummary(
            targetPid, heap, nonHeap, List.of(), List.of(),
            new MemoryPressure(MemoryPressureLevel.NORMAL, 0.25, 0L, 0L, "Healthy"),
            classLoading, bufferPools
        );
        MemoryCommand command = new MemoryCommand(mockAttachService);
        command.pid = targetPid;
        command.json = true;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try (var clients = mockConstruction(MemoryMXBeanClient.class,
                (client, context) -> when(client.getHeapSummary(anyLong())).thenReturn(summary))) {
            System.setOut(new PrintStream(out));
            assertThat(command.call()).isZero();
            String output = out.toString().trim();
            assertThat(output).contains("\"classLoading\":{\"loadedClassCount\":9876,\"totalLoadedClasses\":10000,\"unloadedClassCount\":124}");
            assertThat(output).contains("\"bufferPools\":[{\"name\":\"direct\",\"count\":1,\"totalCapacityBytes\":1024,\"memoryUsedBytes\":2048},{\"name\":\"mapped\",\"count\":2,\"totalCapacityBytes\":4096,\"memoryUsedBytes\":3072}]");
        } finally {
            System.setOut(originalOut);
        }
    }
}