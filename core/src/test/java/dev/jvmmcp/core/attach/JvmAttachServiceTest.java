package dev.jvmmcp.core.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.model.JvmProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
@DisplayName("JVM Attach Service")
class JvmAttachServiceTest {

    private JvmAttachService attachService;

    @Mock
    private VirtualMachine mockVm;

    @BeforeEach
    void setUp() {
        attachService = new JvmAttachService();
    }

    @Nested
    @DisplayName("When discovering running JVMs")
    class DiscoveringJvms {

        @Test
        @DisplayName("listJvms should find active JVM processes including the current test runner process")
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
        @DisplayName("listJvms should safely ignore non-numeric PIDs without throwing exceptions")
        void shouldIgnoreNonNumericPids() {
            com.sun.tools.attach.VirtualMachineDescriptor mockDesc = org.mockito.Mockito.mock(com.sun.tools.attach.VirtualMachineDescriptor.class);
            org.mockito.Mockito.when(mockDesc.id()).thenReturn("not-a-number");

            try (org.mockito.MockedStatic<VirtualMachine> vmStatic = org.mockito.Mockito.mockStatic(VirtualMachine.class)) {
                vmStatic.when(VirtualMachine::list).thenReturn(java.util.List.of(mockDesc));
                List<JvmProcess> jvms = attachService.listJvms();
                assertThat(jvms).isEmpty();
            }
        }
    }

    @Nested
    @DisplayName("When attaching to a JVM")
    class AttachingToJvms {

        @Test
        @DisplayName("should successfully return AttachResult containing a VirtualMachine on valid PID")
        void shouldAttachSuccessfully() {
            try (org.mockito.MockedStatic<VirtualMachine> vmStatic = org.mockito.Mockito.mockStatic(VirtualMachine.class)) {
                vmStatic.when(() -> VirtualMachine.attach("123")).thenReturn(mockVm);
                AttachResult result = attachService.attach("123");
                assertThat(result.isSuccessful()).isTrue();
                assertThat(result.virtualMachine()).isPresent();
            }
        }

        @Test
        @DisplayName("should handle non-existent PIDs gracefully and return PROCESS_NOT_FOUND or GENERIC_ERROR status")
        void shouldHandleNonExistentPidGracefully() {
            String nonExistentPid = "999999999";
            AttachResult result = attachService.attach(nonExistentPid);

            assertThat(result).isNotNull();
            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.status()).isIn(AttachStatus.PROCESS_NOT_FOUND, AttachStatus.GENERIC_ERROR);
        }

        @Test
        @DisplayName("should handle null or blank PIDs gracefully returning GENERIC_ERROR status")
        void shouldHandleBlankPidGracefully() {
            AttachResult nullResult = attachService.attach(null);
            assertThat(nullResult.isSuccessful()).isFalse();
            assertThat(nullResult.status()).isEqualTo(AttachStatus.GENERIC_ERROR);

            AttachResult blankResult = attachService.attach("   ");
            assertThat(blankResult.isSuccessful()).isFalse();
            assertThat(blankResult.status()).isEqualTo(AttachStatus.GENERIC_ERROR);
        }
    }

    @Nested
    @DisplayName("When mapping and handling exceptions")
    class ExceptionMapping {

        @Test
        @DisplayName("categorizes AttachNotSupportedException and IOException into domain-specific AttachStatus")
        void shouldMapAllAttachExceptions() {
            AttachResult nsResult = attachService.mapAttachException("100", new AttachNotSupportedException("Different container namespace"));
            assertThat(nsResult.status()).isEqualTo(AttachStatus.UNSUPPORTED_NAMESPACE);

            AttachResult genAttachResult = attachService.mapAttachException("101", new AttachNotSupportedException("Target JVM refuses connection"));
            assertThat(genAttachResult.status()).isEqualTo(AttachStatus.GENERIC_ERROR);

            AttachResult permResult = attachService.mapAttachException("102", new IOException("Permission denied"));
            assertThat(permResult.status()).isEqualTo(AttachStatus.PERMISSION_DENIED);

            AttachResult notFoundResult = attachService.mapAttachException("103", new IOException("No such process"));
            assertThat(notFoundResult.status()).isEqualTo(AttachStatus.PROCESS_NOT_FOUND);

            AttachResult unexpectedResult = attachService.mapAttachException("105", new RuntimeException("Unexpected panic"));
            assertThat(unexpectedResult.status()).isEqualTo(AttachStatus.GENERIC_ERROR);
        }
    }

    @Nested
    @DisplayName("When detaching and extracting metadata")
    class UtilityFunctions {

        @Test
        @DisplayName("detach handles null VMs and suppresses IOExceptions gracefully")
        void shouldHandleDetachGracefully() throws Exception {
            attachService.detach(null);

            attachService.detach(mockVm);
            verify(mockVm).detach();

            doThrow(new IOException("Detach failure")).when(mockVm).detach();
            attachService.detach(mockVm); // Should not throw
        }

        @Test
        @DisplayName("extractMainClass strips jar paths and identifies true main classes accurately")
        void shouldExtractMainClassAccurately() {
            assertThat(attachService.extractMainClass("-jar target/demo.jar")).isEqualTo("demo.jar");
            assertThat(attachService.extractMainClass("-jar C:\\apps\\demo.jar")).isEqualTo("demo.jar");
            assertThat(attachService.extractMainClass("org.example.Application --spring.profiles.active=dev")).isEqualTo("org.example.Application");
        }
    }

    @Nested
    @DisplayName("When attaching to the current process")
    class AttachingToSelf {

        @Test
        @DisplayName("attach should succeed via local self-inspection without invoking the Attach API")
        void shouldReturnSelfAttachResultForCurrentPid() {
            String currentPid = String.valueOf(ProcessHandle.current().pid());

            AttachResult result = attachService.attach(currentPid);

            assertThat(result.isSuccessful()).isTrue();
            assertThat(result.pid()).isEqualTo(currentPid);
            assertThat(result.virtualMachine()).isEmpty();
        }

        @Test
        @DisplayName("attach should still use the Attach API for other processes")
        void shouldUseAttachApiForOtherPids() {
            try (MockedStatic<VirtualMachine> vmStatic = mockStatic(VirtualMachine.class)) {
                VirtualMachine targetVm = mock(VirtualMachine.class);
                vmStatic.when(() -> VirtualMachine.attach("999999")).thenReturn(targetVm);

                AttachResult result = attachService.attach("999999");

                assertThat(result.isSuccessful()).isTrue();
                assertThat(result.virtualMachine()).contains(targetVm);
            }
        }
    }
}
