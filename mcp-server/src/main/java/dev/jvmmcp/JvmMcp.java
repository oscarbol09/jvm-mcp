package dev.jvmmcp;

import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@Command(
    name = "jvm-mcp",
    mixinStandardHelpOptions = true,
    version = JvmMcp.VERSION,
    description = "Live JVM inspection via Model Context Protocol without target dependencies.",
    subcommands = { 
        ServeCommand.class, 
        ListCommand.class, 
        MemoryCommand.class, 
        ThreadsCommand.class,
        BeansCommand.class,
        PgCommand.class,
        ActuatorCommand.class
    }
)
public class JvmMcp implements Callable<Integer> {
    public static final String VERSION = "1.0.0-SNAPSHOT";

    @Override
    public Integer call() {
        // Default behavior if no subcommand is provided: show usage.
        new CommandLine(this).usage(System.out);
        return 0;
    }

    @Generated
    public static void main(String[] args) {
        int exitCode = new CommandLine(new JvmMcp()).execute(args);
        System.exit(exitCode);
    }
}
