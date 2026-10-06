package dev.jvmmcp.core.attach;

import com.sun.tools.attach.VirtualMachine;
import java.util.Optional;

/**
 * Result of an attachment attempt containing status, diagnostics, and the optional VirtualMachine handle.
 */
public record AttachResult(
    AttachStatus status,
    String pid,
    String message,
    Optional<VirtualMachine> virtualMachine
) {
    public static AttachResult success(String pid, VirtualMachine vm) {
        return new AttachResult(AttachStatus.SUCCESS, pid, "Attached successfully to PID " + pid, Optional.ofNullable(vm));
    }

    public static AttachResult self(String pid) {
        return new AttachResult(
            AttachStatus.SUCCESS,
            pid,
            "PID " + pid + " is the current process; using local self-inspection instead of attaching.",
            Optional.empty()
        );
    }

    public static AttachResult processNotFound(String pid) {
        return new AttachResult(
            AttachStatus.PROCESS_NOT_FOUND,
            pid,
            "Process with PID " + pid + " was not found or is no longer running.",
            Optional.empty()
        );
    }

    public static AttachResult permissionDenied(String pid, String detail) {
        return new AttachResult(
            AttachStatus.PERMISSION_DENIED,
            pid,
            "Permission denied when attaching to PID " + pid + ". Ensure jvm-mcp runs with matching UID/permissions or use --actuator mode. Details: " + detail,
            Optional.empty()
        );
    }

    public static AttachResult unsupportedNamespace(String pid, String detail) {
        return new AttachResult(
            AttachStatus.UNSUPPORTED_NAMESPACE,
            pid,
            "Target JVM (PID " + pid + ") is in a different process namespace/container. Use --actuator mode or run inside container namespace. Details: " + detail,
            Optional.empty()
        );
    }

    public static AttachResult error(String pid, String message) {
        return new AttachResult(AttachStatus.GENERIC_ERROR, pid, message, Optional.empty());
    }

    public boolean isSuccessful() {
        return status == AttachStatus.SUCCESS;
    }
}
