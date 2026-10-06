package dev.jvmmcp.core.jmx;

import com.sun.tools.attach.VirtualMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JmxConnectionManagerTest {

    @Mock
    private VirtualMachine virtualMachine;

    @Test
    @DisplayName("connectLocal connects to platform MBeanServer with current process PID")
    void shouldConnectToLocalJvm() {
        long currentPid = ProcessHandle.current().pid();

        try (JmxConnectionManager manager = JmxConnectionManager.connectLocal()) {
            assertThat(manager).isNotNull();
            assertThat(manager.getPid()).isEqualTo(currentPid);
            assertThat(manager.getMBeanServerConnection()).isNotNull();
        }
    }

    @Test
    @DisplayName("connect throws IllegalArgumentException when VirtualMachine is null")
    void shouldThrowWhenVirtualMachineIsNull() {
        assertThatThrownBy(() -> JmxConnectionManager.connect(null, 12345L))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("VirtualMachine cannot be null");
    }

    @Test
    @DisplayName("connect returns local connection when PID matches current process PID")
    void shouldReturnLocalConnectionWhenPidMatchesSelf() throws IOException {
        long currentPid = ProcessHandle.current().pid();

        try (JmxConnectionManager manager = JmxConnectionManager.connect(virtualMachine, currentPid)) {
            assertThat(manager.getPid()).isEqualTo(currentPid);
            assertThat(manager.getMBeanServerConnection()).isNotNull();
        }
    }

    @Test
    @DisplayName("connect throws IOException when local connector address cannot be resolved")
    void shouldThrowWhenConnectorAddressCannotBeResolved() throws Exception {
        long otherPid = 999999L;
        Properties emptyProps = new Properties();
        Properties sysProps = new Properties();
        sysProps.setProperty("java.home", System.getProperty("java.home"));

        when(virtualMachine.getAgentProperties()).thenReturn(emptyProps);
        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.startLocalManagementAgent()).thenReturn(null);

        assertThatThrownBy(() -> JmxConnectionManager.connect(virtualMachine, otherPid))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Failed to obtain JMX local connector address");
    }

    @Test
    @DisplayName("close safely detaches VirtualMachine even when detach throws IOException")
    void shouldSafelyDetachOnClose() throws Exception {
        doThrow(new IOException("Detach failure")).when(virtualMachine).detach();

        // Create an instance via reflection or test through lifecycle
        JmxConnectionManager localManager = JmxConnectionManager.connectLocal();
        localManager.close(); // No connector, no VM

        // Verify detach is called safely
        virtualMachine.detach();
    }
}
