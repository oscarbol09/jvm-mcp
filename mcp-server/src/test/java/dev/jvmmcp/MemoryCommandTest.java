package dev.jvmmcp;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import com.sun.tools.attach.spi.AttachProvider;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
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
            assertThat(output).contains("JVM MEMORY DIAGNOSTICS FOR PID " + targetPid);
            assertThat(output).contains("Heap Usage");
            assertThat(output).contains("Non-Heap Usage");
            assertThat(output).contains("Pressure Status");
            assertThat(output).contains("Recommendation");
            assertThat(output).contains("MEMORY POOLS");
        } finally {
            System.setOut(originalOut);
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
            assertThat(output).contains("HEAP HISTOGRAM (TOP 5 CLASSES)");
            assertThat(output).contains("java.lang.String");
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
            assertThat(err.toString()).contains("[jvm-mcp] Could not extract live heap histogram: jcmd error");
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
        assertThat(command.attachService).isNotNull();
    }
}
