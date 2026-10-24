package dev.jvmmcp.core.model;

import dev.jvmmcp.core.util.SimpleJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ClassLoadingInfo Model & HeapSummary Serialization Tests")
class ClassLoadingModelTest {

    @Test
    @DisplayName("ClassLoadingInfo correctly stores and exposes counts")
    void shouldStoreClassLoadingCounts() {
        ClassLoadingInfo info = new ClassLoadingInfo(1500L, 2000L, 500L);

        assertThat(info.loadedClassCount()).isEqualTo(1500L);
        assertThat(info.totalLoadedClasses()).isEqualTo(2000L);
        assertThat(info.unloadedClassCount()).isEqualTo(500L);
    }

    @Test
    @DisplayName("HeapSummary 6-argument constructor defaults classLoading to null for backward compatibility")
    void shouldMaintainHeapSummaryBackwardCompatibility() {
        MemoryUsageInfo usage = new MemoryUsageInfo(100, 200, 300, 400, 50.0, 200.0, 400.0);
        MemoryPressure pressure = new MemoryPressure(MemoryPressureLevel.NORMAL, 0.5, 0L, 0L, "Healthy");
        HeapSummary summary = new HeapSummary(100L, usage, usage, List.of(), List.of(), pressure);

        assertThat(summary.pid()).isEqualTo(100L);
        assertThat(summary.heap()).isEqualTo(usage);
        assertThat(summary.nonHeap()).isEqualTo(usage);
        assertThat(summary.pressure()).isEqualTo(pressure);
        assertThat(summary.classLoading()).isNull();
    }

    @Test
    @DisplayName("HeapSummary retains ClassLoadingInfo when provided via 7-argument constructor")
    void shouldRetainClassLoadingInfoInHeapSummary() {
        MemoryUsageInfo usage = new MemoryUsageInfo(100, 200, 300, 400, 50.0, 200.0, 400.0);
        MemoryPressure pressure = new MemoryPressure(MemoryPressureLevel.NORMAL, 0.5, 0L, 0L, "Healthy");
        ClassLoadingInfo classLoading = new ClassLoadingInfo(1000L, 1200L, 200L);

        HeapSummary summary = new HeapSummary(100L, usage, usage, List.of(), List.of(), pressure, classLoading);

        assertThat(summary.classLoading()).isNotNull();
        assertThat(summary.classLoading().loadedClassCount()).isEqualTo(1000L);
        assertThat(summary.classLoading().totalLoadedClasses()).isEqualTo(1200L);
        assertThat(summary.classLoading().unloadedClassCount()).isEqualTo(200L);
    }

    @Test
    @DisplayName("SimpleJson serializes HeapSummary with classLoading correctly")
    void shouldSerializeHeapSummaryWithClassLoading() {
        MemoryUsageInfo usage = new MemoryUsageInfo(100, 200, 300, 400, 50.0, 200.0, 400.0);
        MemoryPressure pressure = new MemoryPressure(MemoryPressureLevel.NORMAL, 0.5, 0L, 0L, "Healthy");
        ClassLoadingInfo classLoading = new ClassLoadingInfo(5000L, 6000L, 1000L);

        HeapSummary summary = new HeapSummary(100L, usage, usage, List.of(), List.of(), pressure, classLoading);

        String json = SimpleJson.toJson(summary);
        assertThat(json).contains("\"classLoading\":{");
        assertThat(json).contains("\"loadedClassCount\":5000");
        assertThat(json).contains("\"totalLoadedClasses\":6000");
        assertThat(json).contains("\"unloadedClassCount\":1000");

        Map<String, Object> parsed = SimpleJson.parseObject(json);
        assertThat(parsed.get("classLoading")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> classLoadingMap = (Map<String, Object>) parsed.get("classLoading");
        assertThat(((Number) classLoadingMap.get("loadedClassCount")).longValue()).isEqualTo(5000L);
        assertThat(((Number) classLoadingMap.get("totalLoadedClasses")).longValue()).isEqualTo(6000L);
        assertThat(((Number) classLoadingMap.get("unloadedClassCount")).longValue()).isEqualTo(1000L);
    }

    @Test
    @DisplayName("SimpleJson serializes null classLoading when absent")
    void shouldSerializeHeapSummaryWithNullClassLoading() {
        MemoryUsageInfo usage = new MemoryUsageInfo(100, 200, 300, 400, 50.0, 200.0, 400.0);
        MemoryPressure pressure = new MemoryPressure(MemoryPressureLevel.NORMAL, 0.5, 0L, 0L, "Healthy");
        HeapSummary summary = new HeapSummary(100L, usage, usage, List.of(), List.of(), pressure, null);

        String json = SimpleJson.toJson(summary);
        assertThat(json).contains("\"classLoading\":null");

        Map<String, Object> parsed = SimpleJson.parseObject(json);
        assertThat(parsed.containsKey("classLoading")).isTrue();
        assertThat(parsed.get("classLoading")).isNull();
    }
}
