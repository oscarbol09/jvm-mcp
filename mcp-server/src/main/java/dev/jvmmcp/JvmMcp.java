package dev.jvmmcp;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.IVersionProvider;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;
import java.util.concurrent.Callable;

@Command(
    name = "jvm-mcp",
    mixinStandardHelpOptions = true,
    versionProvider = JvmMcp.VersionProvider.class,
    description = "Live JVM inspection via Model Context Protocol without target dependencies.",
    subcommands = { 
        ServeCommand.class, 
        ListCommand.class, 
        MemoryCommand.class, 
        ThreadsCommand.class,
        BeansCommand.class,
        PgCommand.class,
        ActuatorCommand.class,
        DoctorCommand.class
    }
)
public class JvmMcp implements Callable<Integer> {

    public static class VersionProvider implements IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[] { JvmMcp.getVersion() };
        }
    }

    public static class ManifestVersionProvider extends VersionProvider {
    }

    public static String getVersion() {
        Package pkg = JvmMcp.class.getPackage();
        String pkgVersion = (pkg != null) ? pkg.getImplementationVersion() : null;
        return resolveVersion(pkgVersion, JvmMcp.class.getResourceAsStream("/version.properties"));
    }

    static String resolveVersion(String packageVersion, InputStream resourceStream) {
        if (packageVersion != null && !packageVersion.isBlank()) {
            return packageVersion;
        }
        if (resourceStream != null) {
            try (resourceStream) {
                Properties props = new Properties();
                props.load(resourceStream);
                String propVersion = props.getProperty("version");
                if (propVersion != null && !propVersion.isBlank() && !propVersion.startsWith("${")) {
                    return propVersion.trim();
                }
            } catch (IOException ignored) {
                // fall through to default fallback
            }
        }
        return "1.1.1";
    }

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
