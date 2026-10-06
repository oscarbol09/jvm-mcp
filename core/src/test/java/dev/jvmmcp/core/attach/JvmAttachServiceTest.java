package dev.jvmmcp.core.attach;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.model.JvmProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JvmAttachServiceTest {

    private JvmAttachService attachService;

    @Mock
    private VirtualMachine mockVm;

    @BeforeEach
    void setUp() {
        attachService = new JvmAttachService();
    }

    @Test
    @DisplayName("listJvms should find active JVM processes including current process")
    void shouldListRunningJvmsIncludingSelf() {
        List<JvmProcess> jvms = attachService.listJvms();

        assertThat(jvms).isNotNull();
        assertThat(jvms).isNotEmpty();

        long currentPid = ProcessHandle.current().pid();
        boolean foundSelf = jvms.stream().anyMatch(jvm -> jvm.pid() == currentPid);

        assertThat(foundSelf)
            .as("listJvms() must discover the test runner JVM itself")
            .isTrue();
    }

    @Test
    @DisplayName("attach with non-existent PID should gracefully return not found or generic error")
    void shouldHandleNonExistentPidGracefully() {
        String nonExistentPid = "999999999";

        AttachResult result = attachService.attach(nonExistentPid);

        assertThat(result).isNotNull();
        assertThat(result.isSuccessful()).isFalse();
        assertThat(result.status()).isIn(AttachStatus.PROCESS_NOT_FOUND, AttachStatus.GENERIC_ERROR);
    }

    @Test
    @DisplayName("attach with null or blank PID should return generic error")
    void shouldHandleBlankPidGracefully() {
        AttachResult nullResult = attachService.attach(null);
        assertThat(nullResult.isSuccessful()).isFalse();
        assertThat(nullResult.status()).isEqualTo(AttachStatus.GENERIC_ERROR);

        AttachResult blankResult = attachService.attach("   ");
        assertThat(blankResult.isSuccessful()).isFalse();
        assertThat(blankResult.status()).isEqualTo(AttachStatus.GENERIC_ERROR);
    }

    @Test
    @DisplayName("detach handles null and suppresses IOExceptions gracefully")
    void shouldHandleDetachGracefully() throws Exception {
        attachService.detach(null); // No error

        attachService.detach(mockVm);
        verify(mockVm).detach();

        doThrow(new IOException("Detach failure")).when(mockVm).detach();
        attachService.detach(mockVm); // Handled silently without throwing
    }

    @Test
    @DisplayName("extractMainClass parses jar paths and main classes accurately")
    void shouldExtractMainClassAccurately() {
        assertThat(attachService.extractMainClass(null)).isEqualTo("Unknown");
        assertThat(attachService.extractMainClass("")).isEqualTo("Unknown");
        assertThat(attachService.extractMainClass("   ")).isEqualTo("Unknown");
        assertThat(attachService.extractMainClass("-jar")).isEqualTo("Unknown");
        assertThat(attachService.extractMainClass("-jar target/demo.jar")).isEqualTo("demo.jar");
        assertThat(attachService.extractMainClass("-jar C:\\apps\\demo.jar")).isEqualTo("demo.jar");
        assertThat(attachService.extractMainClass("service.jar")).isEqualTo("service.jar");
        assertThat(attachService.extractMainClass("org.example.Application --spring.profiles.active=dev")).isEqualTo("org.example.Application");
        assertThat(attachService.extractMainClass("com.example.BatchRunner --input /tmp/data.jar")).isEqualTo("com.example.BatchRunner");
    }
}
