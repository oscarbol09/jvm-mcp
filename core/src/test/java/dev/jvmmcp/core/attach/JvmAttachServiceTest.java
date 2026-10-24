package dev.jvmmcp.core.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import dev.jvmmcp.core.model.JvmProcess;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

        @Test
        @DisplayName("should mark JVM as not attachable when owned by a different user")
        void shouldMarkCrossUserJvmAsNotAttachable() {
            long currentPid = 1000L;
            long targetPid = 99999L;

            ProcessHandle currentProcess = mock(ProcessHandle.class);
            ProcessHandle.Info currentInfo = mock(ProcessHandle.Info.class);

            when(currentProcess.pid()).thenReturn(currentPid);
            when(currentProcess.info()).thenReturn(currentInfo);
            when(currentInfo.user()).thenReturn(Optional.of("alice"));

            ProcessHandle targetProcess = mock(ProcessHandle.class);
            ProcessHandle.Info targetInfo = mock(ProcessHandle.Info.class);

            when(targetProcess.isAlive()).thenReturn(true);
            when(targetProcess.info()).thenReturn(targetInfo);
            when(targetInfo.user()).thenReturn(Optional.of("bob"));

            JvmAttachService service = new JvmAttachService(
                pid -> pid == targetPid
                    ? Optional.of(targetProcess)
                    : Optional.empty(),
                () -> currentProcess
            );

            VirtualMachineDescriptor descriptor =
                mock(VirtualMachineDescriptor.class);
            when(descriptor.id()).thenReturn(String.valueOf(targetPid));
            when(descriptor.displayName()).thenReturn("org.example.Application");

            try (MockedStatic<VirtualMachine> vmStatic =
                     mockStatic(VirtualMachine.class)) {
                vmStatic.when(VirtualMachine::list)
                    .thenReturn(List.of(descriptor));

                List<JvmProcess> jvms = service.listJvms();

                assertThat(jvms).hasSize(1);
                assertThat(jvms.get(0).pid()).isEqualTo(targetPid);
                assertThat(jvms.get(0).isAttachable()).isFalse();
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
                vmStatic.when(() -> VirtualMachine.attach("888888")).thenReturn(mockVm);
                AttachResult result = attachService.attach("888888");
                assertThat(result.isSuccessful()).isTrue();
                assertThat(result.virtualMachine()).isPresent();
            }
        }

        @Test
        @DisplayName("should reject cross-user attachment when target process user differs from current user")
        void shouldRejectCrossUserAttachmentWhenTargetUserDiffers() {
            ProcessHandle targetProcess = mock(ProcessHandle.class);
            ProcessHandle.Info targetInfo = mock(ProcessHandle.Info.class);
            org.mockito.Mockito.when(targetProcess.isAlive()).thenReturn(true);
            org.mockito.Mockito.when(targetProcess.info()).thenReturn(targetInfo);
            org.mockito.Mockito.when(targetInfo.user()).thenReturn(java.util.Optional.of("other-user"));

            ProcessHandle currentProcess = mock(ProcessHandle.class);
            ProcessHandle.Info currentInfo = mock(ProcessHandle.Info.class);
            org.mockito.Mockito.when(currentProcess.pid()).thenReturn(1000L);
            org.mockito.Mockito.when(currentProcess.info()).thenReturn(currentInfo);
            org.mockito.Mockito.when(currentInfo.user()).thenReturn(java.util.Optional.of("current-user"));

            JvmAttachService service = new JvmAttachService(
                pid -> pid == 99999L ? java.util.Optional.of(targetProcess) : java.util.Optional.empty(),
                () -> currentProcess
            );

            AttachResult result = service.attach("99999");

            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.status()).isEqualTo(AttachStatus.PERMISSION_DENIED);
            assertThat(result.message()).contains("Target JVM process is owned by a different user (other-user). Cross-user attachment is prohibited.");
        }

        @Test
        @DisplayName("should return PROCESS_NOT_FOUND when target process is not alive")
        void shouldReturnProcessNotFoundWhenTargetProcessIsNotAlive() {
            ProcessHandle deadProcess = mock(ProcessHandle.class);
            org.mockito.Mockito.when(deadProcess.isAlive()).thenReturn(false);

            ProcessHandle currentProcess = mock(ProcessHandle.class);
            org.mockito.Mockito.when(currentProcess.pid()).thenReturn(1000L);

            JvmAttachService service = new JvmAttachService(
                pid -> pid == 99999L ? java.util.Optional.of(deadProcess) : java.util.Optional.empty(),
                () -> currentProcess
            );

            AttachResult result = service.attach("99999");

            assertThat(result.isSuccessful()).isFalse();
            assertThat(result.status()).isEqualTo(AttachStatus.PROCESS_NOT_FOUND);
        }

        @Test
        @DisplayName("should evaluate isCrossUser policy deterministically across all permutations")
        void testIsCrossUserPolicy() {
            java.util.Optional<String> userA = java.util.Optional.of("alice");
            java.util.Optional<String> userB = java.util.Optional.of("bob");
            java.util.Optional<String> sameA = java.util.Optional.of("alice");
            java.util.Optional<String> empty = java.util.Optional.empty();

            assertThat(JvmAttachService.isCrossUser(userA, userB)).isTrue();
            assertThat(JvmAttachService.isCrossUser(userA, sameA)).isFalse();
            assertThat(JvmAttachService.isCrossUser(userA, empty)).isFalse();
            assertThat(JvmAttachService.isCrossUser(empty, userB)).isFalse();
            assertThat(JvmAttachService.isCrossUser(empty, empty)).isFalse();
        }

        @ParameterizedTest
        @ValueSource(strings = {"Windows 10", "Windows 11", "Windows Server 2022", "Linux", "Mac OS X", "Darwin"})
        @ResourceLock("java.lang.System.properties")
        @DisplayName("should ignore account name casing only on Windows")
        void shouldCompareUsersAccordingToPlatform(String osName) {
            String originalOsName = System.getProperty("os.name");
            try {
                System.setProperty("os.name", osName);
                var current = java.util.Optional.of("DOMAIN\\Alice");
                var sameAccount = java.util.Optional.of("domain\\alice");
                var differentUser = java.util.Optional.of("DOMAIN\\Bob");
                var differentDomain = java.util.Optional.of("OTHER\\Alice");

                assertThat(JvmAttachService.isCrossUser(current, sameAccount))
                    .isEqualTo(!osName.startsWith("Windows"));
                assertThat(JvmAttachService.isCrossUser(current, differentUser)).isTrue();
                assertThat(JvmAttachService.isCrossUser(current, differentDomain)).isTrue();
            } finally {
                if (originalOsName == null) {
                    System.clearProperty("os.name");
                } else {
                    System.setProperty("os.name", originalOsName);
                }
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

        @Test
        @DisplayName("should reject negative or zero PIDs explicitly")
        void shouldRejectNegativeOrZeroPids() {
            AttachResult negativeResult = attachService.attach("-5");
            assertThat(negativeResult.isSuccessful()).isFalse();
            assertThat(negativeResult.status()).isEqualTo(AttachStatus.GENERIC_ERROR);
            assertThat(negativeResult.message()).contains("PID must be a positive number");

            AttachResult zeroResult = attachService.attach("0");
            assertThat(zeroResult.isSuccessful()).isFalse();
            assertThat(zeroResult.status()).isEqualTo(AttachStatus.GENERIC_ERROR);
            assertThat(zeroResult.message()).contains("PID must be a positive number");
        }

        @Test
        @DisplayName("should reject non-numeric PIDs explicitly")
        void shouldRejectNonNumericPids() {
            AttachResult nonNumeric = attachService.attach("abc-not-a-pid");
            assertThat(nonNumeric.isSuccessful()).isFalse();
            assertThat(nonNumeric.status()).isEqualTo(AttachStatus.GENERIC_ERROR);
            assertThat(nonNumeric.message()).contains("PID must be numeric");
        }

        @Test
        @DisplayName("should handle self PID attachment via local self-inspection")
        void shouldHandleSelfAttachment() {
            long currentPid = ProcessHandle.current().pid();
            AttachResult selfResult = attachService.attach(String.valueOf(currentPid));
            assertThat(selfResult.status()).isEqualTo(AttachStatus.SUCCESS);
            assertThat(selfResult.isSuccessful()).isTrue();
            assertThat(selfResult.virtualMachine()).isEmpty();
            assertThat(selfResult.message()).contains("current process");
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

        @Test
        @DisplayName("extractMainClass reports the module/Main descriptor of a modular launch, not the flag")
        void shouldExtractMainClassOfModularLaunch() {
            assertThat(attachService.extractMainClass("-m my.module/com.foo.Main")).isEqualTo("my.module/com.foo.Main");
            assertThat(attachService.extractMainClass("--module my.module/com.foo.Main --port 8080")).isEqualTo("my.module/com.foo.Main");
            assertThat(attachService.extractMainClass("--module=my.module/com.foo.Main")).isEqualTo("my.module/com.foo.Main");
            assertThat(attachService.extractMainClass("-m my.module")).isEqualTo("my.module");
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
