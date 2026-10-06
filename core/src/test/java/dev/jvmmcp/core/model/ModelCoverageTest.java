package dev.jvmmcp.core.model;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.AttachStatus;
import dev.jvmmcp.core.attach.FrameworkDetector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ModelCoverageTest {

    @Mock
    private VirtualMachine mockVm;

    @Test
    @DisplayName("AttachResult factory methods construct accurate states")
    void shouldVerifyAttachResultFactories() {
        AttachResult success = AttachResult.success("123", mockVm);
        assertThat(success.isSuccessful()).isTrue();
        assertThat(success.status()).isEqualTo(AttachStatus.SUCCESS);
        assertThat(success.virtualMachine()).contains(mockVm);

        AttachResult notFound = AttachResult.processNotFound("123");
        assertThat(notFound.isSuccessful()).isFalse();
        assertThat(notFound.status()).isEqualTo(AttachStatus.PROCESS_NOT_FOUND);

        AttachResult denied = AttachResult.permissionDenied("123", "access denied");
        assertThat(denied.isSuccessful()).isFalse();
        assertThat(denied.status()).isEqualTo(AttachStatus.PERMISSION_DENIED);
        assertThat(denied.message()).contains("access denied");

        AttachResult unsupp = AttachResult.unsupportedNamespace("123", "namespace clash");
        assertThat(unsupp.isSuccessful()).isFalse();
        assertThat(unsupp.status()).isEqualTo(AttachStatus.UNSUPPORTED_NAMESPACE);

        AttachResult error = AttachResult.error("123", "failed");
        assertThat(error.isSuccessful()).isFalse();
        assertThat(error.status()).isEqualTo(AttachStatus.GENERIC_ERROR);
    }

    @Test
    @DisplayName("FrameworkDetector classifies JVM processes by heuristic signatures")
    void shouldClassifyFrameworks() {
        assertThat(FrameworkDetector.detect(null, null)).isEqualTo(Framework.UNKNOWN);
        assertThat(FrameworkDetector.detect("   ", "")).isEqualTo(Framework.UNKNOWN);
        assertThat(FrameworkDetector.detect("demo-service", "org.springframework.boot.SpringApplication")).isEqualTo(Framework.SPRING_BOOT);
        assertThat(FrameworkDetector.detect("quarkus-app.jar", "io.quarkus.runner.Main")).isEqualTo(Framework.QUARKUS);
        assertThat(FrameworkDetector.detect("micronaut-app", "io.micronaut.Application")).isEqualTo(Framework.MICRONAUT);
        assertThat(FrameworkDetector.detect("java -jar plain.jar", "com.example.App")).isEqualTo(Framework.PLAIN_JAVA);
    }

    @Test
    @DisplayName("MemoryUsageInfo handles null, fixed max, and undefined max correctly")
    void shouldFormatMemoryUsageInfo() {
        MemoryUsageInfo nullInfo = MemoryUsageInfo.from(null);
        assertThat(nullInfo.usedBytes()).isZero();
        assertThat(nullInfo.usedPercent()).isZero();

        MemoryUsage normalUsage = new MemoryUsage(100, 20 * 1024 * 1024, 50 * 1024 * 1024, 100 * 1024 * 1024);
        MemoryUsageInfo normalInfo = MemoryUsageInfo.from(normalUsage);
        assertThat(normalInfo.usedPercent()).isEqualTo(20.0);
        assertThat(normalInfo.usedMb()).isEqualTo(20.0);

        MemoryUsage undefinedMax = new MemoryUsage(100, 25 * 1024 * 1024, 50 * 1024 * 1024, -1);
        MemoryUsageInfo undefinedInfo = MemoryUsageInfo.from(undefinedMax);
        assertThat(undefinedInfo.usedPercent()).isEqualTo(50.0);
    }

    @Test
    @DisplayName("ThreadStackFrame formats native, file:line, and unknown source frames")
    void shouldFormatThreadStackFrame() {
        ThreadStackFrame nullFrame = ThreadStackFrame.from(null);
        assertThat(nullFrame.className()).isEqualTo("Unknown");
        assertThat(nullFrame.toString()).contains("Unknown Source");

        StackTraceElement nativeElem = new StackTraceElement("com.example.NativeUtil", "doWork", null, -2);
        ThreadStackFrame nativeFrame = ThreadStackFrame.from(nativeElem);
        assertThat(nativeFrame.isNativeMethod()).isTrue();
        assertThat(nativeFrame.toString()).isEqualTo("com.example.NativeUtil.doWork(Native Method)");

        StackTraceElement fileLineElem = new StackTraceElement("com.example.Service", "process", "Service.java", 42);
        ThreadStackFrame fileLineFrame = ThreadStackFrame.from(fileLineElem);
        assertThat(fileLineFrame.toString()).isEqualTo("com.example.Service.process(Service.java:42)");

        StackTraceElement noFileElem = new StackTraceElement("com.example.Service", "process", null, -1);
        ThreadStackFrame noFileFrame = ThreadStackFrame.from(noFileElem);
        assertThat(noFileFrame.toString()).isEqualTo("com.example.Service.process(Unknown Source)");
    }

    @Test
    @DisplayName("ThreadDetail converts live ThreadInfo safely")
    void shouldConvertThreadInfo() {
        assertThat(ThreadDetail.from(null)).isNull();

        ThreadMXBean threadMXBean = ManagementFactory.getThreadMXBean();
        ThreadInfo currentInfo = threadMXBean.getThreadInfo(Thread.currentThread().getId(), Integer.MAX_VALUE);

        ThreadDetail detail = ThreadDetail.from(currentInfo);
        assertThat(detail).isNotNull();
        assertThat(detail.threadName()).isEqualTo(Thread.currentThread().getName());
        assertThat(detail.stackTrace()).isNotEmpty();
    }

    @Test
    @DisplayName("DeadlockReport none and detected factories initialize consistent models")
    void shouldFormatDeadlockReports() {
        DeadlockReport none = DeadlockReport.none();
        assertThat(none.status()).isEqualTo("NONE");
        assertThat(none.deadlockCount()).isZero();
        assertThat(none.chain()).isEmpty();

        DeadlockedThreadDetail detail = new DeadlockedThreadDetail(
            1L, "worker", "BLOCKED", "MonitorLock", 2L, "owner", "stackTop"
        );
        DeadlockReport detected = DeadlockReport.detected(List.of(detail), "Fix deadlock");
        assertThat(detected.status()).isEqualTo("DETECTED");
        assertThat(detected.deadlockCount()).isEqualTo(1);
        assertThat(detected.chain()).containsExactly(detail);
        assertThat(detected.recommendation()).isEqualTo("Fix deadlock");
    }

    @Test
    @DisplayName("ClassHistogramItem formats megabytes calculation")
    void shouldCalculateMegabytes() {
        ClassHistogramItem item = ClassHistogramItem.of(1, 1000L, 2 * 1024 * 1024L, "java.lang.String");
        assertThat(item.megabytes()).isEqualTo(2.0);
        assertThat(item.className()).isEqualTo("java.lang.String");
    }

    @Test
    @DisplayName("SpringBeansReport aggregates beans across multiple contexts")
    void shouldAggregateAllBeans() {
        SpringBeanDetail b1 = new SpringBeanDetail("b1", List.of(), "singleton", "T1", null, List.of());
        SpringBeanDetail b2 = new SpringBeanDetail("b2", List.of(), "singleton", "T2", null, List.of());

        SpringContextBeans ctx1 = new SpringContextBeans("ctx1", null, 1, List.of(b1));
        SpringContextBeans ctx2 = new SpringContextBeans("ctx2", "ctx1", 1, List.of(b2));

        SpringBeansReport report = new SpringBeansReport(100L, "HTTP", 2, List.of(ctx1, ctx2));
        assertThat(report.getAllBeans()).containsExactly(b1, b2);
    }
}
