package dev.jvmmcp.core.jmx;

import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Properties;

/**
 * Manages JMX connection lifecycle for both local self-inspection and target JVM processes attached via Attach API.
 */
public class JmxConnectionManager implements AutoCloseable {

    private final long pid;
    private final VirtualMachine virtualMachine;
    private final JMXConnector connector;
    private final MBeanServerConnection mBeanServerConnection;

    private JmxConnectionManager(long pid, VirtualMachine vm, JMXConnector connector, MBeanServerConnection connection) {
        this.pid = pid;
        this.virtualMachine = vm;
        this.connector = connector;
        this.mBeanServerConnection = connection;
    }

    public static JmxConnectionManager connectLocal() {
        long currentPid = ProcessHandle.current().pid();
        MBeanServerConnection connection = ManagementFactory.getPlatformMBeanServer();
        return new JmxConnectionManager(currentPid, null, null, connection);
    }

    public static JmxConnectionManager connect(VirtualMachine vm, long pid) throws IOException {
        if (vm == null) {
            throw new IllegalArgumentException("VirtualMachine cannot be null when connecting via Attach API.");
        }

        long currentPid = ProcessHandle.current().pid();
        if (pid == currentPid) {
            return connectLocal();
        }

        String connectorAddress = resolveConnectorAddress(vm);
        if (connectorAddress == null || connectorAddress.isBlank()) {
            throw new IOException("Failed to obtain JMX local connector address for PID " + pid);
        }

        JMXServiceURL url = new JMXServiceURL(connectorAddress);
        JMXConnector connector = JMXConnectorFactory.connect(url);
        MBeanServerConnection connection = connector.getMBeanServerConnection();

        return new JmxConnectionManager(pid, vm, connector, connection);
    }

    static String resolveConnectorAddress(VirtualMachine vm) throws IOException {
        Properties agentProps = vm.getAgentProperties();
        String connectorAddress = agentProps.getProperty("com.sun.management.jmxremote.localConnectorAddress");

        if (connectorAddress != null && !connectorAddress.isBlank()) {
            return connectorAddress;
        }

        // Try starting the management agent dynamically if not already started
        try {
            connectorAddress = vm.startLocalManagementAgent();
            if (connectorAddress != null && !connectorAddress.isBlank()) {
                return connectorAddress;
            }
        } catch (Exception ignored) {
            // Fallback to loading management agent jar if startLocalManagementAgent fails
        }

        // Re-check agent properties
        agentProps = vm.getAgentProperties();
        connectorAddress = agentProps.getProperty("com.sun.management.jmxremote.localConnectorAddress");
        if (connectorAddress != null && !connectorAddress.isBlank()) {
            return connectorAddress;
        }

        // Fallback: load management-agent.jar from java.home
        String javaHome = vm.getSystemProperties().getProperty("java.home");
        String agentPath = javaHome + File.separator + "lib" + File.separator + "management-agent.jar";
        File agentFile = new File(agentPath);
        if (agentFile.exists()) {
            try {
                vm.loadAgent(agentFile.getAbsolutePath(), "com.sun.management.jmxremote");
                agentProps = vm.getAgentProperties();
                return agentProps.getProperty("com.sun.management.jmxremote.localConnectorAddress");
            } catch (Exception e) {
                throw new IOException("Failed to load management-agent.jar for PID: " + e.getMessage(), e);
            }
        }

        return null;
    }

    public long getPid() {
        return pid;
    }

    public MBeanServerConnection getMBeanServerConnection() {
        return mBeanServerConnection;
    }

    @Override
    public void close() {
        if (connector != null) {
            try {
                connector.close();
            } catch (IOException ignored) {}
        }
        if (virtualMachine != null) {
            try {
                virtualMachine.detach();
            } catch (IOException ignored) {}
        }
    }
}
