package dev.jvmmcp.core.integration;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.jmx.ThreadMXBeanClient;
import dev.jvmmcp.core.model.ThreadDetail;
import dev.jvmmcp.core.model.ThreadDump;
import dev.jvmmcp.core.model.ThreadSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Live JVM Virtual Thread Diagnostics Integration")
class LiveVirtualThreadIntegrationTest {

    private Process targetProcess;
    private long targetPid;
    private JvmAttachService attachService;

    @BeforeEach
    void setUp() throws Exception {
        attachService = new JvmAttachService();

        String javaHome = System.getProperty("java.home");
        String javaBin = Paths.get(javaHome, "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
            javaBin, "-cp", classpath, "dev.jvmmcp.core.integration.VirtualThreadTarget"
        );
        targetProcess = pb.start();
        targetPid = targetProcess.pid();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(targetProcess.getInputStream()))) {
            String line = reader.readLine();
            assertThat(line).isEqualTo("READY");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (targetProcess != null && targetProcess.isAlive()) {
            targetProcess.destroyForcibly();
            targetProcess.waitFor(2, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Successfully detects real virtual threads and classifies platform vs virtual threads")
    void shouldDetectAndClassifyVirtualThreadsOnLiveJvm() throws Exception {
        AttachResult result = attachService.attach(String.valueOf(targetPid));
        assertThat(result.isSuccessful()).isTrue();
        assertThat(result.virtualMachine()).isPresent();

        try (JmxConnectionManager jmx = JmxConnectionManager.connect(result.virtualMachine().get(), targetPid);
             ThreadMXBeanClient threadClient = new ThreadMXBeanClient(jmx.getMBeanServerConnection())) {

            // 1. Verify ThreadSummary detects virtual threads
            ThreadSummary summary = threadClient.getThreadSummary(targetPid);

            assertThat(summary.totalCount()).isPositive();
            assertThat(summary.virtualThreads()).isNotNull();
            assertThat(summary.virtualThreads().totalCount()).isGreaterThanOrEqualTo(2);

            // 2. Verify ThreadDump separates platform and virtual threads without double-counting
            ThreadDump dump = threadClient.getThreadDump(targetPid);

            assertThat(dump.threads()).isNotEmpty();
            List<String> platformNames = dump.threads().stream().map(ThreadDetail::threadName).toList();
            assertThat(platformNames).doesNotContain("integration-vthread-1", "integration-vthread-2");

            assertThat(dump.virtualThreads()).isNotEmpty();
            List<String> virtualNames = dump.virtualThreads().stream().map(ThreadDetail::threadName).toList();
            assertThat(virtualNames).contains("integration-vthread-1", "integration-vthread-2");

            // Verify virtual thread state and stack frames
            ThreadDetail vthread = dump.virtualThreads().stream()
                .filter(t -> "integration-vthread-1".equals(t.threadName()))
                .findFirst()
                .orElse(null);

            assertThat(vthread).isNotNull();
            assertThat(vthread.state()).isEqualTo("VIRTUAL");
            assertThat(vthread.stackTrace()).isNotEmpty();
        }
    }
}
