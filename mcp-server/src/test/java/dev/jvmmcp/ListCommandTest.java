package dev.jvmmcp;

import dev.jvmmcp.core.attach.JvmAttachService;
import dev.jvmmcp.core.model.Framework;
import dev.jvmmcp.core.model.JvmProcess;
import dev.jvmmcp.core.util.SimpleJson;
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
        String exact28 = "1234567890123456789012345678";

        List<JvmProcess> jvms = List.of(
            new JvmProcess(
                101L,
                "LongApp",
                "org.example.service.very.long.package.name.MainApplicationClass",
                "21",
                Framework.SPRING_BOOT,
                true
            ),
            new JvmProcess(
                102L,
                "ShortApp",
                "App",
                "21",
                Framework.QUARKUS,
                true
            ),
            new JvmProcess(
                103L,
                "ExactApp",
                exact28,
                "21",
                Framework.PLAIN_JAVA,
                true
            )
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
            assertThat(output).contains("ATTACHABLE");
            assertThat(output).contains("-".repeat(100));
            assertThat(output)
                .contains("101        Spring Boot      org.example.service.very....   LongApp");
            assertThat(output)
                .contains("102        Quarkus          App                            ShortApp");
            assertThat(output)
                .contains("103        Plain Java       " + exact28 + "   ExactApp");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("--attachable should show only attachable JVM processes")
    void shouldFilterAttachableJvms() {
        List<JvmProcess> jvms = List.of(
            new JvmProcess(
                101L,
                "AttachableApp",
                "com.example.AttachableMain",
                "21",
                Framework.PLAIN_JAVA,
                true
            ),
            new JvmProcess(
                102L,
                "NotAttachableApp",
                "com.example.NotAttachableMain",
                "21",
                Framework.PLAIN_JAVA,
                false
            )
        );

        when(mockAttachService.listJvms()).thenReturn(jvms);

        CommandLine cmd = new CommandLine(new ListCommand(mockAttachService));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(out));

            int exitCode = cmd.execute("--attachable");

            assertThat(exitCode).isZero();

            String output = out.toString();
            assertThat(output).contains("AttachableApp");
            assertThat(output).doesNotContain("NotAttachableApp");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("list without --attachable should include all JVM processes")
    void shouldIncludeAllJvmsByDefault() {
        List<JvmProcess> jvms = List.of(
            new JvmProcess(
                101L,
                "AttachableApp",
                "com.example.AttachableMain",
                "21",
                Framework.PLAIN_JAVA,
                true
            ),
            new JvmProcess(
                102L,
                "NotAttachableApp",
                "com.example.NotAttachableMain",
                "21",
                Framework.PLAIN_JAVA,
                false
            )
        );

        when(mockAttachService.listJvms()).thenReturn(jvms);

        CommandLine cmd = new CommandLine(
            new ListCommand(mockAttachService)
        );

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;

        try {
            System.setOut(new PrintStream(out));

            int exitCode = cmd.execute();

            assertThat(exitCode).isZero();

            String output = out.toString();
            assertThat(output).contains("AttachableApp");
            assertThat(output).contains("NotAttachableApp");
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
            assertThat(out.toString())
                .contains("No running JVM processes discovered.");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("Default constructor should initialize properly")
    void shouldInitializeWithDefaultConstructor() {
        ListCommand command = new ListCommand();

        assertThat(command).isNotNull();
    }
    @Test
    @DisplayName("list command should output a JSON array with --json")
    void shouldOutputJsonList() {
        List<JvmProcess> jvms = List.of(
            new JvmProcess(101L, "JsonApp", "com.example.Main", "21", Framework.PLAIN_JAVA, true)
        );
        when(mockAttachService.listJvms()).thenReturn(jvms);

        ListCommand command = new ListCommand(mockAttachService);
        command.json = true;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            assertThat(command.call()).isZero();
            String output = out.toString().trim();
            assertThat(SimpleJson.parse(output)).isInstanceOf(List.class);
            assertThat(output).contains("\"pid\":101").doesNotContain("FRAMEWORK", "MAIN CLASS", "----");
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    @DisplayName("list command should output an empty JSON array when no JVMs are found")
    void shouldOutputEmptyJsonList() {
        when(mockAttachService.listJvms()).thenReturn(Collections.emptyList());

        ListCommand command = new ListCommand(mockAttachService);
        command.json = true;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream originalOut = System.out;
        try {
            System.setOut(new PrintStream(out));
            assertThat(command.call()).isZero();
            assertThat(out.toString().trim()).isEqualTo("[]");
        } finally {
            System.setOut(originalOut);
        }
    }
}
