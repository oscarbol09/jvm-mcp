package dev.jvmmcp;

import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.model.Framework;
import dev.jvmmcp.core.model.JvmProcess;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ListCommandTest {

    @Mock
    private JvmAttachService mockAttachService;

    @Test
    @DisplayName("list command should execute successfully and discover JVM processes")
    void shouldExecuteListCommand() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("list");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("ps and ls aliases should route to list command")
    void shouldSupportAliases() {
        CommandLine cmd = new CommandLine(new JvmMcp());

        int psExit = cmd.execute("ps");
        int lsExit = cmd.execute("ls");

        assertThat(psExit).isZero();
        assertThat(lsExit).isZero();
    }

    @Test
    @DisplayName("list command with --help should display options and return exit code 0")
    void shouldDisplayHelp() {
        CommandLine cmd = new CommandLine(new JvmMcp());
        int exitCode = cmd.execute("list", "--help");

        assertThat(exitCode).isZero();
    }

    @Test
    @DisplayName("Direct invocation of ListCommand should format running JVMs with truncation")
    void shouldFormatJvmListWithTruncation() {
        List<JvmProcess> jvms = List.of(
            new JvmProcess(101L, "LongApp", "org.example.service.very.long.package.name.MainApplicationClass", "21", Framework.SPRING_BOOT, true),
            new JvmProcess(102L, "ShortApp", "App", "21", Framework.QUARKUS, true)
        );
        when(mockAttachService.listJvms()).thenReturn(jvms);

        ListCommand command = new ListCommand(mockAttachService);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            String output = out.toString();
            assertThat(output).contains("PID        FRAMEWORK        MAIN CLASS                     DISPLAY NAME");
            assertThat(output).contains("101        Spring Boot      org.example.service.very...    LongApp");
            assertThat(output).contains("102        Quarkus          App                            ShortApp");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("Direct invocation when no JVMs are found should print discovery message")
    void shouldHandleEmptyJvmList() {
        when(mockAttachService.listJvms()).thenReturn(Collections.emptyList());

        ListCommand command = new ListCommand(mockAttachService);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            Integer exitCode = command.call();

            assertThat(exitCode).isZero();
            assertThat(out.toString()).contains("No running JVM processes discovered.");
        } finally {
            System.setOut(originalOut);
        }
    }
}
