package dev.jvmmcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class InstallerScriptTest {

    @Test
    void missingOptionValuesProduceUsageErrors() throws IOException, InterruptedException {
        Path directory = Path.of(System.getProperty("user.dir"));
        if (!Files.isRegularFile(directory.resolve("install.sh"))) {
            directory = directory.getParent();
        }
        assertThat(directory.resolve("install.sh")).isRegularFile();

        String bash = "bash";
        if (System.getProperty("os.name").startsWith("Windows")) {
            bash = "C:\\Program Files\\Git\\bin\\bash.exe";
        }

        for (String option : new String[] {"--version", "--install-dir", "--bin-dir"}) {
            for (boolean followedByOption : new boolean[] {false, true}) {
                ProcessBuilder command =
                        followedByOption
                                ? new ProcessBuilder(bash, "install.sh", option, "--help")
                                : new ProcessBuilder(bash, "install.sh", option);
                Process process =
                        command.directory(directory.toFile()).redirectErrorStream(true).start();
                String output =
                        new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);

                assertThat(process.waitFor()).as("exit code for %s", option).isEqualTo(1);
                assertThat(output).contains("Missing value for " + option).contains("--help");
            }
        }
    }
}
