package dev.jvmmcp;

import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.model.JvmProcess;
import dev.jvmmcp.core.util.SimpleJson;
import picocli.CommandLine.Option;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

@Command(
    name = "list",
    aliases = {"ps", "ls"},
    description = "Lists all running Java Virtual Machines on the host",
    mixinStandardHelpOptions = true
)
public class ListCommand implements Callable<Integer> {

    @Option(names = {"--json"}, description = "Output in JSON format")
    boolean json;

    private final JvmAttachService service;
	@Option(
    names = "--attachable",
    description = "List only attachable JVM processes"
)
private boolean attachableOnly;

    public ListCommand() {
        this.service = new JvmAttachService();
    }

    public ListCommand(JvmAttachService service) {
        this.service = service;
    }

    @Override
    public Integer call() {
        List<JvmProcess> jvms = service.listJvms();
		if (attachableOnly) {
		jvms = jvms.stream()
			.filter(JvmProcess::isAttachable)
			.toList();
}

        if (json) {
            System.out.println(SimpleJson.toJson(jvms));
            return 0;
        }

        if (jvms.isEmpty()) {
            System.out.println("No running JVM processes discovered.");
            return 0;
        }

        System.out.printf("%-10s %-16s %-30s %-30s %-10s%n", "PID", "FRAMEWORK", "MAIN CLASS", "DISPLAY NAME", "ATTACHABLE");
        System.out.println("-".repeat(100));

        for (JvmProcess jvm : jvms) {
            String mainClass = jvm.mainClass();
            if (mainClass.length() > 28) {
                mainClass = mainClass.substring(0, 25) + "...";
            }
            System.out.printf(
                "%-10d %-16s %-30s %-30s %-10s%n",
                jvm.pid(),
                jvm.framework().getDisplayName(),
                mainClass,
                jvm.displayName(),
		jvm.isAttachable()
            );
        }
        return 0;
    }
}
