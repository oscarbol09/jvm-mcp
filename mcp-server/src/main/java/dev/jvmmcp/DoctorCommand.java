package dev.jvmmcp;

import dev.jvmmcp.core.discovery.JvmAttachService;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(name = "doctor", description = "Check environment readiness for JVM-MCP")
public class DoctorCommand implements Callable<Integer> {

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

        System.out.println("Checking Attach API Capabilities...");
        try {
            JvmAttachService attachService = new JvmAttachService();
            var jvms = attachService.listLocalJvms();
            System.out.println("[\u2713] Attach API is functioning.");
            System.out.println("[\u2713] Found " + jvms.size() + " local Java processes accessible to user '" + System.getProperty("user.name") + "'.");
            
            if (jvms.isEmpty()) {
                System.out.println("\nNote: No Java processes found. Ensure your target applications are running as the same OS user.");
            }
        } catch (Exception e) {
            System.out.println("[\u2717] Attach API failed to initialize: " + e.getMessage());
            System.out.println("    Resolution: Ensure you are running this CLI as the same user as the target JVM.");
            return 1;
        }

        System.out.println();
        System.out.println("Your system is ready to use JVM-MCP.");
        return 0;
    }
}
