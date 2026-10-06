package dev.jvmmcp.core.integration;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.jmx.JmxConnectionManager;
import dev.jvmmcp.core.jmx.MemoryMXBeanClient;
import dev.jvmmcp.core.jmx.ThreadMXBeanClient;
import dev.jvmmcp.core.model.HeapSummary;
import dev.jvmmcp.core.model.ThreadSummary;
import org.junit.jupiter.api.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Live JVM Attach & Diagnostics Integration")
class LiveJvmAttachIntegrationTest {

    private Process targetProcess;
    private long targetPid;
    private JvmAttachService attachService;

    @BeforeEach
    void setUp() throws Exception {
        attachService = new JvmAttachService();

        // Start DummyTarget JVM
        String javaHome = System.getProperty("java.home");
        String javaBin = Paths.get(javaHome, "bin", "java").toString();
        String classpath = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", classpath, "dev.jvmmcp.core.integration.DummyTarget");
        targetProcess = pb.start();
        targetPid = targetProcess.pid();

        // Wait for "READY" signal
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

    @Nested
    @DisplayName("When attaching to a live Java 21 process")
    class WhenAttaching {

        @Test
        @DisplayName("Successfully connects and reads live Heap and Thread metrics via JMX")
        void shouldConnectAndReadLiveMetrics() throws Exception {
            // 1. Attach
            AttachResult result = attachService.attach(String.valueOf(targetPid));
            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.virtualMachine()).isPresent();

            // 2. Connect JMX
            try (JmxConnectionManager jmx = JmxConnectionManager.connect(result.virtualMachine().get(), targetPid)) {
                
                // 3. Read Memory (using the Port implementation)
                MemoryMXBeanClient memoryClient = new MemoryMXBeanClient(jmx.getMBeanServerConnection());
                HeapSummary heap = memoryClient.getHeapSummary(targetPid);
                
                assertThat(heap.heap().maxMb()).isGreaterThan(0);
                assertThat(heap.heap().usedMb()).isGreaterThan(0);
                assertThat(heap.pools()).isNotEmpty();
                assertThat(heap.pressure().level()).isNotNull();

                // 4. Read Threads
                ThreadMXBeanClient threadClient = new ThreadMXBeanClient(jmx.getMBeanServerConnection());
                ThreadSummary threads = threadClient.getThreadSummary(targetPid);

                assertThat(threads.totalCount()).isGreaterThan(0);
                assertThat(threads.peakCount()).isGreaterThan(0);
            }
        }
    }
}
