package dev.jvmmcp;

import com.sun.tools.attach.VirtualMachine;
import dev.jvmmcp.core.attach.AttachResult;
import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.server.protocol.McpDispatcher;
import dev.jvmmcp.server.tool.ActuatorToolHandler;
import dev.jvmmcp.server.tool.JvmDiagnosticToolHandler;
import dev.jvmmcp.server.tool.PostgresToolHandler;
import dev.jvmmcp.server.tool.SpringBeansToolHandler;
import dev.jvmmcp.server.tool.ToolRegistry;
import dev.jvmmcp.server.transport.McpTransport;
import java.io.InputStream;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "serve", description = "Starts the JVM-MCP server", mixinStandardHelpOptions = true)
public class ServeCommand implements Callable<Integer> {

    @Option(
            names = "--transport",
            defaultValue = "stdio",
            description = "Transport protocol: stdio (planned/experimental: sse)")
    String transport;

    @Option(
            names = "--spring",
            hidden = true,
            description = "Experimental Spring AI layer (planned)")
    boolean useSpring;

    @Option(names = "--attach", description = "Target PID to attach explicitly before serving")
    Integer targetPid;

    @Option(
            names = "--actuator",
            description = "Target Actuator Base URL (e.g. http://localhost:8080)")
    String actuatorUrl;

    private final JvmAttachService attachService;
    private final InputStream inStream;
    static InputStream testInStream = null;

    public ServeCommand() {
        this(new JvmAttachService(), null);
    }

    public ServeCommand(JvmAttachService attachService) {
        this(attachService, null);
    }

    public ServeCommand(JvmAttachService attachService, InputStream inStream) {
        this.attachService = attachService;
        this.inStream = inStream;
    }

    static Runnable createShutdownHook(VirtualMachine vm, Integer pid, JvmAttachService service) {
        return createShutdownHook(vm, pid, service, new AtomicBoolean());
    }

    static Runnable createShutdownHook(
            VirtualMachine vm, Integer pid, JvmAttachService service, AtomicBoolean detached) {
        return () -> {
            System.err.println("[jvm-mcp] Received shutdown signal. Detaching and cleaning up...");
            detachOnce(vm, pid, service, detached);
        };
    }

    private static void detachOnce(
            VirtualMachine vm, Integer pid, JvmAttachService service, AtomicBoolean detached) {
        if (vm == null || !detached.compareAndSet(false, true)) {
            return;
        }

        try {
            service.detach(vm);
            System.err.println("[jvm-mcp] Detached from PID " + pid);
        } catch (Exception e) {
            System.err.println("[jvm-mcp] Error detaching from PID " + pid + ": " + e.getMessage());
        }
    }

    @Override
    public Integer call() {
        if (!"stdio".equalsIgnoreCase(transport) && !"sse".equalsIgnoreCase(transport)) {
            System.err.println(
                    "[jvm-mcp] Unsupported transport '"
                            + transport
                            + "'. Supported values: stdio, sse.");
            new CommandLine(this).usage(System.err);
            return CommandLine.ExitCode.USAGE;
        }

        if ("sse".equalsIgnoreCase(transport) || useSpring) {
            System.err.println(
                    "[jvm-mcp] Spring layer not yet implemented. Please use stdio transport.");
            return 1;
        }

        VirtualMachine attachedVm = null;

        if (targetPid != null) {
            AttachResult result = attachService.attach(String.valueOf(targetPid));
            if (!result.isSuccessful()) {
                System.err.println(
                        "[jvm-mcp] Error attaching to target PID "
                                + targetPid
                                + ": "
                                + result.message());
                return 1;
            }

            attachedVm = result.virtualMachine().orElse(null);
            System.err.println("[jvm-mcp] Successfully attached to target PID " + targetPid);
        }

        System.err.println("[jvm-mcp] Starting server via transport: " + transport + "...");

        AtomicBoolean detached = new AtomicBoolean();
        Thread shutdownHook =
                new Thread(createShutdownHook(attachedVm, targetPid, attachService, detached));
        boolean hookRegistered = false;

        try {
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            hookRegistered = true;

            try (ToolRegistry registry = new ToolRegistry()) {
                registry.register(
                        JvmDiagnosticToolHandler.withDefaultPid(attachService, targetPid));
                registry.register(new PostgresToolHandler());
                registry.register(new ActuatorToolHandler(actuatorUrl));
                registry.register(SpringBeansToolHandler.withDefaultPid(attachService, targetPid));

                InputStream in =
                        inStream != null
                                ? inStream
                                : (testInStream != null ? testInStream : System.in);
                return new McpTransport(new McpDispatcher(registry)).start(in, System.out);
            }
        } finally {
            if (hookRegistered) {
                try {
                    Runtime.getRuntime().removeShutdownHook(shutdownHook);
                } catch (IllegalStateException ignored) {
                }
            }

            detachOnce(attachedVm, targetPid, attachService, detached);
        }
    }
}
