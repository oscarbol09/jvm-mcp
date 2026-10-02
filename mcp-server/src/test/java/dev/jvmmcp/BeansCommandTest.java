package dev.jvmmcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;

class BeansCommandTest {

    @Test
    @DisplayName("beans command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        StringWriter out = new StringWriter();
        CommandLine cmd = new CommandLine(new JvmMcp());
        cmd.setOut(new PrintWriter(out));

        int exitCode = cmd.execute("beans", "--help");

        assertThat(exitCode).isZero();
        assertThat(out.toString()).contains("Inspects live Spring Boot ApplicationContext");
        assertThat(out.toString()).contains("--filter");
        assertThat(out.toString()).contains("--detail");
        assertThat(out.toString()).contains("--actuator");
        assertThat(out.toString()).contains("--actuator-user");
        assertThat(out.toString()).contains("--actuator-password");
        assertThat(out.toString()).contains("--actuator-token");
        assertThat(out.toString()).contains("--insecure");
    }

    @Test
    @DisplayName("beans command should reject Basic and Bearer credentials used together")
    void shouldRejectBasicAndBearerTogether() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans", "--actuator", "http://localhost:1",
            "--actuator-user", "admin", "--actuator-password", "pw", "--actuator-token", "tok");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("beans command should reject a username without a password")
    void shouldRejectUserWithoutPassword() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans", "--actuator", "http://localhost:1", "--actuator-user", "admin");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("beans command with missing PID and no actuator URL should return error exit code")
    void shouldFailOnMissingPidAndActuator() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans");

        assertThat(exitCode).isEqualTo(1);
    }

    @Test
    @DisplayName("beans command with non-existent PID should return error exit code")
    void shouldFailGracefullyOnNonExistentPid() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("beans", "999999999");

        assertThat(exitCode).isEqualTo(1);
    }
}
