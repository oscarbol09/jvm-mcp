package dev.jvmmcp.core.jmx;

import com.sun.tools.attach.VirtualMachine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import javax.management.MBeanServer;
import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXServiceURL;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
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
    @DisplayName("resolveConnectorAddress returns existing agent property address")
    void shouldResolveAddressFromExistingAgentProperties() throws IOException {
        Properties agentProps = new Properties();
        agentProps.setProperty("com.sun.management.jmxremote.localConnectorAddress", "service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi");
        when(virtualMachine.getAgentProperties()).thenReturn(agentProps);

        String address = JmxConnectionManager.resolveConnectorAddress(virtualMachine);
        assertThat(address).isEqualTo("service:jmx:rmi:///jndi/rmi://localhost:9999/jmxrmi");
    }

    @Test
    @DisplayName("resolveConnectorAddress starts local management agent when not running")
    void shouldStartLocalManagementAgent() throws Exception {
        Properties emptyProps = new Properties();
        when(virtualMachine.getAgentProperties()).thenReturn(emptyProps);
        when(virtualMachine.startLocalManagementAgent()).thenReturn("service:jmx:rmi:///jndi/rmi://localhost:8888/jmxrmi");

        String address = JmxConnectionManager.resolveConnectorAddress(virtualMachine);
        assertThat(address).isEqualTo("service:jmx:rmi:///jndi/rmi://localhost:8888/jmxrmi");
    }

    @Test
    @DisplayName("resolveConnectorAddress falls back to re-checking properties after agent start fails")
    void shouldHandleStartAgentExceptionAndRecheckProps() throws Exception {
        Properties initialProps = new Properties();
        Properties updatedProps = new Properties();
        updatedProps.setProperty("com.sun.management.jmxremote.localConnectorAddress", "service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi");

        when(virtualMachine.getAgentProperties()).thenReturn(initialProps, updatedProps);
        when(virtualMachine.startLocalManagementAgent()).thenThrow(new IOException("Agent start failed"));

        String address = JmxConnectionManager.resolveConnectorAddress(virtualMachine);
        assertThat(address).isEqualTo("service:jmx:rmi:///jndi/rmi://localhost:7777/jmxrmi");
    }

    @Test
    @DisplayName("resolveConnectorAddress loads management-agent.jar fallback from java.home")
    void shouldFallbackToLoadingManagementAgentJar(@TempDir Path tempJavaHome) throws Exception {
        Path libDir = tempJavaHome.resolve("lib");
        Files.createDirectories(libDir);
        Path agentJar = libDir.resolve("management-agent.jar");
        Files.writeString(agentJar, "dummy-agent");

        Properties sysProps = new Properties();
        sysProps.setProperty("java.home", tempJavaHome.toString());

        Properties emptyProps = new Properties();
        Properties loadedProps = new Properties();
        loadedProps.setProperty("com.sun.management.jmxremote.localConnectorAddress", "service:jmx:rmi:///jndi/rmi://localhost:6666/jmxrmi");

        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.getAgentProperties()).thenReturn(emptyProps, emptyProps, loadedProps);
        when(virtualMachine.startLocalManagementAgent()).thenThrow(new IOException());

        String address = JmxConnectionManager.resolveConnectorAddress(virtualMachine);
        assertThat(address).isEqualTo("service:jmx:rmi:///jndi/rmi://localhost:6666/jmxrmi");
        verify(virtualMachine).loadAgent(agentJar.toAbsolutePath().toString(), "com.sun.management.jmxremote");
    }

    @Test
    @DisplayName("resolveConnectorAddress throws IOException when loadAgent fails")
    void shouldThrowWhenLoadAgentFails(@TempDir Path tempJavaHome) throws Exception {
        Path libDir = tempJavaHome.resolve("lib");
        Files.createDirectories(libDir);
        Path agentJar = libDir.resolve("management-agent.jar");
        Files.writeString(agentJar, "dummy-agent");

        Properties sysProps = new Properties();
        sysProps.setProperty("java.home", tempJavaHome.toString());
        Properties emptyProps = new Properties();

        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.getAgentProperties()).thenReturn(emptyProps);
        when(virtualMachine.startLocalManagementAgent()).thenThrow(new IOException());
        doThrow(new RuntimeException("Agent load error")).when(virtualMachine).loadAgent(anyString(), anyString());

        assertThatThrownBy(() -> JmxConnectionManager.resolveConnectorAddress(virtualMachine))
            .isInstanceOf(IOException.class)
            .hasMessageContaining("Failed to load management-agent.jar");
    }

    @Test
    @DisplayName("connect establishes live remote connection when connector address is valid")
    void shouldConnectViaRemoteConnectorAddress() throws Exception {
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
        JMXServiceURL serviceUrl = new JMXServiceURL("service:jmx:rmi://127.0.0.1");
        JMXConnectorServer connectorServer = JMXConnectorServerFactory.newJMXConnectorServer(serviceUrl, null, mbs);
        connectorServer.start();

        try {
            Properties props = new Properties();
            props.setProperty("com.sun.management.jmxremote.localConnectorAddress", connectorServer.getAddress().toString());
            when(virtualMachine.getAgentProperties()).thenReturn(props);

            try (JmxConnectionManager manager = JmxConnectionManager.connect(virtualMachine, 888888L)) {
                assertThat(manager.getPid()).isEqualTo(888888L);
                assertThat(manager.getMBeanServerConnection()).isNotNull();
            }
        } finally {
            connectorServer.stop();
        }
    }

    @Test
    @DisplayName("resolveConnectorAddress returns null when agent jar and connector address missing")
    void shouldReturnNullWhenConnectorAddressMissing() throws Exception {
        Properties emptyProps = new Properties();
        Properties sysProps = new Properties();
        sysProps.setProperty("java.home", System.getProperty("java.home"));

        when(virtualMachine.getAgentProperties()).thenReturn(emptyProps);
        when(virtualMachine.getSystemProperties()).thenReturn(sysProps);
        when(virtualMachine.startLocalManagementAgent()).thenReturn(null);

        String address = JmxConnectionManager.resolveConnectorAddress(virtualMachine);
        assertThat(address).isNull();
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
    @DisplayName("close safely detaches VirtualMachine and closes connector suppressing IOExceptions")
    void shouldSafelyDetachOnClose() throws Exception {
        Constructor<JmxConnectionManager> ctor = JmxConnectionManager.class.getDeclaredConstructor(
            long.class, VirtualMachine.class, JMXConnector.class, MBeanServerConnection.class
        );
        ctor.setAccessible(true);
        JMXConnector mockConnector = mock(JMXConnector.class);
        doThrow(new IOException("Close failure")).when(mockConnector).close();
        doThrow(new IOException("Detach failure")).when(virtualMachine).detach();

        JmxConnectionManager manager = ctor.newInstance(123L, virtualMachine, mockConnector, mock(MBeanServerConnection.class));
        manager.close();
    }
}
