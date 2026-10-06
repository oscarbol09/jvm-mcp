package dev.jvmmcp.core.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import dev.jvmmcp.core.model.Framework;
import dev.jvmmcp.core.model.JvmProcess;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Core service managing JVM discovery and connection lifecycle using the JDK Attach API.
 */
public class JvmAttachService {

    public List<JvmProcess> listJvms() {
        List<JvmProcess> result = new ArrayList<>();
        List<VirtualMachineDescriptor> descriptors = VirtualMachine.list();

        for (VirtualMachineDescriptor desc : descriptors) {
            String pidStr = desc.id();
            long pid;
            try {
                pid = Long.parseLong(pidStr);
            } catch (NumberFormatException e) {
                continue;
            }

            String displayName = Objects.toString(desc.displayName(), "");
            String mainClass = extractMainClass(displayName);
            Framework framework = FrameworkDetector.detect(displayName, mainClass);

            long currentPid = ProcessHandle.current().pid();
            boolean isSelf = (pid == currentPid);

            result.add(new JvmProcess(
                pid,
                displayName,
                mainClass,
                isSelf ? System.getProperty("java.version") : "unknown",
                framework,
                true
            ));
        }

        return result;
    }

    public AttachResult attach(String pid) {
        if (pid == null || pid.isBlank()) {
            return AttachResult.error(pid, "PID cannot be null or empty.");
        }

        try {
            long requestedPid = Long.parseLong(pid);
            if (requestedPid == ProcessHandle.current().pid()) {
                // Self-attachment is blocked by default on modern JDKs (jdk.attach.allowAttachSelf=false),
                // so self-inspection must not go through the Attach API.
                return AttachResult.self(pid);
            }
        } catch (NumberFormatException ignored) {
            // Fall through to the Attach API, which produces a structured error for invalid PIDs.
        }

        try {
            VirtualMachine vm = VirtualMachine.attach(pid);
            return AttachResult.success(pid, vm);
        } catch (Exception e) {
            return mapAttachException(pid, e);
        }
    }

    AttachResult mapAttachException(String pid, Exception e) {
        if (e instanceof AttachNotSupportedException) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            if (msg.toLowerCase().contains("different") || msg.toLowerCase().contains("namespace")) {
                return AttachResult.unsupportedNamespace(pid, msg);
            }
            return AttachResult.error(pid, "Attach not supported for PID " + pid + ": " + msg);
        } else if (e instanceof IOException) {
            String msg = e.getMessage() != null ? e.getMessage() : "";
            String lower = msg.toLowerCase();
            if (lower.contains("permission denied") || lower.contains("access denied")) {
                return AttachResult.permissionDenied(pid, msg);
            }
            if (lower.contains("no such process") || lower.contains("not found")) {
                return AttachResult.processNotFound(pid);
            }
            return AttachResult.error(pid, "I/O failure attaching to PID " + pid + ": " + msg);
        } else {
            return AttachResult.error(pid, "Unexpected error attaching to PID " + pid + ": " + (e != null ? e.getMessage() : ""));
        }
    }

    public void detach(VirtualMachine vm) {
        if (vm != null) {
            try {
                vm.detach();
            } catch (IOException ignored) {
                // Detach errors are suppressed to ensure graceful cleanup
            }
        }
    }

    String extractMainClass(String displayName) {
        if (displayName == null || displayName.isBlank()) {
            return "Unknown";
        }

        String[] parts = displayName.trim().split("\\s+");
        String first = parts[0];

        if ("-jar".equalsIgnoreCase(first)) {
            if (parts.length > 1) {
                return stripJarPath(parts[1]);
            }
            return "Unknown";
        }

        if (first.toLowerCase().endsWith(".jar")) {
            return stripJarPath(first);
        }

        return first;
    }

    private String stripJarPath(String token) {
        int lastSlash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        return lastSlash >= 0 ? token.substring(lastSlash + 1) : token;
    }
}
