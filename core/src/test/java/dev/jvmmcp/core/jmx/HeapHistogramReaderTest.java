package dev.jvmmcp.core.jmx;

import dev.jvmmcp.core.model.HeapHistogram;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeapHistogramReaderTest {

    private static final String SAMPLE_HISTOGRAM = """
         num     #instances         #bytes  class name (module)
        -------------------------------------------------------
           1:         45231        5427720  java.lang.String (java.base@21.0.3)
           2:         23104        1848320  java.util.concurrent.ConcurrentHashMap$Node (java.base@21.0.3)
           3:          5120         819200  [B (java.base@21.0.3)
           4:          1024         122880  com.example.OrderService
        Total         74479        8218120
        """;

    @Test
    @DisplayName("readHistogram throws IllegalArgumentException when VirtualMachine is null")
    void shouldThrowWhenVmIsNull() {
        HeapHistogramReader reader = new HeapHistogramReader();
        assertThatThrownBy(() -> reader.readHistogram(null, 123L, 10))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("VirtualMachine cannot be null");
    }

    @Test
    @DisplayName("parseHistogramStream correctly parses class rankings, instance counts, and byte sizes")
    void shouldParseHistogramStream() throws Exception {
        HeapHistogramReader reader = new HeapHistogramReader();
        ByteArrayInputStream in = new ByteArrayInputStream(SAMPLE_HISTOGRAM.getBytes(StandardCharsets.UTF_8));

        HeapHistogram histogram = reader.parseHistogramStream(in, 12345L, 3);

        assertThat(histogram).isNotNull();
        assertThat(histogram.pid()).isEqualTo(12345L);
        assertThat(histogram.totalInstances()).isEqualTo(74479L);
        assertThat(histogram.totalBytes()).isEqualTo(8218120L);
        assertThat(histogram.totalMegabytes()).isPositive();
        assertThat(histogram.topClasses()).hasSize(3);

        assertThat(histogram.topClasses().get(0).rank()).isEqualTo(1);
        assertThat(histogram.topClasses().get(0).className()).contains("java.lang.String");
        assertThat(histogram.topClasses().get(0).instances()).isEqualTo(45231L);
        assertThat(histogram.topClasses().get(0).bytes()).isEqualTo(5427720L);
        assertThat(histogram.topClasses().get(0).megabytes()).isPositive();
    }

    @Test
    @DisplayName("parseHistogramStream returns empty histogram for null or empty stream")
    void shouldHandleNullOrEmptyStream() throws Exception {
        HeapHistogramReader reader = new HeapHistogramReader();

        HeapHistogram nullResult = reader.parseHistogramStream(null, 123L, 10);
        assertThat(nullResult.totalInstances()).isZero();
        assertThat(nullResult.totalBytes()).isZero();
        assertThat(nullResult.topClasses()).isEmpty();

        ByteArrayInputStream emptyStream = new ByteArrayInputStream(new byte[0]);
        HeapHistogram emptyResult = reader.parseHistogramStream(emptyStream, 123L, 10);
        assertThat(emptyResult.totalInstances()).isZero();
        assertThat(emptyResult.totalBytes()).isZero();
        assertThat(emptyResult.topClasses()).isEmpty();
    }

    @Test
    @DisplayName("parseHistogramStream calculates totals from item sums when Total line is missing")
    void shouldCalculateTotalsWhenFooterMissing() throws Exception {
        String noFooterOutput = """
             num     #instances         #bytes  class name
            ----------------------------------------------
               1:            10            100  java.lang.String
               2:            20            200  java.lang.Integer
            """;

        HeapHistogramReader reader = new HeapHistogramReader();
        ByteArrayInputStream in = new ByteArrayInputStream(noFooterOutput.getBytes(StandardCharsets.UTF_8));

        HeapHistogram histogram = reader.parseHistogramStream(in, 123L, 0); // topN <= 0 returns all

        assertThat(histogram.topClasses()).hasSize(2);
        assertThat(histogram.totalInstances()).isEqualTo(30L);
        assertThat(histogram.totalBytes()).isEqualTo(300L);
    }

    @Test
    @DisplayName("parseHistogramStream ignores malformed rows gracefully")
    void shouldIgnoreMalformedRows() throws Exception {
        String malformedOutput = """
             num     #instances         #bytes  class name
            ----------------------------------------------
             invalid_row_without_parts
               NaN:         invalid        invalid  java.lang.String
               1:            50            500  java.lang.String
            Total          NaN           NaN
            """;

        HeapHistogramReader reader = new HeapHistogramReader();
        ByteArrayInputStream in = new ByteArrayInputStream(malformedOutput.getBytes(StandardCharsets.UTF_8));

        HeapHistogram histogram = reader.parseHistogramStream(in, 123L, 10);

        assertThat(histogram.topClasses()).hasSize(1);
        assertThat(histogram.topClasses().get(0).className()).isEqualTo("java.lang.String");
    }

    // Dummy VM class with executeJCmd for reflection test
    public static class DummyProvider extends com.sun.tools.attach.spi.AttachProvider {
        @Override public String name() { return "dummy"; }
        @Override public String type() { return "dummy"; }
        @Override public com.sun.tools.attach.VirtualMachine attachVirtualMachine(String id) { return null; }
        @Override public java.util.List<com.sun.tools.attach.VirtualMachineDescriptor> listVirtualMachines() { return java.util.List.of(); }
    }

    public static class FakeVirtualMachine extends com.sun.tools.attach.VirtualMachine {
        protected FakeVirtualMachine() {
            super(new DummyProvider(), "123");
        }

        public InputStream executeJCmd(String cmd) {
            return new ByteArrayInputStream(SAMPLE_HISTOGRAM.getBytes(StandardCharsets.UTF_8));
        }

        @Override public void detach() {}
        @Override public void loadAgentLibrary(String agentLibrary, String options) {}
        @Override public void loadAgentPath(String agentPath, String options) {}
        @Override public void loadAgent(String agent, String options) {}
        @Override public java.util.Properties getSystemProperties() { return new java.util.Properties(); }
        @Override public java.util.Properties getAgentProperties() { return new java.util.Properties(); }
        @Override public void startManagementAgent(java.util.Properties agentProperties) {}
        @Override public String startLocalManagementAgent() { return null; }
    }

    @Test
    @DisplayName("readHistogram invokes executeJCmd reflectively and parses output")
    void shouldReadHistogramViaReflectiveExecuteJCmd() throws Exception {
        FakeVirtualMachine fakeVm = new FakeVirtualMachine();
        HeapHistogramReader reader = new HeapHistogramReader();

        HeapHistogram histogram = reader.readHistogram(fakeVm, 123L, 2);

        assertThat(histogram).isNotNull();
        assertThat(histogram.topClasses()).hasSize(2);
    }
}
