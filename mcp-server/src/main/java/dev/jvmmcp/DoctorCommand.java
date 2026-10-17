package dev.jvmmcp;

import dev.jvmmcp.core.attach.JvmAttachService;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "doctor", mixinStandardHelpOptions = true, description = "Check environment readiness for JVM-MCP")
public class DoctorCommand implements Callable<Integer> {

    private final JvmAttachService attachService;

    public DoctorCommand() {
        this(new JvmAttachService());
    }

    public DoctorCommand(JvmAttachService attachService) {
        this.attachService = attachService;
    }

    @Override
    public Integer call() {
        System.out.println("JVM-MCP Doctor");
        System.out.println("--------------");
        System.out.println("OS Name:       " + System.getProperty("os.name"));
        System.out.println("OS Arch:       " + System.getProperty("os.arch"));
        System.out.println("OS Version:    " + System.getProperty("os.version"));
        System.out.println("Java Version:  " + System.getProperty("java.version"));
        System.out.println("Java Vendor:   " + System.getProperty("java.vendor"));
        System.out.println("Java Home:     " + System.getProperty("java.home"));
        System.out.println("User Name:     " + System.getProperty("user.name"));
        System.out.println();

        System.out.println("Checking JVM Runtime Compatibility...");
        checkJvmVendor(System.getProperty("java.vendor", ""), System.getProperty("java.vm.name", ""), System.out);
        checkContainerEnvironment(System.out);

        String osName = System.getProperty("os.name", "").toLowerCase();
        if (osName.contains("linux")) {
            checkLinuxPtraceScope(System.out);
        }
        System.out.println();

        System.out.println("Checking Attach API Capabilities...");
        try {
            var jvms = attachService.listJvms();
            System.out.println("[OK] Attach API is functioning.");
            System.out.println("[OK] Found " + jvms.size() + " local Java processes accessible to user '" + System.getProperty("user.name") + "'.");
            
            if (jvms.isEmpty()) {
                System.out.println("\nNote: No Java processes found. Ensure your target applications are running as the same OS user.");
            }
        } catch (Exception e) {
            System.out.println("[ERROR] Attach API failed to initialize: " + e.getMessage());
            System.out.println("    Resolution: Ensure you are running this CLI as the same user as the target JVM.");
            return 1;
        }

        System.out.println();
        System.out.println("Your system is ready to use JVM-MCP.");
        return 0;
    }

    static void checkJvmVendor(String vendor, String vmName, java.io.PrintStream out) {
        String combined = ((vendor != null ? vendor : "") + " " + (vmName != null ? vmName : "")).toLowerCase();
        if (combined.contains("openj9") || combined.contains("semeru") || combined.contains("ibm")) {
            out.println("[!] Detected OpenJ9 / IBM JVM. HotSpot Attach API (jdk.attach) has limited compatibility. Use Actuator HTTP mode if local attach fails.");
        } else {
            out.println("[OK] HotSpot-compatible JVM detected (" + (vendor != null && !vendor.isBlank() ? vendor : "Standard") + "). Dynamic Attach API fully supported.");
        }
    }

    static void checkContainerEnvironment(java.io.PrintStream out) {
        checkContainerEnvironment(java.nio.file.Path.of("/.dockerenv"), java.nio.file.Path.of("/run/.containerenv"), out);
    }

    static void checkContainerEnvironment(java.nio.file.Path dockerenv, java.nio.file.Path containerenv, java.io.PrintStream out) {
        if (java.nio.file.Files.exists(dockerenv) || java.nio.file.Files.exists(containerenv)) {
            out.println("[!] Container environment detected. JVM Attach API requires sharing the host PID namespace (--pid=host) or executing within the same container namespace.");
        }
    }

    static void checkLinuxPtraceScope(java.io.PrintStream out) {
        checkLinuxPtraceScope(java.nio.file.Path.of("/proc/sys/kernel/yama/ptrace_scope"), out);
    }

    static void checkLinuxPtraceScope(java.nio.file.Path ptracePath, java.io.PrintStream out) {
        if (java.nio.file.Files.exists(ptracePath)) {
            try {
                String val = java.nio.file.Files.readString(ptracePath).trim();
                if ("0".equals(val)) {
                    out.println("[OK] Linux yama/ptrace_scope is 0 (classic ptrace permissions; unrestricted same-user attach).");
                } else {
                    out.println("[!] Linux yama/ptrace_scope is set to " + val + ". Non-child attach requires target JVM to allow ptrace or matching UID/CAP_SYS_PTRACE capability.");
                }
            } catch (Exception ignored) {
                // Ignore if permission denied reading procfs
            }
        }
    }
}
