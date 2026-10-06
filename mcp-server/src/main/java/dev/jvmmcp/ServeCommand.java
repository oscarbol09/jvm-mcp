package dev.jvmmcp;

import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Command(
    name = "serve",
    description = "Starts the JVM-MCP server",
    mixinStandardHelpOptions = true
)
public class ServeCommand implements Callable<Integer> {

    @Option(names = "--transport", defaultValue = "stdio", description = "Transport protocol: stdio or sse")
    String transport;

    @Option(names = "--spring", description = "Use Spring AI layer (SSE, dashboard, slower startup)")
    boolean useSpring;

    @Option(names = "--attach", description = "Target PID to attach explicitly before serving")
    Integer targetPid;

    @Option(names = "--actuator", description = "Target Actuator Base URL (e.g. http://localhost:8080)")
    String actuatorUrl;

    JvmAttachService attachService = new JvmAttachService();

    public ServeCommand() {}

    public ServeCommand(JvmAttachService attachService) {
        this.attachService = attachService;
    }

    @Override
    public Integer call() {
        if ("sse".equalsIgnoreCase(transport) || useSpring) {
            System.err.println("[jvm-mcp] Spring layer not yet implemented. Please use stdio transport.");
            return 1;
        }

        if (targetPid != null) {
            AttachResult result = attachService.attach(String.valueOf(targetPid));
            if (!result.isSuccessful()) {
                System.err.println("[jvm-mcp] Error attaching to target PID " + targetPid + ": " + result.message());
                return 1;
            }
            System.err.println("[jvm-mcp] Successfully attached to target PID " + targetPid);
        }

        System.err.println("[jvm-mcp] Starting server via transport: " + transport + "...");
        return 0;
    }
}
