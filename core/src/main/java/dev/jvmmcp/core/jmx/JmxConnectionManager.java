package dev.jvmmcp.core.jmx;

import com.sun.tools.attach.VirtualMachine;

import javax.management.MBeanServerConnection;
import javax.management.remote.JMXConnector;
import javax.management.remote.JMXConnectorFactory;
import javax.management.remote.JMXServiceURL;
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
            return new JmxConnectionManager(pid, vm, null, ManagementFactory.getPlatformMBeanServer());
        }

        String connectorAddress = resolveConnectorAddress(vm);
        if (connectorAddress == null || connectorAddress.isBlank()) {
            throw new IOException("Failed to establish local JMX connection to JVM process " + pid +
                ". The local management agent could not be started or resolved " +
                "(ensure target JVM allows dynamic attachment and has not disabled dynamic agent loading).");
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
            // Ignored: the caller reports a clear error if no address can be resolved
        }

        // Re-check agent properties
        agentProps = vm.getAgentProperties();
        connectorAddress = agentProps.getProperty("com.sun.management.jmxremote.localConnectorAddress");
        if (connectorAddress != null && !connectorAddress.isBlank()) {
            return connectorAddress;
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
