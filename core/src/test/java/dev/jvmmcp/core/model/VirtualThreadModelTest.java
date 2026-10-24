package dev.jvmmcp.core.model;

import dev.jvmmcp.core.util.SimpleJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Virtual Thread Diagnostic Models & Serialization Tests")
class VirtualThreadModelTest {

    @Test
    @DisplayName("ThreadSummary 9-argument constructor defaults virtualThreads to null")
    void shouldMaintainThreadSummaryBackwardCompatibility() {
        ThreadSummary summary = new ThreadSummary(123L, 10, 2, 12, 50L, 5, 1, 2, 2);

        assertThat(summary.pid()).isEqualTo(123L);
        assertThat(summary.totalCount()).isEqualTo(10);
        assertThat(summary.virtualThreads()).isNull();
    }

    @Test
    @DisplayName("ThreadSummary retains VirtualThreadSummary when provided")
    void shouldConstructThreadSummaryWithVirtualThreads() {
        VirtualThreadSummary vtSummary = new VirtualThreadSummary(42);
        ThreadSummary summary = new ThreadSummary(123L, 10, 2, 12, 50L, 5, 1, 2, 2, vtSummary);

        assertThat(summary.virtualThreads()).isNotNull();
        assertThat(summary.virtualThreads().totalCount()).isEqualTo(42);
    }

    @Test
    @DisplayName("ThreadDump 4-argument constructor defaults virtualThreads to null")
    void shouldMaintainThreadDumpBackwardCompatibility() {
        Instant now = Instant.now();
        ThreadDetail detail = new ThreadDetail(
            1L, "main", "RUNNABLE", null, null, null, 0L, 0L, 0L, 0L, false, false, List.of()
        );
        ThreadDump dump = new ThreadDump(123L, now, 1, List.of(detail));

        assertThat(dump.pid()).isEqualTo(123L);
        assertThat(dump.threads()).hasSize(1);
        assertThat(dump.virtualThreads()).isNull();
    }

    @Test
    @DisplayName("ThreadDump retains virtualThreads list when provided")
    void shouldConstructThreadDumpWithVirtualThreads() {
        Instant now = Instant.now();
        ThreadDetail platform = new ThreadDetail(
            1L, "main", "RUNNABLE", null, null, null, 0L, 0L, 0L, 0L, false, false, List.of()
        );
        ThreadDetail virtual = new ThreadDetail(
            100L, "vt-worker", "VIRTUAL", null, null, null, 0L, 0L, 0L, 0L, false, false, List.of()
        );
        ThreadDump dump = new ThreadDump(123L, now, 1, List.of(platform), List.of(virtual));

        assertThat(dump.threads()).containsExactly(platform);
        assertThat(dump.virtualThreads()).containsExactly(virtual);
    }

    @Test
    @DisplayName("SimpleJson distinguishes available empty virtualThreads from unavailable null virtualThreads in ThreadDump")
    void shouldDistinguishAvailableFromUnavailableVirtualThreadsInThreadDump() {
        Instant now = Instant.now();
        ThreadDetail platform = new ThreadDetail(
            1L, "main", "RUNNABLE", null, null, null, 0L, 0L, 0L, 0L, false, false, List.of()
        );

        // Case 1: Available empty virtual thread dump
        ThreadDump availableEmptyDump = new ThreadDump(123L, now, 1, List.of(platform), List.of());
        String availableJson = SimpleJson.toJson(availableEmptyDump);
        assertThat(availableJson).contains("\"virtualThreads\":[]");
        assertThat(availableEmptyDump.virtualThreads()).isNotNull().isEmpty();

        // Case 2: Unavailable virtual thread dump
        ThreadDump unavailableDump = new ThreadDump(123L, now, 1, List.of(platform), null);
        String unavailableJson = SimpleJson.toJson(unavailableDump);
        assertThat(unavailableJson).contains("\"virtualThreads\":null");
        assertThat(unavailableDump.virtualThreads()).isNull();

        // Platform threads remain intact and identical in both cases
        assertThat(availableEmptyDump.threads()).containsExactly(platform);
        assertThat(unavailableDump.threads()).containsExactly(platform);
    }

    @Test
    @DisplayName("SimpleJson serializes and parses ThreadSummary with virtualThreads correctly")
    void shouldSerializeAndParseThreadSummary() {
        VirtualThreadSummary vtSummary = new VirtualThreadSummary(5);
        ThreadSummary summary = new ThreadSummary(99L, 10, 2, 15, 20L, 6, 0, 3, 1, vtSummary);

        String json = SimpleJson.toJson(summary);
        assertThat(json).contains("\"virtualThreads\":{\"totalCount\":5}");

        Map<String, Object> map = SimpleJson.parseObject(json);
        assertThat(map.get("pid").toString()).isEqualTo("99");
        assertThat(map.get("totalCount").toString()).isEqualTo("10");

        @SuppressWarnings("unchecked")
        Map<String, Object> vtMap = (Map<String, Object>) map.get("virtualThreads");
        assertThat(vtMap).isNotNull();
        assertThat(vtMap.get("totalCount").toString()).isEqualTo("5");
    }

    @Test
    @DisplayName("SimpleJson serializes ThreadSummary with null virtualThreads without failing")
    void shouldSerializeThreadSummaryWithNullVirtualThreads() {
        ThreadSummary summary = new ThreadSummary(99L, 10, 2, 15, 20L, 6, 0, 3, 1);

        String json = SimpleJson.toJson(summary);
        assertThat(json).contains("\"virtualThreads\":null");

        Map<String, Object> map = SimpleJson.parseObject(json);
        assertThat(map.get("virtualThreads")).isNull();
    }
}
