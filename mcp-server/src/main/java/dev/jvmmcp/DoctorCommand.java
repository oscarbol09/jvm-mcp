package dev.jvmmcp;

import com.sun.tools.attach.VirtualMachine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "doctor", description = "Diagnoses the local system for Attach API permissions and JMX capability", mixinStandardHelpOptions = true)
public class DoctorCommand implements Callable<Integer> {

    @Override
    public Integer call() {
        System.out.println("=========================================");
        System.out.println("          JVM-MCP System Doctor");
        System.out.println("=========================================\n");

        boolean attachPassed = false;
        try {
            System.out.println("Checking JVM Attach API support...");
            VirtualMachine.list();
            attachPassed = true;
            System.out.println("[OK] jdk.attach module is present and functioning.");
        } catch (Throwable t) {
            System.out.println("[FAIL] Could not load or execute VirtualMachine.list()");
            System.out.println("Reason: " + t.getMessage());
        }

        System.out.println("\nChecking OS permissions...");
        String osName = System.getProperty("os.name").toLowerCase();
        if (osName.contains("nix") || osName.contains("nux") || osName.contains("aix")) {
            System.out.println("Detected Linux/Unix. Ensuring ptrace_scope allows attaching to non-child processes...");
            try {
                String ptrace = java.nio.file.Files.readString(java.nio.file.Paths.get("/proc/sys/kernel/yama/ptrace_scope")).trim();
                if ("0".equals(ptrace)) {
                    System.out.println("[OK] ptrace_scope is set to 0.");
                } else {
                    System.out.println("[WARN] ptrace_scope is " + ptrace + ". You may not be able to attach to JVMs run by your own user unless you use sudo or set it to 0:");
                    System.out.println("       echo 0 | sudo tee /proc/sys/kernel/yama/ptrace_scope");
                }
            } catch (Exception e) {
                System.out.println("[INFO] Could not read /proc/sys/kernel/yama/ptrace_scope (might not be Yama enabled).");
            }
        } else if (osName.contains("mac")) {
            System.out.println("Detected macOS. Note that attaching to processes run by other users or root requires sudo.");
        } else if (osName.contains("win")) {
            System.out.println("Detected Windows. Attaching should work via Named Pipes (e.g. \\\\.\\pipe\\javatool...) if running under the same user.");
        }

        System.out.println("\n=========================================");
        if (attachPassed) {
            System.out.println("STATUS: HEALTHY. jvm-mcp is ready to connect.");
            return 0;
        } else {
            System.out.println("STATUS: DEGRADED. Ensure you are using the bundled JRE with jdk.attach.");
            return 1;
        }
    }
}
