package dev.jvmmcp.core.attach;

import com.sun.tools.attach.AttachNotSupportedException;
import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import dev.jvmmcp.core.model.Framework;
import dev.jvmmcp.core.model.JvmProcess;
import java.io.IOException;
import java.lang.System.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/** Core service managing JVM discovery and connection lifecycle using the JDK Attach API. */
public class JvmAttachService {

    private static final Logger LOGGER = System.getLogger(JvmAttachService.class.getName());

    private final Function<Long, Optional<ProcessHandle>> processLookup;
    private final Supplier<ProcessHandle> currentProcessSupplier;

    public JvmAttachService() {
        this(ProcessHandle::of, ProcessHandle::current);
    }

    JvmAttachService(
            Function<Long, Optional<ProcessHandle>> processLookup,
            Supplier<ProcessHandle> currentProcessSupplier) {
        this.processLookup =
                Objects.requireNonNull(processLookup, "processLookup must not be null");
        this.currentProcessSupplier =
                Objects.requireNonNull(
                        currentProcessSupplier, "currentProcessSupplier must not be null");
    }

    public List<JvmProcess> listJvms() {
        long start = System.nanoTime();
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

            ProcessHandle currentProcess = currentProcessSupplier.get();
            long currentPid = currentProcess.pid();
            boolean isSelf = (pid == currentPid);
            Optional<ProcessHandle> processHandle = processLookup.apply(pid);

            boolean isAttachable =
                    processHandle.isPresent()
                            && (isSelf
                                    || (processHandle.get().isAlive()
                                            && !isCrossUser(
                                                    currentProcess.info().user(),
                                                    processHandle.get().info().user())));

            result.add(
                    new JvmProcess(
                            pid,
                            displayName,
                            mainClass,
                            isSelf ? System.getProperty("java.version") : "unknown",
                            framework,
                            isAttachable));
        }

        long durationMs = (System.nanoTime() - start) / 1_000_000;
        LOGGER.log(Logger.Level.DEBUG, "Discovered {0} JVMs in {1} ms", result.size(), durationMs);

        return result;
    }

    public AttachResult attach(String pid) {
        if (pid == null || pid.isBlank()) {
            return AttachResult.error(pid, "PID cannot be null or empty.");
        }

        long requestedPid;
        try {
            requestedPid = Long.parseLong(pid);
        } catch (NumberFormatException e) {
            return AttachResult.error(pid, "PID must be numeric.");
        }

        if (requestedPid <= 0) {
            return AttachResult.error(pid, "PID must be a positive number.");
        }

        if (requestedPid == currentProcessSupplier.get().pid()) {
            // Self-attachment is blocked by default on modern JDKs
            // (jdk.attach.allowAttachSelf=false),
            // so self-inspection must not go through the Attach API.
            return AttachResult.self(pid);
        }

        Optional<ProcessHandle> processHandle = processLookup.apply(requestedPid);
        if (processHandle.isPresent()) {
            if (!processHandle.get().isAlive()) {
                return AttachResult.processNotFound(pid);
            }
            Optional<String> currentUser = currentProcessSupplier.get().info().user();
            Optional<String> targetUser = processHandle.get().info().user();
            if (isCrossUser(currentUser, targetUser)) {
                String targetUserName = targetUser.orElse("unknown");
                return AttachResult.permissionDenied(
                        pid,
                        "Target JVM process is owned by a different user ("
                                + targetUserName
                                + "). Cross-user attachment is prohibited.");
            }
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
            if (msg.toLowerCase().contains("different")
                    || msg.toLowerCase().contains("namespace")) {
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
            return AttachResult.error(
                    pid,
                    "Unexpected error attaching to PID "
                            + pid
                            + ": "
                            + (e != null ? e.getMessage() : ""));
        }
    }

    static boolean isCrossUser(
            java.util.Optional<String> currentUser, java.util.Optional<String> targetUser) {
        if (currentUser.isPresent() && targetUser.isPresent()) {
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                return !currentUser.get().equalsIgnoreCase(targetUser.get());
            }
            return !currentUser.get().equals(targetUser.get());
        }
        return false;
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

        String command = displayName.trim();

        // Handle "-jar " prefix explicitly
        if (command.toLowerCase().startsWith("-jar ")) {
            command = command.substring(5).trim();
        }

        // Java 9+ modular launches name "module/Main" after -m, --module or --module=
        if (command.startsWith("-m ")) {
            command = command.substring(3).trim();
        } else if (command.startsWith("--module ")) {
            command = command.substring(9).trim();
        } else if (command.startsWith("--module=")) {
            command = command.substring(9).trim();
        }

        String launchTarget = firstToken(command);
        if (launchTarget.toLowerCase().endsWith(".jar")) {
            return stripJarPath(launchTarget);
        }

        return launchTarget;
    }

    private String firstToken(String command) {
        if (command.startsWith("\"")) {
            int closingQuote = command.indexOf('"', 1);
            if (closingQuote > 0) {
                return command.substring(1, closingQuote);
            }
        }

        int spaceIndex = command.indexOf(' ');
        return spaceIndex == -1 ? command : command.substring(0, spaceIndex);
    }

    private String stripJarPath(String token) {
        int lastSlash = Math.max(token.lastIndexOf('/'), token.lastIndexOf('\\'));
        return lastSlash >= 0 ? token.substring(lastSlash + 1) : token;
    }
}
